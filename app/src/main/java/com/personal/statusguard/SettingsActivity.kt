package com.personal.statusguard

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** WhatsApp status rules. Only reachable with the password (or before one is set). */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private val dayBoxes = mutableMapOf<Int, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        field(R.id.dailyLimit).setText(prefs.dailyLimitMinutes.toString())
        field(R.id.windows).setText(prefs.timeWindows)
        field(R.id.lockout).setText(prefs.lockoutMinutes.toString())
        field(R.id.pauseSeconds).setText(prefs.pauseSeconds.toString())
        field(R.id.relaxedLimit).setText(prefs.relaxedLimitMinutes.toString())

        val daysBox = findViewById<LinearLayout>(R.id.relaxedDays)
        for (day in 1..7) {
            val box = CheckBox(this).apply {
                text = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())
                isChecked = day in prefs.relaxedDays
            }
            dayBoxes[day] = box
            daysBox.addView(box)
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
    }

    /** Leaving this screen closes it, so it can't be reopened from recent apps without the password. */
    override fun onStop() {
        super.onStop()
        finish()
    }

    private fun save() {
        val daily = field(R.id.dailyLimit).text.toString().toIntOrNull()
        val windows = field(R.id.windows).text.toString().trim()
        val lockout = field(R.id.lockout).text.toString().toIntOrNull()
        val pause = field(R.id.pauseSeconds).text.toString().toIntOrNull()
        val relaxedLimit = field(R.id.relaxedLimit).text.toString().toIntOrNull()

        val problem = when {
            daily == null || daily !in 0..1440 -> "Daily limit must be a number of minutes."
            Prefs.parseWindows(windows) == null -> "Write allowed times like 19:00-21:00, 12:30-13:00"
            lockout == null || lockout !in 1..1440 -> "Lockout must be at least 1 minute."
            pause == null || pause !in 0..120 -> "Pause must be 0 to 120 seconds."
            relaxedLimit == null || relaxedLimit !in 0..1440 -> "Relaxed day limit must be a number of minutes."
            else -> null
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }

        prefs.dailyLimitMinutes = daily!!
        prefs.timeWindows = windows
        prefs.lockoutMinutes = lockout!!
        prefs.pauseSeconds = pause!!
        prefs.relaxedLimitMinutes = relaxedLimit!!
        prefs.relaxedDays = dayBoxes.filterValues { it.isChecked }.keys

        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun field(id: Int) = findViewById<EditText>(id)
}
