package com.personal.statusguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * One strict alarm. [days] uses 1 = Monday ... 7 = Sunday. [code] is the barcode that switches it off
 * and [codeName] says where it is ("Bible on my desk").
 * Levels: 1 = scan. 2 = scan, walk outside. 3 = scan, walk outside, push-ups.
 * 4 = scan, walk outside, push-ups, jog. Levels 3 and 4 end by typing today's pledge.
 * Levels 2 to 4 then keep checking you're awake for [watchMinutes].
 * The walk can end at an outside checkpoint ([checkpointType]: a code, or the words on a sign).
 * [skipDate] is one day (yyyy-mm-dd) it won't ring.
 */
data class Alarm(
    val id: Int,
    val label: String,
    val hour: Int,
    val minute: Int,
    val days: Set<Int>,
    val code: String,
    val enabled: Boolean,
    val level: Int = 1,
    val steps: Int = 30,
    val pushups: Int = 10,
    val skipDate: String = "",
    val codeName: String = "",
    val walkMinutes: Int = 5, // time to walk the steps and reach the checkpoint
    val checkpointType: String = CHECK_NONE,
    val checkpoint: String = "", // the code, or the sign's key words
    val checkpointName: String = "", // "Tap outside"
    val cameraPushups: Boolean = false,
    val jogSteps: Int = 100,
    val watchMinutes: Int = 30,
) {
    val walks get() = level >= 2
    val pushupsOn get() = level >= 3
    val jogs get() = level >= 4
    val pledge get() = level >= 3

    companion object {
        const val CHECK_NONE = "none"
        const val CHECK_CODE = "code"
        const val CHECK_WORDS = "words"
        const val MIN_WATCH = 15
    }

    fun timeText() = "%02d:%02d".format(hour, minute)

    fun daysText() = when {
        days.size == 7 -> "Every day"
        days.isEmpty() -> "No days"
        else -> days.sorted().joinToString(" ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    /** The next time this alarm should ring, or null if it never will. */
    fun nextRing(honourSkip: Boolean = true, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        if (!enabled || days.isEmpty()) return null
        for (offset in 0L..8L) {
            val time = now.toLocalDate().plusDays(offset).atTime(hour, minute)
            val skipped = honourSkip && time.toLocalDate().toString() == skipDate
            if (time.dayOfWeek.value in days && time.isAfter(now) && !skipped) return time
        }
        return null
    }

    /** [nextRing] as epoch millis, for Android's alarm manager. */
    fun nextTrigger(): Long? = nextRing()?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
}

/** Saves the alarms on the phone as a small JSON list. */
class AlarmStore(context: Context) {
    private val sp = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun all(): List<Alarm> {
        val array = JSONArray(sp.getString("list", "[]"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val days = o.getJSONArray("days")
            Alarm(
                id = o.getInt("id"),
                label = o.getString("label"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                days = (0 until days.length()).map { days.getInt(it) }.toSet(),
                code = o.getString("code"),
                enabled = o.getBoolean("enabled"),
                level = o.optInt("level", 1),
                steps = o.optInt("steps", 30),
                pushups = o.optInt("pushups", 10),
                skipDate = o.optString("skipDate", ""),
                codeName = o.optString("codeName", ""),
                walkMinutes = o.optInt("walkMinutes", 5),
                checkpointType = o.optString("checkpointType", Alarm.CHECK_NONE),
                checkpoint = o.optString("checkpoint", ""),
                checkpointName = o.optString("checkpointName", ""),
                cameraPushups = o.optBoolean("cameraPushups", false),
                jogSteps = o.optInt("jogSteps", 100),
                watchMinutes = o.optInt("watchMinutes", 30),
            )
        }
    }

    fun get(id: Int) = all().find { it.id == id }

    fun newId() = (all().maxOfOrNull { it.id } ?: 0) + 1

    fun save(alarm: Alarm) = write(all().filter { it.id != alarm.id } + alarm)

    fun delete(id: Int) = write(all().filter { it.id != id })

    private fun write(alarms: List<Alarm>) {
        val array = JSONArray()
        for (a in alarms.sortedBy { it.hour * 60 + it.minute }) {
            array.put(
                JSONObject()
                    .put("id", a.id)
                    .put("label", a.label)
                    .put("hour", a.hour)
                    .put("minute", a.minute)
                    .put("days", JSONArray(a.days.sorted()))
                    .put("code", a.code)
                    .put("enabled", a.enabled)
                    .put("level", a.level)
                    .put("steps", a.steps)
                    .put("pushups", a.pushups)
                    .put("skipDate", a.skipDate)
                    .put("codeName", a.codeName)
                    .put("walkMinutes", a.walkMinutes)
                    .put("checkpointType", a.checkpointType)
                    .put("checkpoint", a.checkpoint)
                    .put("checkpointName", a.checkpointName)
                    .put("cameraPushups", a.cameraPushups)
                    .put("jogSteps", a.jogSteps)
                    .put("watchMinutes", a.watchMinutes)
            )
        }
        sp.edit().putString("list", array.toString()).apply()
    }
}

/** Registers alarms with Android so they ring on time, even if the app is closed. */
object AlarmScheduler {
    const val EXTRA_ID = "alarm_id"

    fun scheduleAll(context: Context) = AlarmStore(context).all().forEach { schedule(context, it) }

    fun schedule(context: Context, alarm: Alarm) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val fire = firePending(context, alarm.id)
        val at = alarm.nextTrigger()
        if (at == null || !manager.canScheduleExactAlarms()) {
            manager.cancel(fire)
            return
        }
        val show = PendingIntent.getActivity(
            context, alarm.id, Intent(context, AlarmsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        // "Alarm clock" alarms are exact, wake the phone and show the ⏰ icon in the status bar.
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
    }

    fun cancel(context: Context, id: Int) =
        context.getSystemService(AlarmManager::class.java).cancel(firePending(context, id))

    private fun firePending(context: Context, id: Int) = PendingIntent.getBroadcast(
        context, id,
        Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
