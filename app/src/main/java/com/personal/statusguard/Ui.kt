package com.personal.statusguard

import android.content.Context
import android.content.res.ColorStateList
import android.widget.TextView

/** dp → pixels, so code-built views use the same spacing scale as the layouts. */
fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

/** Spacing scale (dp): matches res/values/dimens.xml. */
object Space {
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 24
}

/** Puts [icon] in front of the text, tinted [color] (a colour resource). 0 removes it. */
fun TextView.icon(icon: Int, color: Int = R.color.text_secondary) {
    setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    compoundDrawablePadding = context.dp(Space.S)
    if (icon != 0) compoundDrawableTintList = ColorStateList.valueOf(context.getColor(color))
}

/** A status line: green tick when [ok], red block sign when not. */
fun TextView.status(ok: Boolean, value: String) {
    text = value
    if (ok) icon(R.drawable.ic_check, R.color.accent) else icon(R.drawable.ic_block, R.color.danger)
}
