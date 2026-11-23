package com.personal.statusguard

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import kotlin.math.abs

/**
 * "My day" on the home screen, for phones that can't have widgets (Android Go).
 * Drawn by the accessibility service, only while the home screen is in front: phone time,
 * devotion and today's list. Tap a point to tick it; the arrow folds the list away; + and the
 * title open My day; drag the title up or down to move the card. Switch it off on the My day screen.
 */
class HomeCard(private val service: AccessibilityService, private val handler: Handler) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val prefs = service.getSharedPreferences("planner", Context.MODE_PRIVATE)
    private var card: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var summary = ""
    private var summaryAt = 0L

    /** Your home screen app only (Settings and some other apps also call themselves "home"). */
    private val homePackages: Set<String> get() = setOfNotNull(defaultHome(service))

    /** Decides whether the card should show, a moment after the screen changes. */
    private val check = Runnable { update() }

    /** While the card is showing: looks again every second (the notification bar doesn't always say it opened). */
    private val watch = object : Runnable {
        override fun run() {
            if (card == null) return
            update()
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        current = this
    }

    fun stop() {
        hide()
        if (current === this) current = null
    }

    /** Called for every accessibility event. */
    fun onEvent(type: Int, pkg: String) {
        if (type == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || pkg == EscapeGuard.SYSTEM_UI) {
            handler.removeCallbacks(check)
            handler.postDelayed(check, 150)
        }
    }

    private fun update() {
        val front = service.rootInActiveWindow?.packageName?.toString()
        val onHome = front != null && front in homePackages && !keyguard.isKeyguardLocked
        if (enabled(service) && onHome) show() else hide()
    }

    private fun show() {
        if (card != null) return
        val view = build()
        val lp = WindowManager.LayoutParams(
            service.resources.displayMetrics.widthPixels - service.dp(Space.L) * 2,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = prefs.getInt("card_y", service.dp(110))
        }
        runCatching { wm.addView(view, lp) }.onSuccess {
            card = view
            params = lp
            handler.postDelayed(watch, 1000)
        }
    }

    private fun hide() {
        handler.removeCallbacks(watch)
        card?.let { runCatching { wm.removeView(it) } }
        card = null
    }

    /** Redraws the card's contents (after a tick, a new point, a devotion log...). */
    fun redraw() {
        val old = card ?: return
        summaryAt = 0
        val view = build()
        runCatching { wm.removeView(old) }
        runCatching { wm.addView(view, params) }.onSuccess { card = view }.onFailure { card = null }
    }

    // ---- The card ----

    private fun build(): LinearLayout {
        val c = service
        val folded = prefs.getBoolean("card_folded", false)
        val today = LocalDate.now()
        val items = PlanStore(c).items(today)
        // Phone time takes a moment to read: at most once a minute.
        if (SystemClock.elapsedRealtime() - summaryAt > 60_000 || summary.isEmpty()) {
            summary = PlannerWidget.summary(c)
            summaryAt = SystemClock.elapsedRealtime()
        }

        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(c.getColor(R.color.surface))
                alpha = 245
                cornerRadius = c.dp(20).toFloat()
                setStroke(c.dp(1), c.getColor(R.color.outline))
            }
            setPadding(c.dp(Space.L), c.dp(Space.M), c.dp(Space.S), c.dp(Space.M))
            elevation = c.dp(6).toFloat()
        }

        // Header: title (tap to open, drag to move), +, fold arrow
        val header = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(c).apply {
            text = "My day"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.getColor(R.color.text_primary))
        }
        dragOrTap(title) { open(false) }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(icon(R.drawable.w_add) { open(true) })
        header.addView(icon(if (folded) R.drawable.w_expand_more else R.drawable.w_expand_less) {
            prefs.edit().putBoolean("card_folded", !folded).apply()
            redraw()
        })
        root.addView(header)

        root.addView(TextView(c).apply {
            text = summary
            textSize = 13f
            setTextColor(c.getColor(R.color.text_secondary))
            setPadding(0, 0, c.dp(Space.S), 0)
            setOnClickListener { open(false) }
        })

        if (!folded) {
            if (items.isEmpty()) root.addView(TextView(c).apply {
                text = "Nothing planned yet. Tap + to add."
                textSize = 13f
                setTextColor(c.getColor(R.color.text_secondary))
                setPadding(0, c.dp(Space.S), 0, 0)
                setOnClickListener { open(true) }
            })
            for (item in items.take(MAX_ROWS)) root.addView(row(item))
            if (items.size > MAX_ROWS) root.addView(TextView(c).apply {
                text = "+ ${items.size - MAX_ROWS} more"
                textSize = 13f
                setTextColor(c.getColor(R.color.accent))
                setPadding(0, c.dp(Space.XS), 0, 0)
                setOnClickListener { open(false) }
            })
        }
        return root
    }

    private fun row(item: PlanItem): View {
        val c = service
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, c.dp(6), 0, c.dp(6))
            addView(ImageView(c).apply {
                setImageResource(if (item.done) R.drawable.w_box_checked else R.drawable.w_box)
            }, LinearLayout.LayoutParams(c.dp(22), c.dp(22)))
            item.timeText()?.let { t ->
                addView(TextView(c).apply {
                    text = t
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(c.getColor(R.color.accent))
                    setPadding(c.dp(10), 0, 0, 0)
                })
            }
            addView(TextView(c).apply {
                text = item.text
                textSize = 14f
                maxLines = 2
                setTextColor(c.getColor(R.color.text_primary))
                alpha = if (item.done) 0.5f else 1f
                setPadding(c.dp(Space.S), 0, c.dp(Space.S), 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { PlanStore(c).toggle(LocalDate.now(), item.id) } // redraws through PlannerWidget.refresh
        }
    }

    private fun icon(res: Int, onTap: () -> Unit) = ImageView(service).apply {
        setImageResource(res)
        val pad = service.dp(6)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(service.dp(36), service.dp(36))
        setOnClickListener { onTap() }
    }

    /** The title opens My day when tapped, and moves the card up or down when dragged. */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragOrTap(view: View, onTap: () -> Unit) {
        var startY = 0f
        var startPos = 0
        var dragging = false
        view.setOnTouchListener { _, e ->
            val lp = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    startPos = lp.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (abs(dy) > service.dp(8)) dragging = true
                    if (dragging) {
                        val max = service.resources.displayMetrics.heightPixels - service.dp(160)
                        lp.y = (startPos + dy.toInt()).coerceIn(0, max)
                        card?.let { runCatching { wm.updateViewLayout(it, lp) } }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) prefs.edit().putInt("card_y", lp.y).apply() else onTap()
                }
            }
            true
        }
    }

    private fun open(addNow: Boolean) {
        hide()
        service.startActivity(PlannerActivity.intent(service, addNow = addNow))
    }

    companion object {
        private const val MAX_ROWS = 6

        /** The phone's home screen app (the launcher you actually use), e.g. com.android.launcher3. */
        fun defaultHome(context: Context): String? =
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.takeIf { it != "android" }

        /** The running card (inside the accessibility service), if any. */
        @Volatile private var current: HomeCard? = null

        fun enabled(context: Context) =
            context.getSharedPreferences("planner", Context.MODE_PRIVATE).getBoolean("home_card_on", true)

        fun setEnabled(context: Context, on: Boolean) =
            context.getSharedPreferences("planner", Context.MODE_PRIVATE).edit().putBoolean("home_card_on", on).apply()

        /** Called whenever My day changes. */
        fun redrawIfShowing() {
            val card = current ?: return
            card.handler.post { card.redraw() }
        }
    }
}
