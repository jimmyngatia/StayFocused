package com.personal.statusguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * One WhatsApp focus time, e.g. "Morning devotion" 05:30 → 08:00 with a 10-minute quick check.
 * Times are minutes after midnight. [days] are the days it starts (1 = Monday ... 7 = Sunday).
 */
data class FocusBlock(
    val id: Int,
    val name: String,
    val start: Int,
    val end: Int,
    val days: Set<Int>,
    val quickMinutes: Int,
    val enabled: Boolean,
) {
    fun startText() = "%02d:%02d".format(start / 60, start % 60)
    fun endText() = "%02d:%02d".format(end / 60, end % 60)

    fun daysText() = when {
        days.size == 7 -> "Every day"
        days.isEmpty() -> "No days"
        else -> days.sorted().joinToString(" ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    /** The day this focus time started, if [now] is inside it. Handles times that cross midnight. */
    fun startedOn(now: LocalDateTime): LocalDate? {
        if (!enabled) return null
        val minute = now.hour * 60 + now.minute
        val date = when {
            start < end -> if (minute in start until end) now.toLocalDate() else null
            minute >= start -> now.toLocalDate()
            minute < end -> now.toLocalDate().minusDays(1)
            else -> null
        }
        return date?.takeIf { it.dayOfWeek.value in days }
    }
}

/**
 * WhatsApp focus times: during each one WhatsApp opens for a short quick check (counted only while
 * it's on screen), then closes. "Open anyway" gives a few more minutes after a wait that grows each time.
 */
class FocusStore(context: Context) {
    private val sp = context.getSharedPreferences("whatsapp_focus", Context.MODE_PRIVATE)

    fun blocks(): List<FocusBlock> {
        val array = JSONArray(sp.getString("blocks", "[]"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val days = o.getJSONArray("days")
            FocusBlock(
                id = o.getInt("id"),
                name = o.getString("name"),
                start = o.getInt("start"),
                end = o.getInt("end"),
                days = (0 until days.length()).map { days.getInt(it) }.toSet(),
                quickMinutes = o.getInt("quick"),
                enabled = o.getBoolean("enabled"),
            )
        }
    }

    fun newId() = (blocks().maxOfOrNull { it.id } ?: 0) + 1

    fun save(block: FocusBlock) = write(blocks().filter { it.id != block.id } + block)

    fun delete(id: Int) = write(blocks().filter { it.id != id })

    private fun write(list: List<FocusBlock>) {
        val array = JSONArray()
        for (b in list.sortedBy { it.start }) {
            array.put(
                JSONObject()
                    .put("id", b.id)
                    .put("name", b.name)
                    .put("start", b.start)
                    .put("end", b.end)
                    .put("days", JSONArray(b.days.sorted()))
                    .put("quick", b.quickMinutes)
                    .put("enabled", b.enabled)
            )
        }
        sp.edit().putString("blocks", array.toString()).apply()
    }

    /** The focus time happening now and the day it started, if any. */
    fun current(now: LocalDateTime = LocalDateTime.now()): Pair<FocusBlock, LocalDate>? =
        blocks().firstNotNullOfOrNull { b -> b.startedOn(now)?.let { b to it } }

    // ---- Settings for every focus time ----

    /** Minutes WhatsApp opens for with "Open anyway". */
    var extraMinutes: Int
        get() = sp.getInt("extra_minutes", 2)
        set(v) = sp.edit().putInt("extra_minutes", v).apply()

    /** Seconds to wait before "Open anyway" works. It grows each time: 10, 20, 30... */
    var waitSeconds: Int
        get() = sp.getInt("wait_seconds", 10)
        set(v) = sp.edit().putInt("wait_seconds", v).apply()

    // ---- Usage, per focus time and day ----

    private fun key(what: String, block: FocusBlock, day: LocalDate) = "${what}_${block.id}_$day"

    fun usedSeconds(block: FocusBlock, day: LocalDate) = sp.getInt(key("used", block, day), 0)

    fun addUsed(block: FocusBlock, day: LocalDate, seconds: Int) =
        sp.edit().putInt(key("used", block, day), usedSeconds(block, day) + seconds).apply()

    /** "Open anyway" used so far in this focus time. */
    fun extras(block: FocusBlock, day: LocalDate) = sp.getInt(key("extras", block, day), 0)

    /** The wait before the next "Open anyway" in this focus time. */
    fun nextWaitSeconds(block: FocusBlock, day: LocalDate) = waitSeconds * (extras(block, day) + 1)

    val extraUntil get() = sp.getLong("extra_until", 0)

    fun isExtraOpen() = System.currentTimeMillis() < extraUntil

    /** "Open anyway": a few minutes of WhatsApp. A slip: resets the WhatsApp streak and breaks today's general streak. */
    fun openAnyway(context: Context, block: FocusBlock, day: LocalDate) {
        sp.edit()
            .putLong("extra_until", System.currentTimeMillis() + extraMinutes * 60_000L)
            .putInt(key("extras", block, day), extras(block, day) + 1)
            .apply()
        History(context).add(History.WA_EXTRA)
    }
}

/**
 * The focus-time part of the accessibility service: counts the quick check while WhatsApp is on
 * screen and covers WhatsApp once it's used up. Calls always get through.
 */
class FocusGuard(private val service: AccessibilityService, private val handler: Handler) {
    private val store = FocusStore(service)
    private val history = History(service)
    private val power = service.getSystemService(PowerManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private var ticking = false
    private var covering = false
    private var lastTry = 0L
    private var screen: String? = null

    /** Once a second while WhatsApp is open, so the quick check is counted even if nothing moves. */
    private val tick = object : Runnable {
        override fun run() {
            ticking = check(countSecond = true)
            if (ticking) handler.postDelayed(this, 1000)
        }
    }

    /** Looks again a little later: on a slow phone WhatsApp's first screen can take seconds to be readable. */
    private val retries = listOf(1000L, 3000L, 6000L).map { delay -> delay to Runnable { startTicking() } }

    private fun startTicking() {
        if (ticking) return
        ticking = check(countSecond = false)
        if (ticking) handler.postDelayed(tick, 1000)
    }

    /** Called for WhatsApp events. [currentScreen] is WhatsApp's screen (to let calls through). Returns true if WhatsApp is covered. */
    fun onEvent(currentScreen: String?): Boolean {
        screen = currentScreen
        if (!ticking) {
            startTicking()
            if (!ticking && store.current() != null) for ((delay, run) in retries) {
                handler.removeCallbacks(run)
                handler.postDelayed(run, delay)
            }
        }
        return covering
    }

    /** Looks at the screen and acts if needed. Returns true while WhatsApp is still in front. */
    private fun check(countSecond: Boolean): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        if (pkg !in StatusDetector.WHATSAPP_PACKAGES) return false
        if (SleepStore(service).isBlockedNow(pkg)) return false // the night screen deals with it
        val (block, day) = store.current() ?: return true
        if (covering || StatusDetector.isCallScreen(screen) || callActive()) return true
        if (store.isExtraOpen()) {
            if (countSecond && power.isInteractive) history.add(History.WA_FOCUS_SECONDS)
            return true
        }
        if (store.usedSeconds(block, day) < block.quickMinutes * 60) {
            if (countSecond && power.isInteractive) {
                store.addUsed(block, day, 1)
                history.add(History.WA_FOCUS_SECONDS)
            }
            return true
        }
        cover(block, pkg)
        return false
    }

    private fun callActive(): Boolean {
        val mode = audio.mode
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun cover(block: FocusBlock, pkg: String) {
        covering = true
        // Extra minutes that just ran out aren't a new try.
        val extraEnded = System.currentTimeMillis() - store.extraUntil in 0..10_000
        if (!extraEnded) countTry()
        Log.d(StatusGuardService.TAG, "focus: covering WhatsApp (${block.name}) extraEnded=$extraEnded")
        service.startActivity(FocusBlockActivity.intent(service, pkg, extraEnded))
        // Backup: if the cover didn't appear, leave WhatsApp.
        handler.postDelayed({
            if (service.rootInActiveWindow?.packageName?.toString() == pkg) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }, 800)
        handler.postDelayed({ covering = false }, 1500)
    }

    /** One honest count per try (events come in bursts). */
    private fun countTry() {
        val now = System.currentTimeMillis()
        if (now - lastTry > 3000) history.addAttempt(History.WA_FOCUS_TRIES)
        lastTry = now
    }
}
