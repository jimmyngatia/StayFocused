package com.personal.statusguard

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Gmail sender, recipients and the weekly day/time. Only reachable with the password. */
class EmailSettingsActivity : Activity() {
    private lateinit var settings: EmailSettings
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_settings)
        settings = EmailSettings(this)

        field(R.id.sender).setText(settings.sender)
        field(R.id.appPassword).apply {
            hint = if (settings.appPassword.isEmpty()) "16-letter code" else "Saved. Leave blank to keep it"
        }
        field(R.id.recipients).setText(settings.recipients)
        findViewById<Spinner>(R.id.day).apply {
            adapter = ArrayAdapter(
                this@EmailSettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                (1..7).map { DayOfWeek.of(it).getDisplayName(TextStyle.FULL, Locale.getDefault()) }
            )
            setSelection(settings.day - 1)
        }
        findViewById<TimePicker>(R.id.time).apply {
            setIs24HourView(true)
            hour = settings.minute / 60
            minute = settings.minute % 60
        }
        findViewById<CheckBox>(R.id.enabled).isChecked = settings.enabled
        findViewById<CheckBox>(R.id.wakeEmails).isChecked = settings.wakeEmails
        findViewById<TextView>(R.id.lastResult).text = settings.lastResult.ifEmpty { "Nothing sent yet." }

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            if (save()) {
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        findViewById<Button>(R.id.testButton).setOnClickListener { if (save()) sendTest() }
    }

    /** Leaving closes this screen, so it can't be reopened from recent apps without the password. */
    override fun onStop() {
        super.onStop()
        if (!sending) finish()
    }

    private fun save(): Boolean {
        val sender = field(R.id.sender).text.toString().trim()
        val recipients = field(R.id.recipients).text.toString().trim()
        val password = field(R.id.appPassword).text.toString().replace(" ", "")
        val problem = when {
            !sender.contains("@") -> "Enter the Gmail address that sends the report."
            settings.appPassword.isEmpty() && password.isEmpty() -> "Enter the 16-letter app password."
            password.isNotEmpty() && password.length != 16 -> "The app password has 16 letters."
            recipients.split(",").none { it.contains("@") } -> "Enter at least one email address to send to."
            else -> null
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return false
        }
        val time = findViewById<TimePicker>(R.id.time)
        settings.sender = sender
        if (password.isNotEmpty()) settings.appPassword = password
        settings.recipients = recipients
        settings.day = findViewById<Spinner>(R.id.day).selectedItemPosition + 1
        settings.minute = time.hour * 60 + time.minute
        settings.enabled = findViewById<CheckBox>(R.id.enabled).isChecked
        settings.wakeEmails = findViewById<CheckBox>(R.id.wakeEmails).isChecked
        Routine.scheduleAll(this)
        return true
    }

    private fun sendTest() {
        sending = true
        val status = findViewById<TextView>(R.id.lastResult)
        status.text = "Sending…"
        Thread {
            val result = try {
                EmailReport.sendNow(this)
                "Test sent ✓ Check the recipients' inboxes (and spam)."
            } catch (e: Exception) {
                "Failed: ${e.message}\n\nCheck the Gmail address, the app password and your internet."
            }
            runOnUiThread {
                sending = false
                status.text = result
            }
        }.start()
    }

    private fun field(id: Int) = findViewById<EditText>(id)
}
