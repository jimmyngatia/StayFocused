package com.personal.statusguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.time.LocalDate
import java.time.LocalTime

/** The entertainment apps that share one daily pot of minutes. */
enum class SocialApp(val key: String, val label: String, val icon: Int, val packages: Set<String>) {
    TIKTOK("tiktok", "TikTok", R.drawable.ic_music, setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go", "com.tiktok.lite.go")),
    SHORTS("shorts", "YouTube Shorts", R.drawable.ic_play, setOf("com.google.android.youtube")),
    INSTAGRAM("instagram", "Instagram", R.drawable.ic_camera, setOf("com.instagram.android", "com.instagram.lite"));

    /** History fields for this app: seconds watched, blocked tries, force opens. */
    val secondsField get() = "social_${key}_s"
    val triesField get() = "social_${key}_tries"
    val forceField get() = "social_${key}_force"

    companion object {
        fun of(pkg: String) = entries.firstOrNull { pkg in it.packages }
        fun byKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * Social media rules: one shared daily limit ("entertainment time") and the times it's allowed.
 * Outside them the apps are closed, except with a force open (5 minutes, halves every streak).
 */
class SocialPrefs(context: Context) {
    private val sp = context.getSharedPreferences("social", Context.MODE_PRIVATE)
    private val history = History(context)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Which of the apps are guarded. All three by default. */
    var apps: Set<SocialApp>
        get() = sp.getStringSet("apps", SocialApp.entries.map { it.key }.toSet())!!.mapNotNull { SocialApp.byKey(it) }.toSet()
        set(v) = sp.edit().putStringSet("apps", v.map { it.key }.toSet()).apply()

    /** The shared daily pot, in minutes. */
    var limitMinutes: Int
        get() = sp.getInt("limit", 30)
        set(v) = sp.edit().putInt("limit", v).apply()

    var timeWindows: String
        get() = sp.getString("windows", "19:00-21:00")!!
        set(v) = sp.edit().putString("windows", v).apply()

    fun isGuarded(app: SocialApp) = enabled && app in apps

    /** Seconds watched today, all apps together. */
    fun usedSecondsToday(date: LocalDate = LocalDate.now()) = SocialApp.entries.sumOf { history.get(date, it.secondsField) }

    fun addSeconds(app: SocialApp, seconds: Long) = history.add(app.secondsField, seconds)

    // ---- Force open ----

    fun forceUntil(app: SocialApp) = sp.getLong("force_${app.key}", 0)

    fun isForceOpen(app: SocialApp) = System.currentTimeMillis() < forceUntil(app)

    /** Opens [app] for 5 minutes, counts it, and halves every streak. */
    fun forceOpen(app: SocialApp) {
        sp.edit().putLong("force_${app.key}", System.currentTimeMillis() + FORCE_MINUTES * 60_000L).apply()
        history.add(app.forceField)
        history.halveAllStreaks()
    }

    // ---- Rules ----

    fun isInsideWindows(): Boolean {
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        return (Prefs.parseWindows(timeWindows) ?: emptyList()).any { now in it }
    }

    /** Null when [app] may be used right now, otherwise the reason it can't. */
    fun blockReason(app: SocialApp): String? {
        if (!isGuarded(app) || isForceOpen(app)) return null
        if (!isInsideWindows()) return "Entertainment apps are only allowed during $timeWindows."
        if (usedSecondsToday() >= limitMinutes * 60L) return "You've used all $limitMinutes minutes of entertainment time today."
        return null
    }

    companion object {
        const val FORCE_MINUTES = 5
    }
}

/** Works out whether the YouTube app is showing Shorts. Tuned for YouTube in English. */
object ShortsDetector {
    /** Screen parts that only exist in the Shorts player. */
    private val shortsIds = listOf(
        "reel_recycler", "reel_player_page_container", "reel_watch_player", "reel_watch_fragment_root",
        "reel_player_overlay_container", "reel_time_bar", "shorts_player_container",
    ).map { "com.google.android.youtube:id/$it" }

    fun isOnShorts(root: AccessibilityNodeInfo): Boolean {
        for (id in shortsIds) {
            if (root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }) return true
        }
        // Backup: the "Shorts" tab at the bottom is the selected one.
        return root.findAccessibilityNodeInfosByText("Shorts").any { node ->
            val words = (node.contentDescription ?: node.text)?.toString()
            words == "Shorts" && (node.isSelected || node.parent?.isSelected == true)
        }
    }
}

/**
 * The social media part of the accessibility service: counts watching time, covers
 * TikTok / Instagram / YouTube Shorts when they aren't allowed, and closes force opens after 5 minutes.
 * Kept separate from WhatsApp status so the two never get in each other's way.
 */
class SocialGuard(private val service: AccessibilityService, private val handler: Handler) {
    private val prefs = SocialPrefs(service)
    private val history = History(service)
    private val power = service.getSystemService(PowerManager::class.java)
    private var ticking = false
    private var covering = false
    private var lastAttempt = 0L
    private var lastLog: String? = null

    /** Once a second while a guarded app is open, so time is counted even if nothing moves. */
    private val tick = object : Runnable {
        override fun run() {
            ticking = check(countSecond = true)
            if (ticking) handler.postDelayed(this, 1000)
        }
    }

    /** Called for every accessibility event. Returns true if it was a social app (handled here). */
    fun onEvent(pkg: String): Boolean {
        val app = SocialApp.of(pkg) ?: return false
        if (!prefs.isGuarded(app)) return true
        if (!ticking) {
            ticking = check(countSecond = false)
            if (ticking) handler.postDelayed(tick, 1000)
        }
        return true
    }

    /** Looks at the screen and acts if needed. Returns true while a guarded app is still in front. */
    private fun check(countSecond: Boolean): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        val app = SocialApp.of(pkg) ?: return false
        if (!prefs.isGuarded(app)) return false
        if (SleepStore(service).isBlockedNow(pkg)) return false // the sleep cover deals with it

        val watching = app != SocialApp.SHORTS || ShortsDetector.isOnShorts(root)
        val state = "${app.key} watching=$watching"
        if (state != lastLog) {
            Log.d(StatusGuardService.TAG, "social: $state")
            lastLog = state
        }
        if (!watching || covering) return true

        val reason = prefs.blockReason(app)
        if (reason != null) {
            block(app, pkg, reason)
            return false
        }
        if (countSecond && power.isInteractive) prefs.addSeconds(app, 1)
        return true
    }

    private fun block(app: SocialApp, pkg: String, reason: String) {
        covering = true
        // A force open that just ran out isn't a new try.
        val forceJustEnded = System.currentTimeMillis() - prefs.forceUntil(app) in 0..10_000
        if (!forceJustEnded) countAttempt(app)
        if (app == SocialApp.SHORTS) leaveShorts(0) { cover(app, pkg, reason, forceJustEnded) }
        else cover(app, pkg, reason, forceJustEnded)
    }

    /** Presses Back until YouTube is off Shorts (so YouTube itself stays usable), then continues. */
    private fun leaveShorts(attempt: Int, then: () -> Unit) {
        val root = service.rootInActiveWindow
        val onShorts = root != null && SocialApp.of(root.packageName?.toString() ?: "") == SocialApp.SHORTS && ShortsDetector.isOnShorts(root)
        if (onShorts && attempt < 4) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            handler.postDelayed({ leaveShorts(attempt + 1, then) }, 350)
        } else then()
    }

    private fun cover(app: SocialApp, pkg: String, reason: String, forceEnded: Boolean) {
        service.startActivity(SocialBlockActivity.intent(service, app, reason, forceEnded))
        // Backup: if the cover didn't appear, leave the app.
        handler.postDelayed({
            val front = service.rootInActiveWindow
            val stillThere = front?.packageName?.toString() == pkg &&
                (app != SocialApp.SHORTS || ShortsDetector.isOnShorts(front))
            if (stillThere) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }, 800)
        handler.postDelayed({ covering = false }, 1500)
    }

    /** One honest count per try (events come in bursts, so tries within 3 seconds count once). */
    private fun countAttempt(app: SocialApp) {
        val now = System.currentTimeMillis()
        if (now - lastAttempt > 3000) {
            history.add(app.triesField)
            history.add("social_hour_${LocalTime.now().hour}")
        }
        lastAttempt = now
    }
}
