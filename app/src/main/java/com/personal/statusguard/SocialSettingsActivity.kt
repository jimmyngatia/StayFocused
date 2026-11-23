package com.personal.statusguard

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast

/** Social media rules. Only reachable with the password (or before one is set). */
class SocialSettingsActivity : Activity() {
    private lateinit var prefs: SocialPrefs
    private val appBoxes by lazy {
        mapOf(
            SocialApp.TIKTOK to findViewById<CheckBox>(R.id.appTiktok),
            SocialApp.SHORTS to findViewById<CheckBox>(R.id.appShorts),
            SocialApp.INSTAGRAM to findViewById<CheckBox>(R.id.appInstagram),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_social_settings)
        prefs = SocialPrefs(this)

        findViewById<CheckBox>(R.id.enabled).isChecked = prefs.enabled
        appBoxes.forEach { (app, box) -> box.isChecked = app in prefs.apps }
        field(R.id.limit).setText(prefs.limitMinutes.toString())
        field(R.id.windows).setText(prefs.timeWindows)
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
    }

    /** Leaving closes this screen, so it can't be reopened from recent apps without the password. */
    override fun onStop() {
        super.onStop()
        finish()
    }

    private fun save() {
        val limit = field(R.id.limit).text.toString().toIntOrNull()
        val windows = field(R.id.windows).text.toString().trim()
        val apps = appBoxes.filterValues { it.isChecked }.keys
        val problem = when {
            limit == null || limit !in 0..1440 -> "The daily limit must be a number of minutes."
            Prefs.parseWindows(windows) == null -> "Write allowed times like 12:30-13:00, 19:00-21:00"
            apps.isEmpty() -> "Choose at least one app."
            else -> null
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }
        prefs.limitMinutes = limit!!
        prefs.timeWindows = windows
        prefs.apps = apps
        prefs.enabled = findViewById<CheckBox>(R.id.enabled).isChecked
        History(this).socialStreak() // starts the social streak from today
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun field(id: Int) = findViewById<EditText>(id)
}
