package com.personal.statusguard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One streak: icon, name and days in a row. */
class Streak(val icon: Int, val name: String, val days: Int, val unit: String? = null)

/** The four area streaks, read fresh from history. */
fun currentStreaks(context: Context): List<Streak> {
    val h = History(context)
    return listOf(
        Streak(R.drawable.ic_chat, "Status", h.statusStreak()),
        Streak(R.drawable.ic_play, "Social", h.socialStreak()),
        Streak(R.drawable.ic_alarm, "Mornings", h.alarmStreak, unit = "in a row"),
        Streak(R.drawable.ic_moon, "Sleep", h.cleanStreak(History.SLEEP_ATTEMPTS)),
    )
}

/**
 * The streak block: the general streak (every rule kept) big at the top, then one tile per area.
 * Used on the home screen and in the report so both look the same.
 */
fun streakBlock(context: Context): LinearLayout {
    val overall = History(context).overallStreak()
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL

        // Big number + "days in a row"
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(TextView(context, null, 0, R.style.Display).apply {
                text = "$overall"
                if (overall == 0) setTextColor(context.getColor(R.color.text_secondary))
            })
            addView(TextView(context, null, 0, R.style.Body).apply {
                text = if (overall == 1) "day in a row" else "days in a row"
                setPadding(context.dp(Space.S), 0, 0, context.dp(Space.XS))
            })
        })
        addView(TextView(context, null, 0, R.style.Caption).apply {
            text = when {
                overall == 0 -> "Every rule kept today starts it again: no force opens, no sleep-time tries, no alarm shortcuts."
                overall >= 7 -> "Every rule kept. Brilliant run, keep it alive!"
                else -> "Every rule kept. Any slip resets it to 0."
            }
        })
        addView(streakRow(context, currentStreaks(context)))
    }
}

/** A row of streak tiles. Long streaks glow in the accent colour; zero is greyed out. */
fun streakRow(context: Context, streaks: List<Streak>): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = context.dp(Space.M) }
    streaks.forEachIndexed { i, s ->
        val color = context.getColor(if (s.days == 0) R.color.text_secondary else if (s.days >= 7) R.color.accent else R.color.text_primary)
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.streak_tile)
            setPadding(0, context.dp(Space.M), 0, context.dp(Space.M))
            addView(ImageView(context).apply {
                setImageResource(s.icon)
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            }, LinearLayout.LayoutParams(context.dp(20), context.dp(20)))
            addView(TextView(context).apply {
                text = "${s.days}"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(color)
                setPadding(0, context.dp(Space.XS), 0, 0)
            })
            addView(TextView(context).apply {
                text = s.name + "\n" + (s.unit ?: if (s.days == 1) "day" else "days")
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_secondary))
            })
        }
        addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (i > 0) marginStart = context.dp(Space.S)
        })
    }
}
