package com.personal.statusguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Devotion time settings. Only reachable with the password. */
class DevotionActivity : Activity() {
    private lateinit var store: DevotionStore
    private var apps = emptySet<String>()
    private lateinit var dayBoxes: Map<Int, CheckBox>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devotion)
        store = DevotionStore(this)
        val block = store.block

        findViewById<CheckBox>(R.id.enabled).isChecked = block?.enabled ?: true
        picker(R.id.start, block?.start ?: (7 * 60))
        picker(R.id.end, block?.end ?: (10 * 60))
        findViewById<EditText>(R.id.quick).setText((block?.quickMinutes ?: 10).toString())
        val daysList = findViewById<LinearLayout>(R.id.days)
        dayBoxes = (1..7).associateWith { day ->
            CheckBox(this).apply {
                text = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())
                isChecked = block == null || day in block.days
                daysList.addView(this)
            }
        }
        apps = store.apps
        showApps()
        findViewById<Button>(R.id.chooseAppsButton).setOnClickListener { chooseApps() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
    }

    /** Leaving this screen closes it, so it can't be reopened from recent apps without the password. */
    override fun onStop() {
        super.onStop()
        finish()
    }

    private fun picker(id: Int, minutes: Int) = findViewById<TimePicker>(id).apply {
        setIs24HourView(true)
        hour = minutes / 60
        minute = minutes % 60
    }

    private fun minutesOf(id: Int) = findViewById<TimePicker>(id).let { it.hour * 60 + it.minute }

    private fun showApps() {
        val basic = SleepStore(this).basicApps().map { PhoneUsage.appName(this, it) }
        val chosen = apps.map { PhoneUsage.appName(this, it) }.sortedBy { it.lowercase() }
        findViewById<TextView>(R.id.appsText).text =
            "Always: " + (basic + "calls").joinToString(", ") +
                "\nAlso: " + (if (chosen.isEmpty()) "none yet. Add your Bible app." else chosen.joinToString(", "))
    }

    private fun chooseApps() {
        val always = SleepStore(this).basicApps().toSet() + packageName
        val list = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it !in always }
            .map { it to PhoneUsage.appName(this, it) }
            .sortedBy { it.second.lowercase() }
        val chosen = apps.toMutableSet()
        AlertDialog.Builder(this)
            .setTitle("Apps that stay free during devotion time")
            .setMultiChoiceItems(list.map { it.second }.toTypedArray(), list.map { it.first in chosen }.toBooleanArray()) { _, i, on ->
                if (on) chosen += list[i].first else chosen -= list[i].first
            }
            .setPositiveButton("OK") { _, _ ->
                apps = chosen
                showApps()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        currentFocus?.clearFocus() // a time typed into a picker only counts once the picker loses focus
        val quick = findViewById<EditText>(R.id.quick).text.toString().toIntOrNull()
        val start = minutesOf(R.id.start)
        val end = minutesOf(R.id.end)
        val problem = when {
            quick == null || quick !in 0..600 -> "The quick check must be a number of minutes (0 for none)."
            end <= start -> "Devotion time must end after it starts, on the same day."
            else -> null
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }
        store.block = FocusBlock(1, "Devotion time", start, end, dayBoxes.filterValues { it.isChecked }.keys, quick!!,
            findViewById<CheckBox>(R.id.enabled).isChecked)
        store.apps = apps
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
