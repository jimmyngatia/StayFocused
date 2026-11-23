package com.personal.statusguard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Phone screen time, read from Android's own usage data ("Usage access" permission). */
object PhoneUsage {
    /** One day of phone use. [perApp] is milliseconds per package. */
    class Day(val perApp: MutableMap<String, Long> = mutableMapOf(), var unlocks: Int = 0) {
        val totalMs get() = perApp.values.sum()
    }

    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Screen time per app for each day from [from] to [to]. Days Android has forgotten
     * (usually older than about a week) come back empty.
     */
    fun days(context: Context, from: LocalDate, to: LocalDate): Map<LocalDate, Day> {
        val result = mutableMapOf<LocalDate, Day>()
        if (!hasAccess(context)) return result
        val zone = ZoneId.systemDefault()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(System.currentTimeMillis(), to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        // The home screen isn't "using the phone", so it's left out.
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName

        fun dayOf(ms: Long) = result.getOrPut(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()) { Day() }

        /** Adds a stretch of use to the right day(s), splitting at midnight. */
        fun addTime(pkg: String, from: Long, until: Long) {
            if (pkg == home || until <= from) return
            var t = from
            while (t < until) {
                val date = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
                val midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val stop = minOf(until, midnight)
                val day = dayOf(t)
                day.perApp[pkg] = (day.perApp[pkg] ?: 0) + (stop - t)
                t = stop
            }
        }

        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(start, end)
        val event = UsageEvents.Event()
        var openApp: String? = null
        var openedAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    openApp?.let { addTime(it, openedAt, event.timeStamp) }
                    openApp = event.packageName
                    openedAt = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (event.packageName == openApp) {
                    addTime(event.packageName, openedAt, event.timeStamp)
                    openApp = null
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    openApp?.let { addTime(it, openedAt, event.timeStamp) }
                    openApp = null
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> dayOf(event.timeStamp).unlocks++
            }
        }
        openApp?.let { addTime(it, openedAt, end) }
        return result
    }

    fun appName(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    /** "3h 12m" or "45m". */
    fun format(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
}
