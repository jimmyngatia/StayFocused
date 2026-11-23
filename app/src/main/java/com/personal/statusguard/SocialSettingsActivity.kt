package com.personal.statusguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Social media rules. Only reachable with the password (or before one is set). */
class SocialSettingsActivity : Activity() {
    private lateinit var prefs: SocialPrefs
    private var custom = emptySet<String>()
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
        appBoxes.forEach { (app, box) -> box.isChecked = app.key in prefs.builtInKeys }
        custom = prefs.customPackages
        showCustom()
        findViewById<Button>(R.id.otherAppsButton).setOnClickListener { chooseApps() }
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
        val apps = appBoxes.filterValues { it.isChecked }.keys.map { it.key }.toSet()
        val problem = when {
            limit == null || limit !in 0..1440 -> "The daily limit must be a number of minutes."
            Prefs.parseWindows(windows) == null -> "Write allowed times like 12:30-13:00, 19:00-21:00"
            apps.isEmpty() && custom.isEmpty() -> "Choose at least one app."
            else -> null
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }
        prefs.limitMinutes = limit!!
        prefs.timeWindows = windows
        prefs.builtInKeys = apps
        prefs.customPackages = custom
        prefs.enabled = findViewById<CheckBox>(R.id.enabled).isChecked
        History(this).socialStreak() // starts the social streak from today
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showCustom() {
        val names = custom.map { PhoneUsage.appName(this, it) }.sortedBy { it.lowercase() }
        findViewById<TextView>(R.id.otherAppsText).text =
            if (names.isEmpty()) "None yet." else names.joinToString(", ") + "\n(whole app, same pot and times)"
    }

    /** Pick any other apps to treat like social media. WhatsApp, this app and the built-in ones aren't listed. */
    private fun chooseApps() {
        val skip = SocialApp.BUILT_IN_PACKAGES + StatusDetector.WHATSAPP_PACKAGES + packageName + "com.android.settings"
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it !in skip }
            .map { it to PhoneUsage.appName(this, it) }
            .sortedBy { it.second.lowercase() }
        val chosen = custom.toMutableSet()
        AlertDialog.Builder(this)
            .setTitle("Treat these like social media")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), apps.map { it.first in chosen }.toBooleanArray()) { _, i, on ->
                if (on) chosen += apps[i].first else chosen -= apps[i].first
            }
            .setPositiveButton("OK") { _, _ ->
                custom = chosen
                showCustom()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun field(id: Int) = findViewById<EditText>(id)
}
