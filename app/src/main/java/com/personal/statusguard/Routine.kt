package com.personal.statusguard

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Two gentle reminders: 15 minutes before bedtime ("tomorrow's alarm + plan tomorrow"),
 * and the weekly report on the chosen day and time (Sunday 19:00 unless changed), which is
 * also when it's emailed.
 */
object Routine {
    private const val ACTION_BEDTIME = "bedtime"
    private const val ACTION_WEEKLY = "weekly"
    private const val CHANNEL = "routine"

    fun scheduleAll(context: Context) {
        val now = LocalDateTime.now()
        // Next reminder = 15 minutes before the next bedtime that is at least 15 minutes away.
        val bedtime = SleepStore(context).schedules().mapNotNull { it.nextBedtime(now.plusMinutes(15)) }.minOrNull()
        set(context, ACTION_BEDTIME, bedtime?.minusMinutes(15))
        val email = EmailSettings(context)
        var weekly = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(email.day)))
            .withHour(email.minute / 60).withMinute(email.minute % 60).withSecond(0).withNano(0)
        if (!weekly.isAfter(now)) weekly = weekly.plusWeeks(1)
        set(context, ACTION_WEEKLY, weekly)
    }

    private fun set(context: Context, action: String, at: LocalDateTime?) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, action.hashCode(), Intent(context, RoutineReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (at == null) return manager.cancel(pending)
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pending)
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pending)
        }
    }

    /** What the next strict alarm is, e.g. "Next alarm: tomorrow 05:30 · Level 3 · Morning devotions". */
    fun nextAlarmText(context: Context): String {
        val next = AlarmStore(context).all().mapNotNull { a -> a.nextRing()?.let { a to it } }.minByOrNull { it.second }
            ?: return "No strict alarm set."
        val (alarm, time) = next
        val day = when (time.toLocalDate()) {
            LocalDateTime.now().toLocalDate() -> "today"
            LocalDateTime.now().toLocalDate().plusDays(1) -> "tomorrow"
            else -> time.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        }
        return "Next alarm: $day ${alarm.timeText()} · Level ${alarm.level} · ${alarm.label}"
    }

    fun notify(context: Context, id: Int, title: String, text: String, open: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Bedtime & weekly report", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            id, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
        )
    }

    class RoutineReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_BEDTIME -> notify(
                    context, 10, "Bedtime in 15 minutes",
                    "${nextAlarmText(context)}\nTap to plan tomorrow: what do you want to do?",
                    PlannerActivity.intent(context, tomorrow = true)
                )
                ACTION_WEEKLY -> {
                    val report = WeeklyReport.build(context)
                    val email = EmailSettings(context)
                    if (email.isReady) EmailReport.queue(context)
                    val sent = if (email.isReady) "\nEmailing it to ${email.recipients}." else ""
                    notify(
                        context, 11, "Your week: Grade ${report.grade}", report.headline + sent,
                        Intent(context, ReportActivity::class.java)
                    )
                }
            }
            scheduleAll(context)
        }
    }
}
