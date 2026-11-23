package com.personal.statusguard

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalTime

/** "Tomorrow, I will…" — written at night, shown when the morning alarm is done. */
class IntentionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intention)
        val words = Words(this)

        // After noon you're planning tomorrow; in the early morning, today.
        val forDate = if (LocalTime.now().hour >= 12) LocalDate.now().plusDays(1) else LocalDate.now()
        val tomorrow = forDate != LocalDate.now()
        findViewById<TextView>(R.id.titleText).text = if (tomorrow) "Tomorrow, I will…" else "Today, I will…"
        findViewById<TextView>(R.id.alarmText).text = Routine.nextAlarmText(this)
        val input = findViewById<EditText>(R.id.input).apply { setText(words.intentionFor(forDate)) }

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            words.setIntention(input.text.toString().trim(), forDate)
            Toast.makeText(this, if (tomorrow) "Saved. Sleep well." else "Saved. Go get it!", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
