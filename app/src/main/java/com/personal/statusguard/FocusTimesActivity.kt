package com.personal.statusguard

import android.app.Activity
import android.app.AlertDialog
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

/** WhatsApp focus times. Only reachable with the password. */
class FocusTimesActivity : Activity() {
    private lateinit var store: FocusStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_focus_times)
        store = FocusStore(this)
        findViewById<EditText>(R.id.extraMinutes).setText(store.extraMinutes.toString())
        findViewById<EditText>(R.id.waitSeconds).setText(store.waitSeconds.toString())
        findViewById<Button>(R.id.addBlockButton).setOnClickListener { edit(null) }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveSettings() }
        refresh()
    }

    /** Leaving this screen closes it, so it can't be reopened from recent apps without the password. */
    override fun onStop() {
        super.onStop()
        finish()
    }

    private fun refresh() {
        val list = findViewById<LinearLayout>(R.id.blockList)
        list.removeAllViews()
        val blocks = store.blocks()
        if (blocks.isEmpty()) list.addView(TextView(this).apply { text = "No focus times yet."; alpha = 0.7f })
        for (b in blocks) {
            list.addView(TextView(this).apply {
                text = "${b.name}\n${b.startText()} → ${b.endText()}  ·  ${b.quickMinutes} min quick check\n${b.daysText()}" +
                    if (b.enabled) "" else "  ·  off"
                textSize = 16f
                alpha = if (b.enabled) 1f else 0.5f
                background = getDrawable(R.drawable.card_background)
                setPadding(dp(Space.L), dp(Space.M), dp(Space.L), dp(Space.M))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(Space.S) }
                setOnClickListener { edit(b) }
            })
        }
    }

    private fun edit(existing: FocusBlock?) {
        val view = layoutInflater.inflate(R.layout.dialog_focus_block, null)
        val name = view.findViewById<EditText>(R.id.name).apply { setText(existing?.name ?: "") }
        val start = view.findViewById<TimePicker>(R.id.start).apply {
            setIs24HourView(true)
            hour = (existing?.start ?: (5 * 60 + 30)) / 60
            minute = (existing?.start ?: (5 * 60 + 30)) % 60
        }
        val end = view.findViewById<TimePicker>(R.id.end).apply {
            setIs24HourView(true)
            hour = (existing?.end ?: (7 * 60)) / 60
            minute = (existing?.end ?: (7 * 60)) % 60
        }
        val quick = view.findViewById<EditText>(R.id.quick).apply { setText((existing?.quickMinutes ?: 10).toString()) }
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
            .setPositiveButton("Save", null) // checked below, so a mistake doesn't close the dialog
            .setNegativeButton("Cancel", null)
        if (existing != null) dialog.setNeutralButton("Delete") { _, _ -> store.delete(existing.id); refresh() }
        val shown = dialog.show()
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            view.findFocus()?.clearFocus() // a time typed into a picker only counts once the picker loses focus
            val minutes = quick.text.toString().toIntOrNull()
            val from = start.hour * 60 + start.minute
            val to = end.hour * 60 + end.minute
            val problem = when {
                minutes == null || minutes !in 0..600 -> "The quick check must be a number of minutes (0 for none)."
                from == to -> "Start and end can't be the same time."
                else -> null
            }
            if (problem != null) {
                Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            store.save(
                FocusBlock(
                    id = existing?.id ?: store.newId(),
                    name = name.text.toString().trim().ifEmpty { "Focus time" },
                    start = from,
                    end = to,
                    days = dayBoxes.filterValues { it.isChecked }.keys,
                    quickMinutes = minutes!!,
                    enabled = enabled.isChecked,
                )
            )
            refresh()
            shown.dismiss()
        }
    }

    private fun saveSettings() {
        val extra = findViewById<EditText>(R.id.extraMinutes).text.toString().toIntOrNull()
        val wait = findViewById<EditText>(R.id.waitSeconds).text.toString().toIntOrNull()
        val problem = when {
            extra == null || extra !in 1..30 -> "Open anyway: 1 to 30 minutes."
            wait == null || wait !in 0..300 -> "The wait must be 0 to 300 seconds."
            else -> null
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }
        store.extraMinutes = extra!!
        store.waitSeconds = wait!!
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }
}
