package com.personal.statusguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock

/**
 * The sleep-time part of the accessibility service.
 * - Covers every app except the allowed ones (Phone, Clock, your extras) with the night screen.
 * - WhatsApp and WhatsApp Business calls always get through.
 * - Shows the night screen as soon as the screen turns on, even over the lock screen.
 * - Counts sleep: time with the screen off during sleep time.
 */
class SleepGuard(private val service: AccessibilityService, private val handler: Handler) {
    private val store = SleepStore(service)
    private val history = History(service)
    private val audio = service.getSystemService(AudioManager::class.java)
    private var lastTry = 0L
    private var lastCover = 0L

    /** Allowed packages, refreshed once a minute (looking them up every event would be slow). */
    private var allowed = emptySet<String>()
    private var allowedAt = 0L
    private fun allowed(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - allowedAt > 60_000) {
            allowed = store.allowedPackages()
            allowedAt = now
        }
        return allowed
    }

    /** Home screen apps: covered without counting a try (going home isn't trying to open something). */
    private val homePackages: Set<String> by lazy {
        service.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> store.screenOff()
                Intent.ACTION_SCREEN_ON -> {
                    store.screenOn()
                    // Give the lock screen or a call screen a moment to appear, then decide.
                    handler.postDelayed({ showOnWake() }, 600)
                }
            }
        }
    }

    /** Every few seconds: catches apps left open at bedtime, and ended bypasses. */
    private val tick = object : Runnable {
        override fun run() {
            service.rootInActiveWindow?.packageName?.toString()?.let { check(it, screen = null, fromTick = true) }
            handler.postDelayed(this, 3000)
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else service.registerReceiver(screenReceiver, filter)
        handler.post(tick)
    }

    fun stop() {
        runCatching { service.unregisterReceiver(screenReceiver) }
    }

    /**
     * Covers [pkg] with the night screen if it isn't allowed right now. [screen] is WhatsApp's
     * current screen (to let calls through). Returns true if it was covered.
     */
    fun check(pkg: String, screen: String?, fromTick: Boolean = false): Boolean {
        if (!store.isEnforcing() || callActive()) return false
        if (pkg in allowed() || inCall(pkg, screen)) return false
        // Opening an app is a try. The home screen, and apps still open at bedtime, are covered quietly.
        if (pkg !in homePackages && !fromTick) countTry()
        cover()
        return true
    }

    /** Any call ringing or going on (phone, WhatsApp, WhatsApp Business...): never cover anything then. */
    private fun callActive(): Boolean {
        val mode = audio.mode
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /** WhatsApp or WhatsApp Business on a call screen. */
    private fun inCall(pkg: String, screen: String?): Boolean {
        if (pkg !in StatusDetector.WHATSAPP_PACKAGES) return false
        return StatusDetector.isCallScreen(screen)
    }

    /** Screen just turned on: show the night screen, unless a call or an alarm turned it on. */
    private fun showOnWake() {
        if (!store.isEnforcing() || callActive()) return
        val front = service.rootInActiveWindow?.packageName?.toString()
        when {
            front == null || front == "com.android.systemui" || front in homePackages -> cover()
            else -> check(front, screen = null)
        }
    }

    private fun cover() {
        val now = SystemClock.elapsedRealtime()
        if (NightActivity.isVisible || now - lastCover < 1000) return
        lastCover = now
        service.startActivity(NightActivity.intent(service))
    }

    /** One honest count per try (events come in bursts). */
    private fun countTry() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTry > 3000) history.addAttempt(History.SLEEP_ATTEMPTS)
        lastTry = now
    }
}
