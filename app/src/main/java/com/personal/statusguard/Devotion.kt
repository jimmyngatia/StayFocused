package com.personal.statusguard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Devotion time, e.g. 07:00 → 10:00. During it the apps you choose (Bible, notes...) stay free;
 * every other app shares a short quick check, then closes. After it ends you're asked what you read
 * until you answer. Each day's answer goes into the weekly report and the devotion streak.
 */
class DevotionStore(private val context: Context) {
    private val sp = context.getSharedPreferences("devotion", Context.MODE_PRIVATE)
    private val history = History(context)

    /** The devotion time (a [FocusBlock] named "Devotion time"), or null if not set up. */
    var block: FocusBlock?
        get() {
            val o = JSONObject(sp.getString("block", null) ?: return null)
            val days = o.getJSONArray("days")
            return FocusBlock(1, "Devotion time", o.getInt("start"), o.getInt("end"),
                (0 until days.length()).map { days.getInt(it) }.toSet(), o.getInt("quick"), o.getBoolean("enabled"))
        }
        set(b) {
            val edit = sp.edit()
            if (b == null) edit.remove("block") else edit.putString(
                "block", JSONObject().put("start", b.start).put("end", b.end).put("days", JSONArray(b.days.sorted()))
                    .put("quick", b.quickMinutes).put("enabled", b.enabled).toString()
            )
            // The streak and missed days count from the day it was first set up.
            if (!sp.contains("since")) edit.putString("since", LocalDate.now().toString())
            edit.apply()
        }

    val since: LocalDate? get() = sp.getString("since", null)?.let { LocalDate.parse(it) }

    /** Apps that stay free during devotion time (package names). Phone and Clock always do. */
    var apps: Set<String>
        get() = sp.getStringSet("apps", emptySet())!!
        set(v) = sp.edit().putStringSet("apps", v).apply()

    /** Today's date if devotion time is happening now. */
    fun current(now: LocalDateTime = LocalDateTime.now()) = block?.startedOn(now)

    fun allowedPackages(): Set<String> {
        val sleep = SleepStore(context)
        val keyboard = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName }
        val home = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        return apps + sleep.basicApps() + context.packageName + SleepStore.SYSTEM_PACKAGES + home + listOfNotNull(keyboard)
    }

    // ---- The quick check and "Open anyway" ----

    fun usedSeconds(day: LocalDate) = sp.getInt("used_$day", 0)

    fun addUsed(day: LocalDate, seconds: Int) = sp.edit().putInt("used_$day", usedSeconds(day) + seconds).apply()

    fun extras(day: LocalDate) = sp.getInt("extras_$day", 0)

    /** Same "Open anyway" settings as WhatsApp focus times: a few minutes, after a wait that grows. */
    fun nextWaitSeconds(day: LocalDate) = FocusStore(context).waitSeconds * (extras(day) + 1)

    val extraMinutes get() = FocusStore(context).extraMinutes

    val extraUntil get() = sp.getLong("extra_until", 0)

    fun isExtraOpen() = System.currentTimeMillis() < extraUntil

    /** "Open anyway": a slip (breaks today's general streak). */
    fun openAnyway(day: LocalDate) {
        sp.edit()
            .putLong("extra_until", System.currentTimeMillis() + extraMinutes * 60_000L)
            .putInt("extras_$day", extras(day) + 1)
            .apply()
        history.add(History.DEV_EXTRA)
    }

    // ---- The devotion log ----

    fun isScheduled(date: LocalDate): Boolean {
        val b = block ?: return false
        val from = since ?: return false
        return b.enabled && date.dayOfWeek.value in b.days && !date.isBefore(from)
    }

    fun isDone(date: LocalDate) = history.get(date, History.DEV_DONE) > 0
    fun isMissed(date: LocalDate) = history.get(date, History.DEV_MISSED) > 0
    fun entry(date: LocalDate) = history.text(date, History.DEV_TEXT)

    /** "I read Deuteronomy 2-3." Replaces an earlier answer from the same day, even an honest "No". */
    fun logDone(date: LocalDate, text: String) {
        history.set(date, History.DEV_DONE, 1)
        history.set(date, History.DEV_MISSED, 0)
        history.setText(date, History.DEV_TEXT, text)
    }

    /** "I didn't today." Honest, but it resets the devotion streak and breaks the general streak. */
    fun logNotDone(date: LocalDate) {
        history.set(date, History.DEV_DONE, 0)
        history.set(date, History.DEV_MISSED, 1)
        history.setText(date, History.DEV_TEXT, null)
    }

    /** Devotion time ended today and there's no answer yet. */
    fun needsAnswer(now: LocalDateTime = LocalDateTime.now()): Boolean {
        val b = block ?: return false
        val today = now.toLocalDate()
        if (!isScheduled(today) || isDone(today) || isMissed(today)) return false
        return now.hour * 60 + now.minute >= b.end
    }

    /** Past devotion days with no answer by midnight count as missed (looked at up to 2 weeks back). */
    fun settleMissed() {
        val from = since ?: return
        var d = LocalDate.now().minusDays(1)
        val stop = maxOf(from, LocalDate.now().minusDays(14))
        while (!d.isBefore(stop)) {
            if (isScheduled(d) && !isDone(d) && !isMissed(d)) history.set(d, History.DEV_MISSED, 1)
            d = d.minusDays(1)
        }
    }

    /**
     * Devotion streak: days in a row with devotions logged. Days without devotion time don't break it
     * (and count if you logged one anyway); today only counts once it's logged.
     */
    fun streak(): Int {
        val from = since ?: return 0
        var days = 0
        var d = LocalDate.now()
        while (!d.isBefore(from)) {
            if (isDone(d)) days++
            else if (isMissed(d) || (d != LocalDate.now() && isScheduled(d))) break
            d = d.minusDays(1)
        }
        return days
    }

    /** When the log screen last popped up by itself (it does at most every 30 minutes). */
    var promptedAt: Long
        get() = sp.getLong("prompted_at", 0)
        set(v) = sp.edit().putLong("prompted_at", v).apply()

    companion object {
        const val NOTIFICATION_ID = 20
        private const val CHANNEL = "devotion"

        /** The "Did you do your devotions?" notification. It stays until you answer. */
        fun notifyAsk(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Devotion log", NotificationManager.IMPORTANCE_DEFAULT))
            val tap = PendingIntent.getActivity(
                context, NOTIFICATION_ID, Intent(context, DevotionLogActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Did you do your devotions today?")
                    .setContentText("Tap to write what you read, e.g. Deuteronomy 2-3.")
                    .setContentIntent(tap)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            )
        }

        fun cancelAsk(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

/**
 * The devotion part of the accessibility service.
 * - During devotion time: covers any app that isn't free once the quick check is used up.
 * - After it: keeps asking (notification, and the log screen on unlock, at most every 30 minutes).
 */
class DevotionGuard(private val service: AccessibilityService, private val handler: Handler) {
    private val store = DevotionStore(service)
    private val history = History(service)
    private val power = service.getSystemService(PowerManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private var ticking = false
    private var covering = false
    private var lastTry = 0L
    private var lastLook = 0L

    /** Looked up once a minute: reading them on every event would be slow. */
    private var active: LocalDate? = null
    private var allowed = emptySet<String>()
    private var refreshedAt = 0L

    private fun refresh(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - refreshedAt < 60_000) return
        refreshedAt = now
        active = store.current()
        if (active != null) allowed = store.allowedPackages()
    }

    /** Once a second while another app is open during devotion time. */
    private val tick = object : Runnable {
        override fun run() {
            ticking = check(countSecond = true)
            if (ticking) handler.postDelayed(this, 1000)
        }
    }

    /** Once a minute: devotion time starting or ending, the reminder, and missed days. */
    private val minute = object : Runnable {
        override fun run() {
            refresh(force = true)
            store.settleMissed()
            ask(openScreen = power.isInteractive && !keyguard.isKeyguardLocked)
            handler.postDelayed(this, 60_000)
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.postDelayed({ ask(openScreen = true) }, 800)
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else service.registerReceiver(unlockReceiver, filter)
        handler.post(minute)
    }

    fun stop() {
        runCatching { service.unregisterReceiver(unlockReceiver) }
    }

    /** Called for every event. Returns true if the app in front was covered. */
    fun onEvent(): Boolean {
        refresh()
        if (active == null) return false
        if (!ticking) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLook < 500) return covering
            lastLook = now
            ticking = check(countSecond = false)
            if (ticking) handler.postDelayed(tick, 1000)
        }
        return covering
    }

    /** Returns true while an app that isn't free is in front and still allowed (quick check or open anyway). */
    private fun check(countSecond: Boolean): Boolean {
        val day = store.current() ?: return false
        val pkg = service.rootInActiveWindow?.packageName?.toString() ?: return false
        if (pkg in allowed) return false
        if (SleepStore(service).isBlockedNow(pkg) || AlarmService.phase != null) return false
        if (covering || callActive()) return true
        if (store.isExtraOpen()) return true
        if (store.usedSeconds(day) < (store.block?.quickMinutes ?: 0) * 60) {
            if (countSecond && power.isInteractive) {
                store.addUsed(day, 1)
                history.add(History.DEV_SECONDS)
            }
            return true
        }
        cover(pkg)
        return false
    }

    private fun callActive(): Boolean {
        val mode = audio.mode
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun cover(pkg: String) {
        covering = true
        val extraEnded = System.currentTimeMillis() - store.extraUntil in 0..10_000
        if (!extraEnded) countTry()
        Log.d(StatusGuardService.TAG, "devotion: covering $pkg extraEnded=$extraEnded")
        service.startActivity(DevotionBlockActivity.intent(service, pkg, extraEnded))
        handler.postDelayed({
            if (service.rootInActiveWindow?.packageName?.toString() == pkg) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }, 800)
        handler.postDelayed({ covering = false }, 1500)
    }

    private fun countTry() {
        val now = System.currentTimeMillis()
        if (now - lastTry > 3000) history.addAttempt(History.DEV_TRIES)
        lastTry = now
    }

    /** After devotion time: the notification stays until you answer; the log screen pops up every 30 minutes you use the phone. */
    private fun ask(openScreen: Boolean) {
        if (!store.needsAnswer()) {
            DevotionStore.cancelAsk(service)
            return
        }
        DevotionStore.notifyAsk(service)
        val busy = AlarmService.phase != null || SleepStore(service).isEnforcing() || callActive()
        val now = System.currentTimeMillis()
        if (openScreen && !busy && !DevotionLogActivity.isOpen && now - store.promptedAt > 30 * 60_000L) {
            store.promptedAt = now
            service.startActivity(Intent(service, DevotionLogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
