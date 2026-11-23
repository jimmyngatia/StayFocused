package com.personal.statusguard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalDateTime

/** Home: streaks, report, status, social media, strict alarms, sleep time and my words. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        button(R.id.enableButton) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button(R.id.runningButton) { open(AlarmRingActivity::class.java) }
        button(R.id.reportButton) { open(ReportActivity::class.java) }
        button(R.id.alarmsButton) { open(AlarmsActivity::class.java) }
        button(R.id.wordsButton) { open(MyWordsActivity::class.java) }
        // Settings need the password (free before one is set).
        button(R.id.settingsButton) { withPassword { open(SettingsActivity::class.java) } }
        button(R.id.sleepButton) { withPassword { open(SleepActivity::class.java) } }
        button(R.id.socialButton) { withPassword { open(SocialSettingsActivity::class.java) } }
        button(R.id.focusButton) { withPassword { open(FocusTimesActivity::class.java) } }
        button(R.id.devotionButton) { withPassword { open(DevotionActivity::class.java) } }
        button(R.id.devotionLogButton) { open(DevotionLogActivity::class.java) }
        button(R.id.passwordButton) { withPassword { open(PasswordActivity::class.java) } }
    }

    override fun onResume() {
        super.onResume()
        AlarmScheduler.scheduleAll(this)
        Routine.scheduleAll(this)
        val history = History(this)
        history.checkEscapedAlarm(this)
        AlarmRingActivity.reopenIfRunning(this)
        findViewById<View>(R.id.runningButton).visibility = if (AlarmService.phase != null) View.VISIBLE else View.GONE

        // Header: one of your whys, and today's intention.
        val words = Words(this)
        text(R.id.whyText, words.whys.randomOrNull()?.let { "“$it”" } ?: "Your time, reclaimed.")
        val intention = words.todaysIntention()
        findViewById<View>(R.id.intentionText).visibility = if (intention == null) View.GONE else View.VISIBLE
        text(R.id.intentionText, "Today: $intention")

        // Streak
        findViewById<FrameLayout>(R.id.streakBox).apply {
            removeAllViews()
            addView(streakBlock(this@MainActivity))
        }

        // Devotion
        val today = LocalDate.now()
        val devotion = DevotionStore(this)
        devotion.settleMissed()
        val devBlock = devotion.block
        text(
            R.id.devotionText,
            (if (devBlock == null || !devBlock.enabled) "No devotion time set yet." else "Devotion time ${devBlock.startText()}–${devBlock.endText()}  ·  ${devBlock.daysText()}") +
                "\n" + when {
                    devotion.isDone(today) -> "Today: ${devotion.entry(today)} ✓"
                    devotion.isMissed(today) -> "Today: not done"
                    devotion.current() != null -> {
                        val left = ((devBlock?.quickMinutes ?: 0) * 60 - devotion.usedSeconds(today)).coerceAtLeast(0)
                        "Devotion time now. Quick check left for other apps: ${formatSeconds(left.toLong())}"
                    }
                    else -> "Today: not logged yet"
                }
        )

        // Today
        val social = SocialPrefs(this)
        val socialApps = SocialApp.all(this)
        val socialTries = socialApps.sumOf { history.get(today, it.triesField) }
        val tries = history.get(today, History.STATUS_ATTEMPTS) + history.get(today, History.SLEEP_ATTEMPTS) +
            history.get(today, History.WA_FOCUS_TRIES) + history.get(today, History.DEV_TRIES) + socialTries
        val forces = socialApps.sumOf { history.get(today, it.forceField) } + history.get(today, History.WA_EXTRA) + history.get(today, History.DEV_EXTRA)
        val phone = PhoneUsage.days(this, today, today)[today]?.totalMs
        text(
            R.id.todayText, (phone?.let { "${PhoneUsage.format(it)} on phone  ·  " } ?: "") + "$tries blocked tries" +
                (if (forces > 0) "  ·  $forces force opens" else "")
        )

        // Status
        val on = isServiceEnabled()
        findViewById<TextView>(R.id.protectionText).status(on, if (on) "Protection is on" else "Protection is off")
        findViewById<View>(R.id.enableButton).visibility = if (on) View.GONE else View.VISIBLE
        val relaxed = if (prefs.isRelaxedToday()) "  ·  relaxed day" else ""
        text(R.id.usageText, "${prefs.usedSecondsToday() / 60} of ${prefs.todaysLimitMinutes()} min used$relaxed")
        findViewById<ProgressBar>(R.id.usageBar).apply {
            max = maxOf(1, prefs.todaysLimitMinutes() * 60)
            progress = prefs.usedSecondsToday()
        }
        val reason = prefs.blockReason()
        var now = if (reason == null) "Allowed right now." else "Blocked right now. $reason"
        if (prefs.isLockedOut()) {
            val minutesLeft = (prefs.lockedUntil - System.currentTimeMillis()) / 60_000 + 1
            now += "\nWhatsApp is locked for about $minutesLeft more min."
        }
        findViewById<TextView>(R.id.nowText).status(reason == null && !prefs.isLockedOut(), now)

        // WhatsApp focus times
        val focus = FocusStore(this)
        val focusNow = focus.current()
        text(
            R.id.focusText, when {
                focusNow != null -> {
                    val (b, day) = focusNow
                    val left = (b.quickMinutes * 60 - focus.usedSeconds(b, day)).coerceAtLeast(0)
                    "Focus time now: ${b.name} until ${b.endText()}\n" +
                        (if (focus.isExtraOpen()) "Opened anyway: about ${(focus.extraUntil - System.currentTimeMillis()) / 60_000 + 1} min left."
                        else if (left > 0) "Quick check left: ${formatSeconds(left.toLong())}"
                        else "Quick check used up. WhatsApp is closed.")
                }
                focus.blocks().any { it.enabled } -> "Focus times: " + focus.blocks().filter { it.enabled }
                    .joinToString(", ") { "${it.name} ${it.startText()}–${it.endText()}" }
                else -> "Focus times: none yet. Close WhatsApp at set times, like your morning devotion."
            }
        )

        // Social media
        val used = social.usedSecondsToday()
        text(
            R.id.socialUsageText,
            if (social.enabled) "${used / 60} of ${social.limitMinutes} min used" else "Off. Open the settings to set it up."
        )
        findViewById<ProgressBar>(R.id.socialBar).apply {
            visibility = if (social.enabled) View.VISIBLE else View.GONE
            max = maxOf(1, social.limitMinutes * 60)
            progress = used.toInt()
        }
        val forcedApp = socialApps.firstOrNull { social.isForceOpen(it) }
        val socialReason = social.guardedApps(this).firstNotNullOfOrNull { social.blockReason(it) }
        findViewById<TextView>(R.id.socialNowText).apply {
            when {
                !social.enabled -> {
                    text = "TikTok, YouTube Shorts, Instagram and any apps you add share one daily limit and allowed times."
                    icon(0)
                }
                forcedApp != null -> {
                    text = "${forcedApp.label} is force-opened for about ${(social.forceUntil(forcedApp) - System.currentTimeMillis()) / 60_000 + 1} more min."
                    icon(R.drawable.ic_warning, R.color.warning)
                }
                else -> status(socialReason == null, if (socialReason == null) "Allowed right now (${social.timeWindows})." else "Blocked right now. $socialReason")
            }
        }
        text(
            R.id.socialAppsText,
            socialApps.filter { social.isGuarded(it) || history.get(today, it.secondsField) > 0 }.joinToString("\n") { app ->
                "${app.label}: ${formatSeconds(history.get(today, app.secondsField))}  ·  ${history.get(today, app.triesField)} tries" +
                    history.get(today, app.forceField).let { if (it > 0) "  ·  $it force opens" else "" }
            }
        )

        // Strict alarms
        text(R.id.alarmText, Routine.nextAlarmText(this))

        // Sleep time
        val sleep = SleepStore(this)
        val current = sleep.current()
        val next = sleep.schedules().mapNotNull { s -> s.nextBedtime(LocalDateTime.now())?.let { s to it } }.minByOrNull { it.second }
        text(
            R.id.sleepText, when {
                current != null -> "Sleeping now, until ${current.wakeText()}"
                next != null -> "Next: ${next.first.bedText()} → ${next.first.wakeText()}"
                else -> "No sleep schedule yet."
            } + sleep.sleptTonightMs().let { if (it >= 60_000) "\nLast night: ${PhoneUsage.format(it)} asleep" else "" }
        )

        // My words
        text(R.id.wordsText, "${words.whys.size} whys  ·  ${words.quotes.size} quotes")

        // Password
        val hasPassword = prefs.hasPassword()
        findViewById<TextView>(R.id.passwordText).apply {
            if (hasPassword) status(true, "Password set. It protects every setting." + if (prefs.name.isBlank()) " Add your name for partner emails." else "")
            else {
                text = "Not set yet. Anyone can change your rules."
                icon(R.drawable.ic_warning, R.color.warning)
            }
        }
        findViewById<Button>(R.id.passwordButton).text = if (hasPassword) "Name & password" else "Set name & password"
    }

    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).text = value
    }

    private fun button(id: Int, action: () -> Unit) = findViewById<Button>(id).setOnClickListener { action() }

    private fun open(screen: Class<*>) = startActivity(Intent(this, screen))

    private fun withPassword(action: () -> Unit) =
        if (prefs.hasPassword()) askPassword(this, "Enter password") { action() } else action()

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val mine = ComponentName(this, StatusGuardService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }
}
