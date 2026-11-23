package com.personal.statusguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * One sleep schedule, e.g. 22:30 → 05:30. Times are minutes after midnight.
 * [days] are the days the night *starts* (1 = Monday ... 7 = Sunday).
 */
data class SleepSchedule(
    val id: Int,
    val bed: Int,
    val wake: Int,
    val days: Set<Int>,
    val enabled: Boolean,
) {
    fun bedText() = "%02d:%02d".format(bed / 60, bed % 60)
    fun wakeText() = "%02d:%02d".format(wake / 60, wake % 60)

    fun daysText() = when {
        days.size == 7 -> "Every night"
        days.isEmpty() -> "No days"
        else -> days.sorted().joinToString(" ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    /** Is [now] inside this sleep time? Handles nights that cross midnight. */
    fun covers(now: LocalDateTime): Boolean {
        if (!enabled) return false
        val minute = now.hour * 60 + now.minute
        val today = now.dayOfWeek.value
        val yesterday = now.minusDays(1).dayOfWeek.value
        return if (bed < wake) today in days && minute >= bed && minute < wake
        else (today in days && minute >= bed) || (yesterday in days && minute < wake)
    }

    /** The night [time] belongs to, named by its wake-up date (e.g. Mon 23:00 → Tue 06:00 is Tuesday). */
    fun nightOf(time: LocalDateTime): LocalDate {
        val minute = time.hour * 60 + time.minute
        return if (bed > wake && minute >= bed) time.toLocalDate().plusDays(1) else time.toLocalDate()
    }

    /** The next bedtime after [now], or null if never. */
    fun nextBedtime(now: LocalDateTime): LocalDateTime? {
        if (!enabled) return null
        for (offset in 0L..7L) {
            val time = now.toLocalDate().plusDays(offset).atTime(bed / 60, bed % 60)
            if (time.dayOfWeek.value in days && time.isAfter(now)) return time
        }
        return null
    }
}

/**
 * Sleep schedules, the apps you can still use at night, the 5-minute bypass, and the sleep counter.
 * During sleep time everything else is covered by the night screen.
 */
class SleepStore(private val context: Context) {
    private val sp = context.getSharedPreferences("sleep", Context.MODE_PRIVATE)

    fun schedules(): List<SleepSchedule> {
        val array = JSONArray(sp.getString("schedules", "[]"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val days = o.getJSONArray("days")
            SleepSchedule(
                id = o.getInt("id"),
                bed = o.getInt("bed"),
                wake = o.getInt("wake"),
                days = (0 until days.length()).map { days.getInt(it) }.toSet(),
                enabled = o.getBoolean("enabled"),
            )
        }
    }

    fun newId() = (schedules().maxOfOrNull { it.id } ?: 0) + 1

    fun save(schedule: SleepSchedule) = write(schedules().filter { it.id != schedule.id } + schedule)

    fun delete(id: Int) = write(schedules().filter { it.id != id })

    private fun write(list: List<SleepSchedule>) {
        val array = JSONArray()
        for (s in list.sortedBy { it.bed }) {
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("bed", s.bed)
                    .put("wake", s.wake)
                    .put("days", JSONArray(s.days.sorted()))
                    .put("enabled", s.enabled)
            )
        }
        sp.edit().putString("schedules", array.toString()).apply()
    }

    /** The sleep schedule happening right now, if any. */
    fun current(now: LocalDateTime = LocalDateTime.now()) = schedules().firstOrNull { it.covers(now) }

    // ---- Apps you can still use at night ----

    /** Extra apps you chose (package names). Phone and Clock are always allowed. */
    var extraApps: Set<String>
        get() = sp.getStringSet("allowed_apps", emptySet())!!
        set(v) = sp.edit().putStringSet("allowed_apps", v).apply()

    /** Phone and Clock: always usable at night (shown on the night screen). */
    fun basicApps(): List<String> {
        val pm = context.packageManager
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        val clock = pm.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)?.activityInfo?.packageName
            ?.takeIf { it != "android" }
        return listOfNotNull(dialer, clock).distinct()
    }

    /** The apps shown on the night screen, in order. */
    fun nightApps() = (basicApps() + extraApps.sortedBy { PhoneUsage.appName(context, it).lowercase() }).distinct()

    /** Everything that keeps working at night, including parts of Android itself (calls, lock screen). */
    fun allowedPackages(): Set<String> = nightApps().toSet() + context.packageName + SYSTEM_PACKAGES + listOfNotNull(keyboard())

    /** The keyboard is its own app; typing in an allowed app must not bring the night screen. */
    private fun keyboard() = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.let { ComponentName.unflattenFromString(it)?.packageName }

    // ---- Force bypass ----

    val bypassUntil get() = sp.getLong("bypass_until", 0)

    fun isBypassed() = System.currentTimeMillis() < bypassUntil

    /** Opens the whole phone for 5 minutes. Counted in the report, and every streak is cut in half. */
    fun startBypass() {
        sp.edit().putLong("bypass_until", System.currentTimeMillis() + BYPASS_MINUTES * 60_000L).apply()
        History(context).apply {
            add(History.SLEEP_EMERGENCY)
            halveAllStreaks()
        }
    }

    /** Is sleep time being enforced right now? (Not during a bypass, and never while an alarm rings.) */
    fun isEnforcing() = current() != null && !isBypassed() && AlarmService.phase == null

    fun isBlockedNow(pkg: String) = isEnforcing() && pkg !in allowedPackages()

    // ---- Sleep counter: time with the screen off during sleep time ----

    fun screenOff(now: Long = System.currentTimeMillis()) = sp.edit().putLong("off_since", now).apply()

    /** Adds the time the screen was off (only the part inside sleep time) to that night. */
    fun screenOn(now: Long = System.currentTimeMillis()) {
        val since = sp.getLong("off_since", 0)
        sp.edit().remove("off_since").apply()
        if (since <= 0 || now <= since) return
        val schedules = schedules()
        val history = History(context)
        val zone = ZoneId.systemDefault()
        var t = maxOf(since, now - 36 * 3_600_000L) // a phone left off for days doesn't count as sleep
        while (t < now) {
            val step = minOf(60_000L, now - t)
            val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone)
            schedules.firstOrNull { it.covers(time) }?.let { history.add(History.SLEEP_MS, step, it.nightOf(time)) }
            t += step
        }
    }

    /** Sleep counted so far for the night happening now (or last night, if it's daytime). */
    fun sleptTonightMs(): Long {
        val now = LocalDateTime.now()
        val night = current(now)?.nightOf(now) ?: now.toLocalDate()
        return History(context).get(night, History.SLEEP_MS)
    }

    companion object {
        const val BYPASS_MINUTES = 5

        /** Android's own parts: lock screen, notifications, calls, permission pop-ups. */
        val SYSTEM_PACKAGES = setOf(
            "android", "com.android.systemui", "com.android.phone", "com.android.server.telecom",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.android.emergency", "com.google.android.apps.safetyhub",
            // Incoming/ongoing call screens (separate from the dialer on some phones)
            "com.android.incallui", "com.samsung.android.incallui", "com.google.android.dialer",
        )
    }
}
