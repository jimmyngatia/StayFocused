package com.personal.statusguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Sleep schedules and the apps you can still use at night. Only reachable with the password. */
class SleepActivity : Activity() {
    private lateinit var store: SleepStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sleep)
        store = SleepStore(this)
        findViewById<Button>(R.id.addScheduleButton).setOnClickListener { editSchedule(null) }
        findViewById<Button>(R.id.chooseAppsButton).setOnClickListener { chooseApps() }
        refresh()
    }

    /** Leaving this screen closes it, so it can't be reopened from recent apps without the password. */
    override fun onStop() {
        super.onStop()
        finish()
    }

    private fun refresh() {
        val list = findViewById<LinearLayout>(R.id.scheduleList)
        list.removeAllViews()
        val schedules = store.schedules()
        if (schedules.isEmpty()) list.addView(TextView(this).apply { text = "No schedules yet."; alpha = 0.7f })
        for (s in schedules) {
            list.addView(TextView(this).apply {
                text = "${s.bedText()} → ${s.wakeText()}\n${s.daysText()}" + if (s.enabled) "" else "  ·  off"
                textSize = 18f
                alpha = if (s.enabled) 1f else 0.5f
                background = getDrawable(R.drawable.card_background)
                setPadding(dp(Space.L), dp(Space.M), dp(Space.L), dp(Space.M))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(Space.S) }
                setOnClickListener { editSchedule(s) }
            })
        }
        val basic = store.basicApps().map { PhoneUsage.appName(this, it) }
        val extra = store.extraApps.map { PhoneUsage.appName(this, it) }.sorted()
        findViewById<TextView>(R.id.appsText).text =
            "Always: " + (basic + "WhatsApp calls").joinToString(", ") +
                "\nAlso: " + (if (extra.isEmpty()) "none yet" else extra.joinToString(", ")) +
                "\n\nEverything else is covered by the night screen."
        Routine.scheduleAll(this)
    }

    private fun editSchedule(existing: SleepSchedule?) {
        val view = layoutInflater.inflate(R.layout.dialog_sleep_schedule, null)
        val bed = view.findViewById<TimePicker>(R.id.bed).apply {
            setIs24HourView(true)
            hour = (existing?.bed ?: (22 * 60 + 30)) / 60
            minute = (existing?.bed ?: (22 * 60 + 30)) % 60
        }
        val wake = view.findViewById<TimePicker>(R.id.wake).apply {
            setIs24HourView(true)
            hour = (existing?.wake ?: (5 * 60 + 30)) / 60
            minute = (existing?.wake ?: (5 * 60 + 30)) % 60
        }
        val enabled = view.findViewById<CheckBox>(R.id.enabled).apply { isChecked = existing?.enabled ?: true }
        val dayBoxes = (1..7).associateWith { day ->
            CheckBox(this).apply {
                text = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())
                isChecked = existing == null || day in existing.days
                view.findViewById<LinearLayout>(R.id.days).addView(this)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                view.findFocus()?.clearFocus() // a time typed into a picker only counts once the picker loses focus
                store.save(
                    SleepSchedule(
                        id = existing?.id ?: store.newId(),
                        bed = bed.hour * 60 + bed.minute,
                        wake = wake.hour * 60 + wake.minute,
                        days = dayBoxes.filterValues { it.isChecked }.keys,
                        enabled = enabled.isChecked,
                    )
                )
                refresh()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) dialog.setNeutralButton("Delete") { _, _ -> store.delete(existing.id); refresh() }
        dialog.show()
    }

    /** Pick extra apps that still work at night. Phone, Clock and Status Guard are always allowed. */
    private fun chooseApps() {
        val always = store.basicApps().toSet() + packageName
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it !in always }
            .map { it to PhoneUsage.appName(this, it) }
            .sortedBy { it.second.lowercase() }
        val chosen = store.extraApps.toMutableSet()
        val checked = apps.map { it.first in chosen }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Apps you can still use at night")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on ->
                if (on) chosen += apps[i].first else chosen -= apps[i].first
            }
            .setPositiveButton("Save") { _, _ ->
                store.extraApps = chosen
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
