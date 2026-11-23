package com.personal.statusguard

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.SystemClock
import android.text.TextUtils
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
 * Drawn by the accessibility service, only while the home screen is in front.
 * - Open: a square about half the screen wide: a ring (points done), phone time, devotion,
 *   and today's points. Tap a point to tick it.
 * - Folded: a small tile with just the ring. Tap it to open.
 * Drag it anywhere (by the title, or the folded tile). + adds a point. Switch it off on the My day screen.
 */
class HomeCard(private val service: AccessibilityService, private val handler: Handler) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val prefs = service.getSharedPreferences("planner", Context.MODE_PRIVATE)
    private var card: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var phoneMs: Long? = null
    private var phoneAt = 0L

    /** Your home screen app only (Settings and some other apps also call themselves "home"). */
    private val homePackages: Set<String> get() = setOfNotNull(defaultHome(service))

    private val folded get() = prefs.getBoolean("card_folded", false)

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
        val lp = layoutParams()
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

    /** Redraws the card's contents (after a tick, a new point, a devotion log, folding...). */
    fun redraw() {
        val old = card ?: return
        phoneAt = 0
        val view = build()
        val lp = layoutParams()
        runCatching { wm.removeView(old) }
        runCatching { wm.addView(view, lp) }.onSuccess { card = view; params = lp }.onFailure { card = null }
    }

    // ---- Size and place ----

    private fun screenW() = service.resources.displayMetrics.widthPixels
    private fun screenH() = service.resources.displayMetrics.heightPixels

    /** Open: half the screen wide. Folded: a small tile. */
    private fun width() = if (folded) service.dp(96) else (screenW() - service.dp(Space.L) * 3) / 2

    private fun layoutParams() = WindowManager.LayoutParams(
        width(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Right-hand side under the search bar unless you moved it.
        x = (screenW() - width() - prefs.getInt("card_right", service.dp(Space.L))).coerceIn(0, maxOf(0, screenW() - width()))
        y = prefs.getInt("card_y", service.dp(110)).coerceIn(0, screenH() - service.dp(120))
    }

    // ---- The card ----

    private fun build(): View {
        val c = service
        val today = LocalDate.now()
        val items = PlanStore(c).items(today)
        val done = items.count { it.done }
        // Phone time takes a moment to read: at most once a minute.
        if (SystemClock.elapsedRealtime() - phoneAt > 60_000) {
            phoneMs = if (PhoneUsage.hasAccess(c)) PhoneUsage.days(c, today, today)[today]?.totalMs else null
            phoneAt = SystemClock.elapsedRealtime()
        }

        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(c.getColor(R.color.surface))
                alpha = 245
                cornerRadius = c.dp(22).toFloat()
                setStroke(c.dp(1), c.getColor(R.color.outline))
            }
            elevation = c.dp(6).toFloat()
        }

        if (folded) {
            // The small tile: ring + "My day". Tap to open, drag to move.
            root.gravity = Gravity.CENTER_HORIZONTAL
            root.setPadding(0, c.dp(Space.M), 0, c.dp(Space.M))
            root.addView(Ring(c, done, items.size), LinearLayout.LayoutParams(c.dp(52), c.dp(52)))
            root.addView(label("My day", 12f, R.color.text_secondary).apply { gravity = Gravity.CENTER; setPadding(0, c.dp(Space.XS), 0, 0) })
            dragOrTap(root) { setFolded(false) }
            return root
        }

        root.setPadding(c.dp(Space.M), c.dp(Space.S), c.dp(Space.XS), c.dp(Space.M))

        // Header: title (tap opens My day, drag moves the card), +, fold
        val header = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = label("My day", 15f, R.color.text_primary).apply { typeface = Typeface.DEFAULT_BOLD }
        dragOrTap(title) { open(false) }
        header.addView(title, LinearLayout.LayoutParams(0, c.dp(36), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        (title.layoutParams as LinearLayout.LayoutParams).height = c.dp(36)
        title.gravity = Gravity.CENTER_VERTICAL
        header.addView(icon(R.drawable.w_add) { open(true) })
        header.addView(icon(R.drawable.w_expand_less) { setFolded(true) })
        root.addView(header)

        // Ring + phone time + devotion
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, c.dp(Space.XS), c.dp(Space.S), c.dp(Space.S))
            setOnClickListener { open(false) }
        }
        top.addView(Ring(c, done, items.size), LinearLayout.LayoutParams(c.dp(56), c.dp(56)))
        val facts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(Space.M), 0, 0, 0) }
        phoneMs?.let {
            facts.addView(label(PhoneUsage.format(it), 15f, R.color.text_primary).apply { typeface = Typeface.DEFAULT_BOLD })
            facts.addView(label("on phone", 11f, R.color.text_secondary))
        }
        val devotion = DevotionStore(c)
        val devotionText = when {
            devotion.isDone(LocalDate.now()) -> "Devotion ✓"
            devotion.isMissed(LocalDate.now()) -> "No devotion"
            devotion.isScheduled(LocalDate.now()) -> "Devotion ○"
            else -> null
        }
        devotionText?.let { facts.addView(label(it, 12f, if (it.endsWith("✓")) R.color.accent else R.color.text_secondary).apply { setPadding(0, c.dp(2), 0, 0) }) }
        top.addView(facts)
        root.addView(top)

        // Today's points
        if (items.isEmpty()) root.addView(label("Nothing planned. Tap + to add.", 12f, R.color.text_secondary).apply {
            setPadding(0, 0, c.dp(Space.S), 0)
            setOnClickListener { open(true) }
        })
        for (item in items.take(MAX_ROWS)) root.addView(row(item))
        if (items.size > MAX_ROWS) root.addView(label("+ ${items.size - MAX_ROWS} more", 12f, R.color.accent).apply {
            setPadding(c.dp(28), c.dp(2), 0, 0)
            setOnClickListener { open(false) }
        })
        return root
    }

    private fun label(text: String, size: Float, color: Int) = TextView(service).apply {
        this.text = text
        textSize = size
        setTextColor(service.getColor(color))
    }

    private fun row(item: PlanItem): View {
        val c = service
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, c.dp(5), c.dp(Space.S), c.dp(5))
            addView(ImageView(c).apply {
                setImageResource(if (item.done) R.drawable.w_box_checked else R.drawable.w_box)
            }, LinearLayout.LayoutParams(c.dp(20), c.dp(20)))
            addView(TextView(c).apply {
                text = (item.timeText()?.let { "$it " } ?: "") + item.text
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(c.getColor(R.color.text_primary))
                alpha = if (item.done) 0.5f else 1f
                setPadding(c.dp(Space.S), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { PlanStore(c).toggle(LocalDate.now(), item.id) } // redraws through PlannerWidget.refresh
        }
    }

    private fun icon(res: Int, onTap: () -> Unit) = ImageView(service).apply {
        setImageResource(res)
        val pad = service.dp(7)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(service.dp(34), service.dp(34))
        setOnClickListener { onTap() }
    }

    private fun setFolded(fold: Boolean) {
        prefs.edit().putBoolean("card_folded", fold).apply()
        redraw()
    }

    /** Tap does [onTap]; drag moves the card anywhere on the screen (and it stays there). */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragOrTap(view: View, onTap: () -> Unit) {
        var startX = 0f
        var startY = 0f
        var fromX = 0
        var fromY = 0
        var dragging = false
        view.setOnTouchListener { _, e ->
            val lp = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    fromX = lp.x; fromY = lp.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (abs(dx) > service.dp(8) || abs(dy) > service.dp(8)) dragging = true
                    if (dragging) {
                        lp.x = (fromX + dx.toInt()).coerceIn(0, maxOf(0, screenW() - lp.width))
                        lp.y = (fromY + dy.toInt()).coerceIn(0, screenH() - service.dp(120))
                        card?.let { runCatching { wm.updateViewLayout(it, lp) } }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Remember the place by the card's right edge, so folding and opening keep it on the same side.
                        prefs.edit().putInt("card_right", screenW() - lp.x - lp.width).putInt("card_y", lp.y).apply()
                    } else onTap()
                }
            }
            true
        }
    }

    private fun open(addNow: Boolean) {
        hide()
        service.startActivity(PlannerActivity.intent(service, addNow = addNow))
    }

    /** A ring that fills as points are ticked, with "1/3" in the middle (or "–" when nothing is planned). */
    private class Ring(context: Context, private val done: Int, private val total: Int) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = context.dp(6).toFloat(); color = context.getColor(R.color.outline)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = context.dp(6).toFloat(); strokeCap = Paint.Cap.ROUND
            color = context.getColor(R.color.accent)
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
            textSize = context.dp(14).toFloat(); color = context.getColor(R.color.text_primary)
        }

        override fun onDraw(canvas: Canvas) {
            val pad = track.strokeWidth / 2 + 1
            val box = RectF(pad, pad, width - pad, height - pad)
            canvas.drawArc(box, 0f, 360f, false, track)
            if (total > 0 && done > 0) canvas.drawArc(box, -90f, 360f * done / total, false, fill)
            val label = if (total == 0) "–" else "$done/$total"
            canvas.drawText(label, width / 2f, height / 2f - (text.descent() + text.ascent()) / 2, text)
        }
    }

    companion object {
        private const val MAX_ROWS = 5

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
