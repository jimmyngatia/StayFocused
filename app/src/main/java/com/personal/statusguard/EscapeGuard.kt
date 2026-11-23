package com.personal.statusguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Closes the ways out of a running alarm and of sleep time.
 * - The power menu (hold the power button): closed straight away while an alarm is running and
 *   during sleep time, so "Power off" and "Restart" can't be tapped. (Holding power + volume down
 *   for about 10 seconds forces a restart; no app can stop that. It still counts as switched off.)
 * - While an alarm is running: the notification bar is closed as soon as it's pulled down
 *   (its "Active apps → Stop" would kill the alarm), and Android Settings sends you back to the alarm
 *   (that's where permissions are removed and apps force-stopped).
 */
class EscapeGuard(private val service: AccessibilityService, private val handler: Handler) {
    private val sleep = SleepStore(service)
    private var lastAction = 0L

    /** Called for every accessibility event. Returns true if it closed something. */
    fun onEvent(event: AccessibilityEvent, pkg: String): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        val alarm = AlarmService.phase != null
        if (!alarm && !sleep.isEnforcing()) return false

        if (pkg == SYSTEM_UI) {
            val words = (event.text.joinToString(" ") + " " + (event.contentDescription ?: "")).trim()
            val name = event.className?.toString() ?: ""
            Log.d(StatusGuardService.TAG, "escape: systemui class=$name text=$words")
            if (isPowerMenu(name, words)) return act("power menu") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            if (alarm && isShade(words)) return act("notification bar") { closeShade() }
        }
        if (alarm && pkg in SETTINGS) return act("settings") { backToAlarm() }
        return false
    }

    /** The power menu: a System UI dialog with Power off / Restart. */
    private fun isPowerMenu(name: String, words: String) =
        name.contains("GlobalActions", ignoreCase = true) ||
            POWER_WORDS.any { words.contains(it, ignoreCase = true) }

    /** The notification bar or quick settings, pulled down. */
    private fun isShade(words: String) = SHADE_WORDS.any { words.contains(it, ignoreCase = true) }

    private fun closeShade() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }

    private fun backToAlarm() {
        service.startActivity(Intent(service, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Backup: if Settings is still in front, leave it.
        handler.postDelayed({
            if (service.rootInActiveWindow?.packageName?.toString() in SETTINGS) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }, 800)
    }

    /** Runs [action] (at most every 300 ms: events come in bursts). Always returns true. */
    private fun act(what: String, action: () -> Unit): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAction > 300) {
            lastAction = now
            Log.d(StatusGuardService.TAG, "escape: closed $what")
            action()
            // Some menus open in two steps: check once more.
            handler.postDelayed({ recheck() }, 400)
        }
        return true
    }

    /** Closes a power menu or shade that is still showing a moment later. */
    private fun recheck() {
        val alarm = AlarmService.phase != null
        if (!alarm && !sleep.isEnforcing()) return
        val front = service.rootInActiveWindow ?: return
        val pkg = front.packageName?.toString()
        if (pkg == SYSTEM_UI && POWER_WORDS.any { front.findAccessibilityNodeInfosByText(it).isNotEmpty() }) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        } else if (alarm && pkg in SETTINGS) backToAlarm()
    }

    companion object {
        const val SYSTEM_UI = "com.android.systemui"
        val SETTINGS = setOf("com.android.settings", "com.android.settings.intelligence")
        private val POWER_WORDS = listOf("Power off", "Restart", "Reboot", "Shut down")
        private val SHADE_WORDS = listOf("Notification shade", "Quick settings", "Active apps")
    }
}
