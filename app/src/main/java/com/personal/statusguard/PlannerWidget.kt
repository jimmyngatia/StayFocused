package com.personal.statusguard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate

/**
 * The "My day" home screen widget: phone time today, whether today's devotion is logged, and
 * today's list. Tap a point to tick or untick it; the arrow folds the list away (summary only)
 * or opens it again; + and the title open the full list in the app. Resizable.
 */
class PlannerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_ITEM -> {
                val id = intent.getLongExtra(EXTRA_ID, -1)
                if (id >= 0) PlanStore(context).toggle(LocalDate.now(), id) // refreshes the widget
            }
            ACTION_HIDE_NOTIFICATION -> DayNotification.hideForToday(context)
            ACTION_FOLD -> {
                val sp = context.getSharedPreferences("planner", Context.MODE_PRIVATE)
                sp.edit().putBoolean("widget_folded", !sp.getBoolean("widget_folded", false)).apply()
                refresh(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_TOGGLE_ITEM = "com.personal.statusguard.widget.TOGGLE_ITEM"
        const val ACTION_HIDE_NOTIFICATION = "com.personal.statusguard.widget.HIDE_NOTIFICATION"
        private const val ACTION_FOLD = "com.personal.statusguard.widget.FOLD"
        const val EXTRA_ID = "id"

        /** "5h 17m on phone · Devotion ✓ · 3 of 5 done": the line on the widget and the notification. */
        fun summary(context: Context): String {
            val today = LocalDate.now()
            val parts = mutableListOf<String>()
            if (PhoneUsage.hasAccess(context)) {
                PhoneUsage.days(context, today, today)[today]?.totalMs?.let { parts += "${PhoneUsage.format(it)} on phone" }
            }
            val devotion = DevotionStore(context)
            when {
                devotion.isDone(today) -> parts += "Devotion ✓"
                devotion.isMissed(today) -> parts += "No devotion today"
                devotion.isScheduled(today) -> parts += "Devotion not logged"
            }
            PlanStore(context).summary(today)?.let { parts += it }
            return parts.joinToString("  ·  ").ifEmpty { "Plan your day" }
        }

        /** Redraws every "My day" widget and the My day notification. */
        fun refresh(context: Context) {
            DayNotification.update(context)
            HomeCard.redrawIfShowing()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlannerWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(context)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        private fun build(context: Context): RemoteViews {
            val today = LocalDate.now()
            val plan = PlanStore(context)
            val items = plan.items(today)
            val folded = context.getSharedPreferences("planner", Context.MODE_PRIVATE).getBoolean("widget_folded", false)
            val views = RemoteViews(context.packageName, R.layout.widget_planner)

            views.setTextViewText(R.id.widgetSummary, summary(context))

            // The list (or nothing, when folded)
            views.setImageViewResource(R.id.widgetToggle, if (folded) R.drawable.w_expand_more else R.drawable.w_expand_less)
            views.setViewVisibility(R.id.widgetList, if (folded || items.isEmpty()) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widgetEmpty, if (!folded && items.isEmpty()) View.VISIBLE else View.GONE)
            val rows = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            for (item in items) {
                val row = RemoteViews(context.packageName, R.layout.widget_planner_item)
                row.setImageViewResource(R.id.itemBox, if (item.done) R.drawable.w_box_checked else R.drawable.w_box)
                row.setTextViewText(R.id.itemTime, item.timeText() ?: "")
                row.setViewVisibility(R.id.itemTime, if (item.time == null) View.GONE else View.VISIBLE)
                row.setTextViewText(R.id.itemText, item.text)
                row.setFloat(R.id.itemText, "setAlpha", if (item.done) 0.5f else 1f)
                row.setOnClickFillInIntent(R.id.itemRow, Intent().putExtra(EXTRA_ID, item.id))
                rows.addItem(item.id, row)
            }
            views.setRemoteAdapter(R.id.widgetList, rows.build())
            views.setPendingIntentTemplate(
                R.id.widgetList,
                PendingIntent.getBroadcast(
                    context, 1, Intent(context, PlannerWidget::class.java).setAction(ACTION_TOGGLE_ITEM),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )

            // Buttons
            val open = PendingIntent.getActivity(
                context, 2, Intent(context, PlannerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetTitle, open)
            views.setOnClickPendingIntent(R.id.widgetSummary, open)
            views.setOnClickPendingIntent(R.id.widgetEmpty, open)
            views.setOnClickPendingIntent(
                R.id.widgetAdd,
                PendingIntent.getActivity(
                    context, 3, PlannerActivity.intent(context, addNow = true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            views.setOnClickPendingIntent(
                R.id.widgetToggle,
                PendingIntent.getBroadcast(
                    context, 4, Intent(context, PlannerWidget::class.java).setAction(ACTION_FOLD),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            return views
        }
    }
}
