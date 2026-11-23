package com.personal.statusguard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Closes the ways out of a running alarm and of sleep time.
 * - The power menu: closed straight away while an alarm is running and during sleep time, so
 *   "Power off" and "Restart" can't be tapped. (Holding power + volume down for about 10 seconds
 *   forces a restart; no app can stop that. It still counts as switched off.)
 * - While an alarm is running: the notification bar is closed as soon as it's pulled down
 *   (its "Active apps → Stop" would kill the alarm), and Android Settings sends you back to the alarm
 *   (that's where permissions are removed and apps force-stopped).
 * The lock screen and its PIN pad are never touched: unlocking is needed for the alarm's scan.
 */
class EscapeGuard(private val service: AccessibilityService, private val handler: Handler) {
    private val sleep = SleepStore(service)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private var lastLook = 0L
    private var lastAction = 0L

    /** Called for every accessibility event. Returns true if it closed something. */
    fun onEvent(event: AccessibilityEvent, pkg: String): Boolean {
        if (pkg != SYSTEM_UI && pkg !in SETTINGS) return false
        // The notification bar only sends "content changed" events here, and lots of them: look at most every 250 ms.
        val now = SystemClock.elapsedRealtime()
        if (pkg == SYSTEM_UI && event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && now - lastLook < 250) return false
        lastLook = now
        val alarm = alarmRunning()
        if (!alarm && !sleep.isEnforcing()) return false
        return look(alarm)
    }

    private fun alarmRunning() = AlarmService.phase != null

    /** Looks at the window in front and closes it if it's a way out. Returns true if it did. */
    private fun look(alarm: Boolean): Boolean {
        val front = service.rootInActiveWindow ?: return false
        val pkg = front.packageName?.toString()
        return when {
            pkg == SYSTEM_UI && isPowerMenu(front) -> act("power menu") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            // Unlocked, System UI in front: the notification bar, quick settings or one of their pop-ups.
            alarm && pkg == SYSTEM_UI && !keyguard.isKeyguardLocked -> act("notification bar") { closeShade() }
            alarm && pkg in SETTINGS -> act("settings") { backToAlarm() }
            else -> false
        }
    }

    /** The power menu: Power off / Restart buttons. */
    private fun isPowerMenu(root: AccessibilityNodeInfo) =
        POWER_WORDS.any { word -> root.findAccessibilityNodeInfosByText(word).any { it.isVisibleToUser } }

    private fun closeShade() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        // A pop-up from the bar (e.g. "Active apps") needs Back as well.
        handler.postDelayed({
            if (alarmRunning() && service.rootInActiveWindow?.packageName?.toString() == SYSTEM_UI && !keyguard.isKeyguardLocked) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }, 400)
    }

    private fun backToAlarm() {
        service.startActivity(Intent(service, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Backup: if Settings is still in front, leave it.
        handler.postDelayed({
            if (service.rootInActiveWindow?.packageName?.toString() in SETTINGS) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }, 800)
    }

    /** Runs [action] (at most every 300 ms), then looks again a moment later. Always returns true. */
    private fun act(what: String, action: () -> Unit): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAction > 300) {
            lastAction = now
            Log.d(StatusGuardService.TAG, "escape: closed $what")
            action()
            handler.postDelayed({
                val alarm = alarmRunning()
                if (alarm || sleep.isEnforcing()) look(alarm)
            }, 700)
        }
        return true
    }

    companion object {
        const val SYSTEM_UI = "com.android.systemui"
        val SETTINGS = setOf("com.android.settings", "com.android.settings.intelligence")
        private val POWER_WORDS = listOf("Power off", "Restart", "Reboot", "Shut down")
    }
}
