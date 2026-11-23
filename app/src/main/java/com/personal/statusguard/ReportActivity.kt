package com.personal.statusguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** The weekly report screen. Built in code from the shared styles: a column of cards. */
class ReportActivity : Activity() {
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this, null, 0, R.style.Page).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        column.removeAllViews()
        val r = WeeklyReport.build(this)
        val dayLabels = r.days.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2) }

        column.addView(text(R.style.Title, "Your week"))
        column.addView(text(R.style.Subtitle, r.rangeText))

        card("Grade", R.drawable.ic_chart, first = true).apply {
            addView(text(R.style.Display, r.grade))
            addView(text(R.style.Body, r.gradeComment))
        }

        card("My streaks", R.drawable.ic_flame).apply {
            addView(streakBlock(this@ReportActivity))
            addView(text(R.style.Caption, if (r.social.forceOpens > 0)
                "${r.social.forceOpens} force opens this week. Each one reset the social streak."
            else "No force opens this week.").apply {
                setPadding(0, dp(Space.M), 0, 0)
                if (r.social.forceOpens > 0) icon(R.drawable.ic_warning, R.color.warning)
            })
        }

        card("My week in plain words", R.drawable.ic_edit).apply {
            r.insights().forEach { (iconRes, topic, story) ->
                addView(text(R.style.Body, topic).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    icon(iconRes, R.color.accent)
                    setPadding(0, dp(Space.S), 0, 0)
                })
                addView(text(R.style.Body, story).apply { setPadding(dp(28), 0, 0, 0) })
            }
        }

        if (!r.hasPhoneData) {
            card("Phone time", R.drawable.ic_phone).apply {
                addView(text(R.style.Body, "Allow \"Usage access\" for Status Guard to see your daily phone time and top apps."))
                addView(button(R.style.PrimaryButton, "Allow usage access") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
            }
        } else {
            card("Phone time", R.drawable.ic_phone).apply {
                addView(text(R.style.Title, PhoneUsage.format(r.phoneTotal)))
                addView(BarChart(this@ReportActivity, r.phonePerDay.map { it / 3_600_000f }, dayLabels) { "%.1fh".format(it) })
            }
            if (r.topApps.isNotEmpty()) {
                card("Top apps", R.drawable.ic_chart).apply {
                    r.topApps.forEachIndexed { i, (name, ms) -> addView(text(R.style.Body, "${i + 1}.  $name  ·  ${PhoneUsage.format(ms)}")) }
                }
            }
        }
        card("Status minutes", R.drawable.ic_chat).apply {
            addView(BarChart(this@ReportActivity, r.statusMinutesPerDay.map { it.toFloat() }, dayLabels) { "${it.toInt()}m" })
        }
        if (r.social.hasData) {
            val s = r.social
            card("Social media", R.drawable.ic_play).apply {
                addView(text(R.style.Title, formatSeconds(s.totalSeconds)))
                addView(text(R.style.Caption, "About ${formatSeconds(s.totalSeconds / 7)} a day" +
                    (if (s.enabled) "  ·  limit ${s.limitMinutes} min/day  ·  allowed ${s.windows}" else "  ·  blocking is off")))
                addView(BarChart(this@ReportActivity, s.secondsPerDay.map { it / 60f }, dayLabels) { "${it.toInt()}m" })
                s.apps.forEach { a ->
                    addView(text(R.style.Body, "${a.app.label}: ${formatSeconds(a.totalSeconds)}").apply {
                        icon(a.app.icon, R.color.accent)
                        setPadding(0, dp(Space.M), 0, 0)
                    })
                    addView(text(R.style.Caption, "${a.tries} blocked tries  ·  ${a.forceOpens} force opens  ·  ${formatSeconds(a.totalSeconds / 7)} a day")
                        .apply { setPadding(dp(28), 0, 0, 0) })
                }
                addView(text(R.style.Body, "Total: ${s.tries} blocked tries  ·  ${s.forceOpens} force opens" +
                    (if (s.fullDays > 0) "  ·  limit reached on ${s.fullDays} days" else "")).apply {
                    setPadding(0, dp(Space.L), 0, 0)
                    setTypeface(typeface, Typeface.BOLD)
                })
                s.peakHour?.let { addView(text(R.style.Caption, "Most tries around %02d:00.".format(it))) }
            }
            if (s.forceOpens > 0) {
                card("Force opens", R.drawable.ic_warning).apply {
                    addView(BarChart(this@ReportActivity, s.forcePerDay.map { it.toFloat() }, dayLabels) { "${it.toInt()}" })
                }
            }
        }
        if (r.mornings.count > 0) {
            val m = r.mornings
            card("My mornings", R.drawable.ic_sun).apply {
                addView(text(R.style.Title, "${m.pushups} push-ups  ·  ${m.jogged} jog steps"))
                addView(text(R.style.Caption,
                    "${m.walked} steps walked outside  ·  ${m.checks} of ${m.checks + m.missed} awake checks answered" +
                        (m.averageUp?.let { "  ·  up in $it min on average" } ?: "")))
                for (x in m.mornings) {
                    val day = x.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d"))
                    val did = listOfNotNull(
                        x.upMinutes?.let { "up in $it min" },
                        x.walked.takeIf { it > 0 }?.let { "walked $it" },
                        x.pushups.takeIf { it > 0 }?.let { "push-ups ${x.pushupsText}" },
                        x.jogged.takeIf { it > 0 }?.let { "jogged $it" },
                        x.pledges.takeIf { it > 0 }?.let { "pledge" },
                    ).joinToString(" · ")
                    addView(text(R.style.Body, "$day · Level ${x.level} at ${x.rang} · ${x.resultText}\n$did").apply {
                        setPadding(0, dp(Space.M), 0, 0)
                        if (!x.finished) icon(R.drawable.ic_warning, R.color.warning)
                    })
                }
            }
        }
        if (r.hasSleepSchedule) {
            card("Sleep", R.drawable.ic_moon).apply {
                addView(text(R.style.Title, if (r.sleepNights > 0) "${PhoneUsage.format(r.sleepAverage)} a night" else "No sleep counted yet"))
                addView(text(R.style.Caption, "Phone untouched during sleep time  ·  ${r.sleepNights} nights counted"))
                addView(BarChart(this@ReportActivity, r.sleepMsPerDay.map { it / 3_600_000f }, dayLabels) { "%.1fh".format(it) })
                addView(text(R.style.Body, "${r.sleepAttempts} tries during sleep time  ·  ${r.sleepEmergency} force bypasses").apply {
                    setPadding(0, dp(Space.M), 0, 0)
                    if (r.sleepEmergency > 0) icon(R.drawable.ic_warning, R.color.warning)
                })
            }
        }
        card("Blocked tries (all)", R.drawable.ic_block).apply {
            addView(BarChart(this@ReportActivity, r.attemptsPerDay.map { it.toFloat() }, dayLabels) { "${it.toInt()}" })
        }

        // ---- Share ----
        card("Share", R.drawable.ic_share).apply {
            val email = EmailSettings(this@ReportActivity)
            addView(text(R.style.Caption, if (email.isReady) "Auto-email is on: every ${dayName(email.day)} to ${email.recipients}." else "Auto-email is off."))
            addView(button(R.style.PrimaryButton, "Save PDF to Downloads", R.drawable.ic_download) { savePdf(r) })
            addView(button(R.style.PrimaryButton, "Share PDF (WhatsApp, email…)", R.drawable.ic_share) { sharePdf(r) })
            addView(button(R.style.LockedLinkButton, "Auto-email settings") {
                val prefs = Prefs(this@ReportActivity)
                val open = { startActivity(Intent(this@ReportActivity, EmailSettingsActivity::class.java)) }
                if (prefs.hasPassword()) askPassword(this@ReportActivity, "Enter password") { open() } else open()
            })
        }
    }

    private fun savePdf(r: WeeklyReport) {
        val uri = PdfReport.saveToDownloads(this, PdfReport.create(this, r))
        Toast.makeText(this, "Saved to Downloads as ${PdfReport.fileName(r)}", Toast.LENGTH_LONG).show()
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Manual sharing: pick WhatsApp, email or anything else. */
    private fun sharePdf(r: WeeklyReport) {
        val uri = PdfReport.saveToDownloads(this, PdfReport.create(this, r))
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, r.messageText())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share report"))
    }

    // ---- Small helpers to build the screen from the shared styles ----

    private fun dayName(day: Int) = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())

    private fun text(style: Int, s: String) = TextView(this, null, 0, style).apply { text = s }

    private fun button(style: Int, label: String, iconRes: Int = 0, action: () -> Unit) = Button(this, null, 0, style).apply {
        text = label
        if (iconRes != 0) setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            if (style == R.style.PrimaryButton) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(Space.M) }
    }

    /** A card with an icon heading, added to the page. Same look as the cards in the layouts. */
    private fun card(title: String, iconRes: Int, first: Boolean = false): LinearLayout {
        val card = LinearLayout(this, null, 0, R.style.Card).apply { orientation = LinearLayout.VERTICAL }
        card.addView(text(R.style.Heading, title).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        })
        column.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(if (first) Space.XL else Space.M)
        })
        return card
    }
}

/** A simple bar chart: one bar per day, value on top, day below. */
class BarChart(
    context: Context,
    private val values: List<Float>,
    private val labels: List<String>,
    private val format: (Float) -> String,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * density
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.text_secondary)
    }

    init {
        minimumHeight = (160 * density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, minimumHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1f
        val slot = width / values.size.toFloat()
        val top = 18 * density
        val bottom = height - 18 * density
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            val h = (bottom - top) * (v / max)
            canvas.drawRoundRect(RectF(cx - slot * 0.3f, bottom - h, cx + slot * 0.3f, bottom), 6 * density, 6 * density, bar)
            if (v > 0) canvas.drawText(format(v), cx, bottom - h - 4 * density, label)
            canvas.drawText(labels[i], cx, height - 4 * density, label)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), minimumHeight)
}
