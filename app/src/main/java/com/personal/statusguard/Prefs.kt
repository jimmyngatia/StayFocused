package com.personal.statusguard

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalTime
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A time range in minutes after midnight. Ranges like 22:00-01:00 that cross midnight work too. */
class TimeWindow(private val start: Int, private val end: Int) {
    operator fun contains(minute: Int) =
        if (start <= end) minute in start until end else minute >= start || minute < end
}

/** Everything the app remembers: the rules, today's usage, the lockout and the password. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("status_guard", Context.MODE_PRIVATE)
    private val history = History(context)

    // ---- Rules (only changed from the password-protected settings screen) ----

    var dailyLimitMinutes: Int
        get() = sp.getInt("daily_limit", 30)
        set(v) = sp.edit().putInt("daily_limit", v).apply()

    var timeWindows: String
        get() = sp.getString("windows", "19:00-21:00")!!
        set(v) = sp.edit().putString("windows", v).apply()

    /** Your name, used in the emails to your accountability partners. */
    var name: String
        get() = sp.getString("name", "")!!
        set(v) = sp.edit().putString("name", v.trim()).apply()

    var lockoutMinutes: Int
        get() = sp.getInt("lockout", 5)
        set(v) = sp.edit().putInt("lockout", v).apply()

    /** Days where the time windows are ignored. 1 = Monday ... 7 = Sunday. Saturday by default. */
    var relaxedDays: Set<Int>
        get() = sp.getStringSet("relaxed_days", setOf("6"))!!.map { it.toInt() }.toSet()
        set(v) = sp.edit().putStringSet("relaxed_days", v.map { it.toString() }.toSet()).apply()

    var relaxedLimitMinutes: Int
        get() = sp.getInt("relaxed_limit", 60)
        set(v) = sp.edit().putInt("relaxed_limit", v).apply()

    /** Seconds of "Breathe…" before status opens. 0 = no pause. */
    var pauseSeconds: Int
        get() = sp.getInt("pause_seconds", 10)
        set(v) = sp.edit().putInt("pause_seconds", v).apply()

    fun isRelaxedToday() = LocalDate.now().dayOfWeek.value in relaxedDays

    fun todaysLimitMinutes() = if (isRelaxedToday()) relaxedLimitMinutes else dailyLimitMinutes

    /** Returns null when watching status is allowed right now, otherwise the reason it isn't. */
    fun blockReason(): String? {
        if (!isRelaxedToday()) {
            val now = LocalTime.now().let { it.hour * 60 + it.minute }
            val windows = parseWindows(timeWindows) ?: emptyList()
            if (windows.none { now in it }) return "Status is only allowed during $timeWindows."
        }
        val limit = todaysLimitMinutes()
        if (usedSecondsToday() >= limit * 60) return "You've used your $limit minutes of status for today."
        return null
    }

    // ---- Today's usage (resets automatically when the date changes) ----

    fun usedSecondsToday(): Int =
        if (sp.getString("used_date", "") == LocalDate.now().toString()) sp.getInt("used_seconds", 0) else 0

    fun addUsedSeconds(seconds: Int) {
        history.add(History.STATUS_SECONDS, seconds.toLong())
        sp.edit()
            .putString("used_date", LocalDate.now().toString())
            .putInt("used_seconds", usedSecondsToday() + seconds)
            .apply()
    }

    // ---- Lockout ----

    val lockedUntil: Long get() = sp.getLong("locked_until", 0)
    val lockReason: String get() = sp.getString("lock_reason", "")!!

    fun isLockedOut() = System.currentTimeMillis() < lockedUntil

    fun startLockout(reason: String) {
        sp.edit()
            .putLong("locked_until", System.currentTimeMillis() + lockoutMinutes * 60_000L)
            .putString("lock_reason", reason)
            .apply()
    }

    // ---- Password (stored as a salted hash, never as plain text) ----

    fun hasPassword() = sp.contains("pw_hash")

    fun setPassword(password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("pw_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pw_hash", Base64.encodeToString(hash(password, salt), Base64.NO_WRAP))
            .apply()
    }

    fun checkPassword(password: String): Boolean {
        val salt = sp.getString("pw_salt", null) ?: return false
        val expected = sp.getString("pw_hash", null) ?: return false
        val actual = hash(password, Base64.decode(salt, Base64.NO_WRAP))
        return MessageDigest.isEqual(actual, Base64.decode(expected, Base64.NO_WRAP))
    }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 20_000, 256))
            .encoded

    companion object {
        /** Reads text like "19:00-21:00, 12:30-13:00". Returns null if anything is written wrongly. */
        fun parseWindows(text: String): List<TimeWindow>? {
            val parts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            return parts.map { part ->
                val ends = part.split("-").map { it.trim() }
                if (ends.size != 2) return null
                TimeWindow(parseTime(ends[0]) ?: return null, parseTime(ends[1]) ?: return null)
            }
        }

        private fun parseTime(text: String): Int? {
            val match = Regex("""(\d{1,2}):(\d{2})""").matchEntire(text) ?: return null
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            if (hour > 24 || minute > 59 || (hour == 24 && minute > 0)) return null
            return hour * 60 + minute
        }
    }
}
