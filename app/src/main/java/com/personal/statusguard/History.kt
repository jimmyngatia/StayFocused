package com.personal.statusguard

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * A small diary of each day: status minutes, blocked attempts, alarms and phone time.
 * Streaks and the weekly report are worked out from it.
 */
class History(context: Context) {
    private val sp = context.getSharedPreferences("history", Context.MODE_PRIVATE)

    init {
        if (!sp.contains("first_day")) sp.edit().putString("first_day", LocalDate.now().toString()).apply()
    }

    /** The day the app started keeping history. Streaks can't go back further. */
    val firstDay: LocalDate get() = LocalDate.parse(sp.getString("first_day", LocalDate.now().toString()))

    fun day(date: LocalDate): JSONObject = JSONObject(sp.getString(date.toString(), "{}")!!)

    fun get(date: LocalDate, field: String): Long = day(date).optLong(field, 0)

    fun add(field: String, amount: Long = 1, date: LocalDate = LocalDate.now()) {
        val d = day(date)
        d.put(field, d.optLong(field) + amount)
        sp.edit().putString(date.toString(), d.toString()).apply()
    }

    /** Keeps the bigger of the saved and new value (Android forgets old phone-usage data). */
    fun keepMax(date: LocalDate, field: String, value: Long) {
        val d = day(date)
        if (value > d.optLong(field)) sp.edit().putString(date.toString(), d.put(field, value).toString()).apply()
    }

    /** A blocked attempt, plus the hour it happened (to find when temptation is strongest). */
    fun addAttempt(field: String) {
        add(field)
        add("hour_${LocalTime.now().hour}")
    }

    /**
     * Where a streak may start counting from. Normally the first day; a force open moves it
     * forward so the streak is halved.
     */
    private fun streakStart(field: String): LocalDate =
        sp.getString("start_$field", null)?.let { maxOf(LocalDate.parse(it), firstDay) } ?: firstDay

    /** Days in a row, up to today, without any [field] (e.g. no blocked status attempts). */
    fun cleanStreak(field: String): Int {
        var days = 0
        var date = LocalDate.now()
        val start = streakStart(field)
        while (!date.isBefore(start) && get(date, field) == 0L) {
            days++
            date = date.minusDays(1)
        }
        return days
    }

    /**
     * Everything that counts as a slip: any one of these on a day breaks the general streak.
     * Blocked tries on status, WhatsApp (during a lockout) and social media are NOT slips:
     * the app just blocks you and counts them for the report.
     */
    private val slips get() = listOf(SLEEP_ATTEMPTS, SLEEP_EMERGENCY, ALARMS_STOPPED, ALARMS_ESCAPED) +
        SocialApp.entries.map { it.forceField }

    /** Did anything go wrong on [date]? */
    fun slipped(date: LocalDate) = slips.any { get(date, it) > 0 }

    /**
     * The general streak: days in a row where every rule was kept. Any slip
     * (a force open, a sleep-time try or bypass, an alarm shortcut) breaks it.
     */
    fun overallStreak(): Int {
        var days = 0
        var date = LocalDate.now()
        val start = streakStart(OVERALL)
        while (!date.isBefore(start) && !slipped(date)) {
            days++
            date = date.minusDays(1)
        }
        return days
    }

    /** Status streak: days since the app started; blocked tries don't break it, only halving cuts it. */
    fun statusStreak(): Int =
        (ChronoUnit.DAYS.between(streakStart(STATUS_ATTEMPTS), LocalDate.now()) + 1).toInt().coerceAtLeast(0)

    /** Days in a row with no social media force open (a force open halves it, like every streak). */
    fun socialStreak(): Int {
        if (!sp.contains("start_$SOCIAL")) sp.edit().putString("start_$SOCIAL", LocalDate.now().toString()).apply()
        return (ChronoUnit.DAYS.between(streakStart(SOCIAL), LocalDate.now()) + 1).toInt().coerceAtLeast(0)
    }

    /** Makes a streak count only [keep] days, by moving its start (tomorrow if 0). */
    private fun setStreak(field: String, keep: Int) =
        sp.edit().putString("start_$field", LocalDate.now().minusDays(keep - 1L).toString()).apply()

    /** The price of a force open or of going back to sleep: every streak is cut in half. */
    fun halveAllStreaks() {
        setStreak(STATUS_ATTEMPTS, statusStreak() / 2)
        setStreak(SLEEP_ATTEMPTS, cleanStreak(SLEEP_ATTEMPTS) / 2)
        setStreak(SOCIAL, socialStreak() / 2)
        setStreak(OVERALL, overallStreak() / 2)
        alarmStreak /= 2
    }

    /** Alarms finished in a row without the emergency password or a restart. */
    var alarmStreak: Int
        get() = sp.getInt("alarm_streak", 0)
        set(v) = sp.edit().putInt("alarm_streak", v).apply()

    /**
     * True while a real alarm is running. If it's still true when no alarm is running
     * (the phone was restarted, or the app force-stopped), the alarm was escaped.
     */
    var alarmRunning: Boolean
        get() = sp.getBoolean("alarm_running", false)
        set(v) {
            sp.edit().putBoolean("alarm_running", v).commit() // saved immediately, in case the phone dies
        }

    // ---- The morning log: what happened with each real alarm ----

    /** Saves (or updates) one morning's record under [date]. Saved immediately, in case the phone dies. */
    fun saveMorning(date: LocalDate, key: String, record: JSONObject) {
        val d = day(date)
        val all = d.optJSONObject("mornings") ?: JSONObject()
        all.put(key, record)
        sp.edit().putString(date.toString(), d.put("mornings", all).toString()).commit()
    }

    /** The mornings logged on [date], in ringing order. */
    fun mornings(date: LocalDate): List<JSONObject> {
        val all = day(date).optJSONObject("mornings") ?: return emptyList()
        return all.keys().asSequence().sorted().map { all.getJSONObject(it) }.toList()
    }

    /** A real alarm started ringing: remember it, in case the phone is switched off to escape it. */
    fun alarmStarted(label: String, morningDate: LocalDate, morningKey: String) {
        sp.edit().putString("alarm_label", label)
            .putString("morning_date", morningDate.toString())
            .putString("morning_key", morningKey)
            .putString("alarm_since", java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
            .commit()
        alarmRunning = true
    }

    /** Counts an escaped alarm (phone switched off or app force-stopped) and tells the partners. */
    fun checkEscapedAlarm(context: Context) {
        if (!alarmRunning || AlarmService.phase != null) return
        alarmRunning = false
        add(ALARMS_ESCAPED)
        alarmStreak = 0
        // Mark that morning in the log.
        val date = sp.getString("morning_date", null)?.let { LocalDate.parse(it) }
        val key = sp.getString("morning_key", null)
        if (date != null && key != null) {
            val all = day(date).optJSONObject("mornings")
            all?.optJSONObject(key)?.let { saveMorning(date, key, it.put("result", Morning.SWITCHED_OFF)) }
        }
        EmailReport.queuePartnerEmail(
            context, EmailReport.SWITCHED_OFF,
            "label" to sp.getString("alarm_label", "Alarm")!!, "rang" to sp.getString("alarm_since", "")!!,
        )
    }

    /** "Switch off for tomorrow" — free, but counted on the day it skips (undo takes it back). */
    fun alarmPaused(date: LocalDate, paused: Boolean) {
        val d = day(date)
        d.put(ALARMS_PAUSED, maxOf(0, d.optLong(ALARMS_PAUSED) + if (paused) 1 else -1))
        sp.edit().putString(date.toString(), d.toString()).apply()
    }

    companion object {
        const val STATUS_SECONDS = "statusSeconds"
        const val STATUS_ATTEMPTS = "statusAttempts"
        const val RESISTED = "resisted" // chose "Never mind" on the pause screen
        const val SLEEP_ATTEMPTS = "sleepAttempts"
        const val SLEEP_EMERGENCY = "sleepEmergency" // force bypass of the night screen (was "emergency open")
        const val SLEEP_MS = "sleepMs" // screen-off time during sleep time, stored under the wake-up date
        const val ALARMS_DONE = "alarmsDone"
        const val ALARMS_STOPPED = "alarmsStopped" // emergency password
        const val ALARMS_ESCAPED = "alarmsEscaped" // restart or force stop
        const val ALARMS_PAUSED = "alarmsPaused" // "switch off for tomorrow"
        const val AWAKE_MISSED = "awakeMissed" // missed an "are you still awake?" check: went back to sleep
        const val PHONE_MS = "phoneMs"
        const val UNLOCKS = "unlocks"
        const val SOCIAL = "social" // the social media streak
        const val OVERALL = "overall" // the general streak
    }
}

/** "1st", "2nd", "3rd", "4th"... */
fun ordinal(n: Long): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1L -> "st"
        2L -> "nd"
        3L -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
