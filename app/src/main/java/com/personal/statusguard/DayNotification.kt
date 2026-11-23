package com.personal.statusguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate

/**
 * My day in the notifications, for phones without home screen widgets (Android Go).
 * Folded: phone time · devotion · done. Pulled open: today's list; tap a point to tick it.
 * Swiping it away hides it until tomorrow. It can be switched off on the My day screen.
 */
object DayNotification {
    private const val ID = 30
    private const val CHANNEL = "my_day"
    private const val MAX_ROWS = 8

    fun enabled(context: Context) = prefs(context).getBoolean("notification_on", true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("notification_on", on).remove("hidden_on").apply()
        update(context)
    }

    fun hideForToday(context: Context) = prefs(context).edit().putString("hidden_on", LocalDate.now().toString()).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("planner", Context.MODE_PRIVATE)

    fun update(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val hidden = prefs(context).getString("hidden_on", null) == LocalDate.now().toString()
        if (!enabled(context) || hidden) return manager.cancel(ID)

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "My day", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        val summary = PlannerWidget.summary(context)
        val items = PlanStore(context).items(LocalDate.now())

        val small = RemoteViews(context.packageName, R.layout.notification_day_small)
        small.setTextViewText(R.id.daySummary, summary)

        val big = RemoteViews(context.packageName, R.layout.notification_day_big)
        big.setTextViewText(R.id.daySummary, summary)
        big.removeAllViews(R.id.dayList)
        items.take(MAX_ROWS).forEachIndexed { i, item ->
            val row = RemoteViews(context.packageName, R.layout.notification_day_item)
            row.setImageViewResource(R.id.itemBox, if (item.done) R.drawable.w_box_checked else R.drawable.w_box)
            row.setTextViewText(R.id.itemText, (item.timeText()?.let { "$it  " } ?: "") + item.text)
            row.setFloat(R.id.itemText, "setAlpha", if (item.done) 0.5f else 1f)
            row.setOnClickPendingIntent(
                R.id.itemRow,
                PendingIntent.getBroadcast(
                    context, 100 + i,
                    Intent(context, PlannerWidget::class.java).setAction(PlannerWidget.ACTION_TOGGLE_ITEM).putExtra(PlannerWidget.EXTRA_ID, item.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            big.addView(R.id.dayList, row)
        }
        val more = items.size - MAX_ROWS
        big.setTextViewText(R.id.dayMore, if (items.isEmpty()) "Nothing planned yet. Tap to plan your day." else if (more > 0) "+ $more more" else "")
        big.setViewVisibility(R.id.dayMore, if (items.isEmpty() || more > 0) View.VISIBLE else View.GONE)

        val open = PendingIntent.getActivity(
            context, ID, PlannerActivity.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hide = PendingIntent.getBroadcast(
            context, ID, Intent(context, PlannerWidget::class.java).setAction(PlannerWidget.ACTION_HIDE_NOTIFICATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setContentIntent(open)
                .setDeleteIntent(hide)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build()
        )
    }
}
