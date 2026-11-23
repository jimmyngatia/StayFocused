package com.personal.statusguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.time.LocalDate

/** Covers TikTok, Instagram or YouTube Shorts when they aren't allowed. Force open is the only way in. */
class SocialBlockActivity : Activity() {
    private var app = SocialApp.TIKTOK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_social_block)
        findViewById<Button>(R.id.okButton).setOnClickListener { goHome() }
        findViewById<Button>(R.id.forceButton).setOnClickListener {
            it.visibility = View.GONE
            findViewById<View>(R.id.forceBox).visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.forceConfirmButton).setOnClickListener { forceOpen() }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goHome() }
        }
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        app = SocialApp.byKey(intent.getStringExtra(EXTRA_APP)) ?: SocialApp.TIKTOK
        val forceEnded = intent.getBooleanExtra(EXTRA_FORCE_ENDED, false)
        val prefs = SocialPrefs(this)
        val history = History(this)
        val today = LocalDate.now()

        findViewById<android.widget.ImageView>(R.id.appIcon).setImageResource(app.icon)
        text(R.id.titleText, if (forceEnded) "Your 5 minutes are up" else "${app.label} is closed for now")
        text(R.id.reasonText, intent.getStringExtra(EXTRA_REASON) ?: "")
        val (quote, source) = Encouragements.pick(this)
        text(R.id.quoteText, "“$quote”\n— $source")

        val tries = history.get(today, app.triesField)
        val usedMin = prefs.usedSecondsToday() / 60
        text(
            R.id.statsText,
            (if (forceEnded) "" else "This is your ${ordinal(maxOf(1, tries))} ${app.label} try today.\n") +
                "Entertainment today: $usedMin of ${prefs.limitMinutes} min"
        )
        text(R.id.streakText, "Streak: ${history.overallStreak()} days with every rule kept")

        // What a force open would cost, streak by streak.
        val social = history.socialStreak()
        val status = history.statusStreak()
        val sleep = history.cleanStreak(History.SLEEP_ATTEMPTS)
        val mornings = history.alarmStreak
        val overall = history.overallStreak()
        val forcesToday = SocialApp.entries.sumOf { history.get(today, it.forceField) }
        text(
            R.id.costText,
            "Every streak is cut in half:\n" +
                "Streak $overall → 0 (a force open is a slip)\n" +
                "Status $status → ${status / 2}  ·  Social $social → ${social / 2}\n" +
                "Sleep $sleep → ${sleep / 2}  ·  Mornings $mornings → ${mornings / 2}\n\n" +
                "It opens ${app.label} for ${SocialPrefs.FORCE_MINUTES} minutes only, and it shows in your weekly report." +
                if (forcesToday > 0) "\nYou've already force-opened $forcesToday times today." else ""
        )
        findViewById<Button>(R.id.forceConfirmButton).text = "Force open ${app.label} for ${SocialPrefs.FORCE_MINUTES} min"
        findViewById<View>(R.id.forceButton).visibility = View.VISIBLE
        findViewById<View>(R.id.forceBox).visibility = View.GONE
    }

    private fun forceOpen() {
        SocialPrefs(this).forceOpen(app)
        Toast.makeText(this, "Streaks halved. ${app.label} is open for ${SocialPrefs.FORCE_MINUTES} minutes.", Toast.LENGTH_LONG).show()
        app.packages.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
            ?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    @Deprecated("Used on Android 12 only")
    override fun onBackPressed() = goHome()

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).text = value
    }

    companion object {
        private const val EXTRA_APP = "app"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_FORCE_ENDED = "force_ended"

        fun intent(context: Context, app: SocialApp, reason: String, forceEnded: Boolean) =
            Intent(context, SocialBlockActivity::class.java)
                .putExtra(EXTRA_APP, app.key)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_FORCE_ENDED, forceEnded)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
