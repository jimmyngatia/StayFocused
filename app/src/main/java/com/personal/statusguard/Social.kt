package com.personal.statusguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.time.LocalDate
import java.time.LocalTime

/**
 * An entertainment app that shares the daily pot of minutes: TikTok, YouTube Shorts and Instagram
 * are built in; any other app you add ([custom]) is treated the same way (whole app).
 * A custom app's key is its package name.
 */
data class SocialApp(val key: String, val label: String, val icon: Int, val packages: Set<String>, val custom: Boolean = false) {

    /** History fields for this app: seconds watched, blocked tries, force opens. */
    val secondsField get() = "social_${key}_s"
    val triesField get() = "social_${key}_tries"
    val forceField get() = "social_${key}_force"

    companion object {
        val TIKTOK = SocialApp("tiktok", "TikTok", R.drawable.ic_music, setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go", "com.tiktok.lite.go"))
        val SHORTS = SocialApp("shorts", "YouTube Shorts", R.drawable.ic_play, setOf("com.google.android.youtube"))
        val INSTAGRAM = SocialApp("instagram", "Instagram", R.drawable.ic_camera, setOf("com.instagram.android", "com.instagram.lite"))
        val BUILT_IN = listOf(TIKTOK, SHORTS, INSTAGRAM)

        /** Packages that can't be added as custom apps (already built in). */
        val BUILT_IN_PACKAGES = BUILT_IN.flatMap { it.packages }.toSet()

        /** Built-in apps plus the ones you added. Cached: it's looked up on every screen change. */
        @Volatile private var cache: List<SocialApp>? = null

        fun all(context: Context): List<SocialApp> = cache ?: (BUILT_IN + SocialPrefs(context).customPackages
            .filter { it !in BUILT_IN_PACKAGES }
            .map { SocialApp(it, PhoneUsage.appName(context, it), R.drawable.ic_phone, setOf(it), custom = true) }
            .sortedBy { it.label.lowercase() }).also { cache = it }

        /** Called after the list of added apps changes. */
        fun refresh() {
            cache = null
        }

        fun of(context: Context, pkg: String) = all(context).firstOrNull { pkg in it.packages }
        fun byKey(context: Context, key: String?) = all(context).firstOrNull { it.key == key }
    }
}

/**
 * Social media rules: one shared daily limit ("entertainment time") and the times it's allowed.
 * Outside them the apps are closed, except with a force open (5 minutes, resets the social streak).
 */
class SocialPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val sp = context.getSharedPreferences("social", Context.MODE_PRIVATE)
    private val history = History(context)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Which of the built-in apps are guarded (keys). All three by default. */
    var builtInKeys: Set<String>
        get() = sp.getStringSet("apps", SocialApp.BUILT_IN.map { it.key }.toSet())!!
        set(v) = sp.edit().putStringSet("apps", v).apply()

    /** Other apps you added (package names). Always guarded while social media blocking is on. */
    var customPackages: Set<String>
        get() = sp.getStringSet("custom_apps", emptySet())!!
        set(v) {
            sp.edit().putStringSet("custom_apps", v).apply()
            SocialApp.refresh()
        }

    /** The shared daily pot, in minutes. */
    var limitMinutes: Int
        get() = sp.getInt("limit", 30)
        set(v) = sp.edit().putInt("limit", v).apply()

    var timeWindows: String
        get() = sp.getString("windows", "19:00-21:00")!!
        set(v) = sp.edit().putString("windows", v).apply()

    fun isGuarded(app: SocialApp) = enabled && (app.custom || app.key in builtInKeys)

    /** The apps being guarded right now. */
    fun guardedApps(context: Context) = SocialApp.all(context).filter { isGuarded(it) }

    /** Seconds watched today, all apps together. */
    fun usedSecondsToday(date: LocalDate = LocalDate.now()) = SocialApp.all(appContext).sumOf { history.get(date, it.secondsField) }

    fun addSeconds(app: SocialApp, seconds: Long) = history.add(app.secondsField, seconds)

    // ---- Force open ----

    fun forceUntil(app: SocialApp) = sp.getLong("force_${app.key}", 0)

    fun isForceOpen(app: SocialApp) = System.currentTimeMillis() < forceUntil(app)

    /** Opens [app] for 5 minutes, counts it, and resets the social streak (and today's general streak). */
    fun forceOpen(app: SocialApp) {
        sp.edit().putLong("force_${app.key}", System.currentTimeMillis() + FORCE_MINUTES * 60_000L).apply()
        history.add(app.forceField)
        history.resetSocialStreak()
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
 * TikTok / Instagram / YouTube Shorts (and the apps you added) when they aren't allowed, and closes force opens after 5 minutes.
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

    /**
     * Looks again a little later. A slow phone can take seconds to draw an app's first screen, and
     * until then the screen can't be read (and an app stuck on its splash screen sends nothing more).
     */
    private val retries = listOf(1000L, 3000L, 6000L).map { delay -> delay to Runnable { startTicking() } }

    private fun startTicking() {
        if (ticking) return
        ticking = check(countSecond = false)
        if (ticking) handler.postDelayed(tick, 1000)
    }

    /** Called for every accessibility event. Returns true if it was a social app (handled here). */
    fun onEvent(pkg: String, type: Int): Boolean {
        val app = SocialApp.of(service, pkg) ?: return false
        if (!prefs.isGuarded(app)) return true
        // A whole app (not Shorts) that isn't allowed now: cover it as soon as it opens, without
        // waiting to read its screen (on a slow phone that can take a long time).
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && app != SocialApp.SHORTS && !covering &&
            !SleepStore(service).isBlockedNow(pkg)
        ) {
            prefs.blockReason(app)?.let { reason ->
                block(app, pkg, reason)
                return true
            }
        }
        if (!ticking) {
            startTicking()
            if (!ticking) for ((delay, run) in retries) {
                handler.removeCallbacks(run)
                handler.postDelayed(run, delay)
            }
        }
        return true
    }

    /** Looks at the screen and acts if needed. Returns true while a guarded app is still in front. */
    private fun check(countSecond: Boolean): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        val app = SocialApp.of(service, pkg) ?: return false
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
        val onShorts = root != null && root.packageName?.toString() in SocialApp.SHORTS.packages && ShortsDetector.isOnShorts(root)
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
