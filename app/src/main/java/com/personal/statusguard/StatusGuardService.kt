package com.personal.statusguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Watches which app is open.
 * - WhatsApp: counts status time, shows the breathing pause, and covers it with the lockout
 *   screen as soon as status isn't allowed.
 * - Sleep time: the night screen covers everything except the allowed apps (see [SleepGuard]).
 *   WhatsApp and WhatsApp Business calls still come through.
 * - Devotion time: the apps you choose stay free, the rest share a quick check; afterwards you're
 *   asked what you read (see [DevotionGuard]).
 * - WhatsApp focus times: a short quick check, then WhatsApp closes until the focus time ends (see [FocusGuard]).
 * - Social media: TikTok, Instagram, YouTube Shorts and the apps you add share one daily pot (see [SocialGuard]).
 * - A running alarm and sleep time: the power menu, notification bar and Settings are closed (see [EscapeGuard]).
 * Only app names and WhatsApp's screens are looked at; nothing else is read.
 */
class StatusGuardService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private lateinit var sleepGuard: SleepGuard
    private lateinit var history: History
    private lateinit var social: SocialGuard
    private lateinit var escape: EscapeGuard
    private lateinit var focus: FocusGuard
    private lateinit var devotion: DevotionGuard
    private lateinit var homeCard: HomeCard
    private val handler = Handler(Looper.getMainLooper())
    private var currentScreen: String? = null
    private var wasOnStatus = false
    private var ticking = false
    private var leaving = false // busy moving WhatsApp off the status page
    private var lastAttempt = 0L
    private var pauseAskedAt = 0L
    private var exitingUntil = 0L // busy taking WhatsApp back to Chats after "Never mind"

    /** Runs once a second while WhatsApp is open so watching time is counted even if nothing moves. */
    private val tick = object : Runnable {
        override fun run() {
            ticking = check(countSecond = true)
            if (ticking) handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        history = History(this)
        social = SocialGuard(this, handler)
        escape = EscapeGuard(this, handler)
        focus = FocusGuard(this, handler)
        devotion = DevotionGuard(this, handler).also { it.start() }
        homeCard = HomeCard(this, handler).also { it.start() }
        sleepGuard = SleepGuard(this, handler).also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // My day on the home screen (phones without widgets).
        homeCard.onEvent(event.eventType, pkg)
        // A running alarm or sleep time: the power menu, notification bar and Settings are closed first.
        if (escape.onEvent(event, pkg)) return
        val isWhatsApp = pkg in StatusDetector.WHATSAPP_PACKAGES

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val name = event.className?.toString()
            // Remember real WhatsApp screens only, not menus or pop-ups.
            // (WhatsApp Business screens are named com.whatsapp.* too.)
            if (isWhatsApp && name != null && name.startsWith("com.whatsapp")) currentScreen = name
            if (sleepGuard.check(pkg, currentScreen)) return
        }
        // Devotion time: apps that aren't free close once the quick check is used up.
        if (devotion.onEvent()) return
        // TikTok, Instagram and YouTube Shorts: handled separately in Social.kt.
        if (social.onEvent(pkg, event.eventType)) return
        if (!isWhatsApp) return
        // Focus times (e.g. morning devotion): WhatsApp closes once the quick check is used up.
        if (focus.onEvent(currentScreen)) return

        // During a lockout, any WhatsApp event means it was opened.
        if (prefs.isLockedOut()) {
            kickOut()
            return
        }
        if (check(countSecond = false) && !ticking) {
            ticking = true
            handler.postDelayed(tick, 1000)
        }
    }

    // ---- WhatsApp status ----

    /** Looks at the screen and acts if needed. Returns true while WhatsApp is still in front. */
    private fun check(countSecond: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() !in StatusDetector.WHATSAPP_PACKAGES) return false

        if (prefs.isLockedOut()) {
            kickOut()
            return false
        }

        val onStatus = StatusDetector.isStatusScreen(currentScreen) || StatusDetector.isOnUpdatesTab(root)
        if (onStatus != wasOnStatus) {
            Log.d(TAG, "screen=$currentScreen onStatus=$onStatus")
            wasOnStatus = onStatus
        }
        val now = System.currentTimeMillis()
        if (!onStatus) {
            exitStatusRequested = false // already off status
            return true
        }

        // "Never mind" on the pause screen: take WhatsApp back to Chats.
        if (exitStatusRequested) {
            exitStatusRequested = false
            exitingUntil = now + 3000
            leaveStatus(attempt = 0, cover = false)
            return true
        }
        // Still on the way back to Chats: WhatsApp can look like status for a moment. Don't ask again.
        if (now < exitingUntil) return true

        // The pass from "Continue" lasts while you're on status. Switching screens (e.g. tapping a
        // status) can look like "off status" for a split second, so it only ends after 5 seconds away.
        if (statusPass && now - lastOnStatusAt > PASS_GRACE_MS) statusPass = false
        lastOnStatusAt = now

        val reason = prefs.blockReason()
        if (reason != null) {
            prefs.startLockout(reason)
            kickOut()
            return false
        }

        // Allowed, but breathe first.
        if (prefs.pauseSeconds > 0 && !statusPass) {
            if (!PauseActivity.isOpen && now - pauseAskedAt > 2000) {
                pauseAskedAt = now
                startActivity(Intent(this, PauseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return true
        }

        if (countSecond) prefs.addUsedSeconds(1)
        return true
    }

    /**
     * Moves WhatsApp off the status page first (so it reopens on Chats later), then covers it
     * with the lockout screen.
     */
    private fun kickOut() {
        if (leaving) return
        countAttempt(History.STATUS_ATTEMPTS)
        leaving = true
        leaveStatus(attempt = 0, cover = true)
    }

    /** Closes the status viewer with Back, or switches from the Updates tab to Chats. Tries a few times. */
    private fun leaveStatus(attempt: Int, cover: Boolean) {
        val root = rootInActiveWindow
        val inWhatsApp = root?.packageName?.toString() in StatusDetector.WHATSAPP_PACKAGES
        val acted = when {
            root == null || !inWhatsApp || attempt >= 4 -> false
            StatusDetector.isStatusScreen(currentScreen) -> performGlobalAction(GLOBAL_ACTION_BACK)
            StatusDetector.isOnUpdatesTab(root) -> StatusDetector.openChatsTab(root) || performGlobalAction(GLOBAL_ACTION_BACK)
            else -> false
        }
        if (acted) {
            handler.postDelayed({ leaveStatus(attempt + 1, cover) }, 350)
            return
        }
        if (cover) {
            leaving = false
            startActivity(Intent(this, BlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            leaveIfStillOpen(null)
        }
    }

    // ---- Helpers ----

    /** Backup: if a cover screen didn't appear, leave the app by going home. */
    private fun leaveIfStillOpen(pkg: String?) {
        handler.postDelayed({
            val front = rootInActiveWindow?.packageName?.toString()
            val stillThere = if (pkg != null) front == pkg else front in StatusDetector.WHATSAPP_PACKAGES
            if (stillThere) performGlobalAction(GLOBAL_ACTION_HOME)
        }, 800)
    }

    /** One honest count per try (events come in bursts, so tries within 3 seconds count once). */
    private fun countAttempt(field: String) {
        val now = System.currentTimeMillis()
        if (now - lastAttempt > 3000) history.addAttempt(field)
        lastAttempt = now
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::sleepGuard.isInitialized) sleepGuard.stop()
        if (::devotion.isInitialized) devotion.stop()
        if (::homeCard.isInitialized) homeCard.stop()
        super.onDestroy()
    }

    companion object {
        const val TAG = "StatusGuard"

        /** Set by the pause screen's "Continue": status is open until you leave it for 5 seconds. */
        var statusPass = false
            private set

        /** Last time status was seen on screen (for ending the pass). */
        private var lastOnStatusAt = 0L
        private const val PASS_GRACE_MS = 5000L

        /** "Continue" on the pause screen. */
        fun grantStatusPass() {
            statusPass = true
            lastOnStatusAt = System.currentTimeMillis()
        }

        /** Set by the pause screen's "Never mind": take WhatsApp back to Chats. */
        var exitStatusRequested = false
    }
}
