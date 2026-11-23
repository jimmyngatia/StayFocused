package com.personal.statusguard

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Turns a [WeeklyReport] into a colourful, easy-to-read A4 PDF. */
object PdfReport {
    fun fileName(r: WeeklyReport) = "StatusGuard-Report-${r.days.last()}.pdf"

    /** Creates the PDF in the app's private folder and returns it. */
    fun create(context: Context, r: WeeklyReport): File {
        val doc = PdfDocument()
        val w = PdfWriter(doc)

        w.header(if (r.name == "The user") "Weekly report" else "Weekly report: ${r.name}", r.rangeText, r.grade)

        // Summary: grade comment + four numbers.
        w.paragraph(plain(r.gradeComment), 15f, Ink.TEXT, bold = true)
        w.space(10f)
        w.tiles(
            listOf(
                Tile(if (r.hasPhoneData) PhoneUsage.format(r.phoneTotal) else "–", "Phone time", Ink.GREEN),
                Tile(if (r.hasPhoneData) PhoneUsage.format(r.phoneTotal / 7) else "–", "Per day", Ink.TEAL),
                Tile("${r.statusTotal} min", "Status watched", Ink.BLUE),
                Tile("${r.attemptsTotal}", "Blocked attempts", if (r.attemptsTotal == 0L) Ink.GREEN else Ink.ORANGE),
            )
        )
        if (r.social.hasData) {
            w.space(10f)
            w.tiles(
                listOf(
                    Tile(formatSeconds(r.social.totalSeconds), "Social media time", Ink.PURPLE),
                    Tile(formatSeconds(r.social.totalSeconds / 7), "Social per day", Ink.PURPLE),
                    Tile("${r.social.tries}", "Social blocked tries", if (r.social.tries == 0L) Ink.GREEN else Ink.ORANGE),
                    Tile("${r.social.forceOpens}", "Force opens", if (r.social.forceOpens == 0L) Ink.GREEN else Ink.RED),
                )
            )
        }

        // Streaks, big and early.
        w.section("My streaks")
        w.tiles(
            listOf(
                Tile("${r.overallStreak} days", "In a row with every rule kept. Blocked tries don't count; force opens and shortcuts do.", Ink.GREEN),
                Tile("${r.cleanDays} of 7", "Days this week with every rule kept", Ink.TEAL),
            ),
            big = true,
        )
        w.space(10f)
        w.tiles(
            listOf(
                Tile("${r.statusStreak}", "Status: days (halved by force opens)", Ink.PURPLE),
                Tile("${r.socialStreak}", "Social media: days (halved by force opens)", Ink.PURPLE),
                Tile("${r.alarmStreak}", "Mornings in a row", Ink.PURPLE),
                Tile("${r.sleepStreak}", "Sleep: days without tries", Ink.PURPLE),
            ),
            big = true,
        )
        w.paragraph(
            if (r.social.forceOpens > 0) "${r.social.forceOpens} force opens this week. Each one cut every streak in half."
            else "No force opens this week, so no streak was cut.",
            9.5f, Ink.MUTED,
        )

        w.section("My week in plain words")
        for ((topic, text) in r.story()) {
            w.paragraph(topic, 11f, Ink.GREEN, bold = true)
            w.paragraph(text, 11.5f, Ink.TEXT)
            w.space(8f)
        }

        val labels = r.days.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        if (r.hasPhoneData) {
            w.section("Phone time per day (hours)")
            w.barChart(r.phonePerDay.map { it / 3_600_000f }, labels, Ink.GREEN) { "%.1f".format(it) }
            if (r.topApps.isNotEmpty()) {
                w.section("Where my phone time went")
                w.appBars(r.topApps, r.phoneTotal)
            }
        }

        if (r.social.hasData) {
            val sw = r.social
            w.section("Social media: TikTok, YouTube Shorts, Instagram")
            w.paragraph(
                if (sw.enabled) "Shared limit: ${sw.limitMinutes} min a day. Allowed: ${sw.windows}. Pot used up on ${sw.fullDays} of 7 days." +
                    (sw.peakHour?.let { " Most tries around %02d:00.".format(it) } ?: "")
                else "Social media blocking is off. Time is still counted.",
                10.5f, Ink.MUTED,
            )
            w.space(6f)
            w.barChart(sw.secondsPerDay.map { it / 60f }, labels, Ink.PURPLE) { "${it.toInt()}m" }
            w.space(6f)
            w.table(
                listOf("App", "Time", "Per day", "Blocked tries", "Force opens"),
                sw.apps.map { a ->
                    listOf(a.app.label, formatSeconds(a.totalSeconds), formatSeconds(a.totalSeconds / 7), "${a.tries}", "${a.forceOpens}")
                } + listOf(listOf("Total", formatSeconds(sw.totalSeconds), formatSeconds(sw.totalSeconds / 7), "${sw.tries}", "${sw.forceOpens}"))
            )
        }

        w.section("Day by day")
        w.table(
            listOf("Day", "Phone", "Status", "Social", "Sleep", "Tries", "Forced"),
            r.days.indices.map { i ->
                listOf(
                    r.dayName(i),
                    if (r.hasPhoneData) PhoneUsage.format(r.phonePerDay[i]) else "–",
                    "${r.statusMinutesPerDay[i]}m",
                    formatSeconds(r.social.secondsPerDay[i]),
                    if (r.sleepMsPerDay[i] >= 60_000) PhoneUsage.format(r.sleepMsPerDay[i]) else "–",
                    "${r.attemptsPerDay[i]}",
                    "${r.social.forcePerDay[i] + r.sleepBypassPerDay[i]}",
                )
            }
        )
        w.paragraph("Tries = blocked attempts for status, sleep time and social media together. Forced = social media force opens + sleep force bypasses. Sleep = the night that ended that morning.", 8.5f, Ink.MUTED)

        if (r.hasSleepSchedule) {
            w.section("Sleep (phone untouched)")
            w.paragraph(
                if (r.sleepNights > 0) "Average ${PhoneUsage.format(r.sleepAverage)} a night over ${r.sleepNights} nights. Each bar is the night that ended that morning."
                else "No sleep counted this week.",
                10.5f, Ink.MUTED,
            )
            w.space(6f)
            w.barChart(r.sleepMsPerDay.map { it / 3_600_000f }, labels, Ink.BLUE) { "%.1f".format(it) }
        }

        w.section("Mornings and sleep")
        w.tiles(
            listOf(
                Tile("${r.alarmsDone}", "Alarms finished", Ink.GREEN),
                Tile("${r.backToSleepDays}", "Days I went back to sleep", if (r.backToSleepDays == 0) Ink.GREEN else Ink.RED),
                Tile("${r.alarmsStopped + r.alarmsEscaped}", "Alarm shortcuts", if (r.alarmsStopped + r.alarmsEscaped == 0L) Ink.GREEN else Ink.RED),
            )
        )
        w.space(10f)
        w.tiles(
            listOf(
                Tile("${r.alarmsPaused}", "Alarms switched off for a day", if (r.alarmsPaused == 0L) Ink.GREEN else Ink.ORANGE),
                Tile("${r.sleepAttempts}", "Sleep-time attempts", if (r.sleepAttempts == 0L) Ink.GREEN else Ink.ORANGE),
                Tile("${r.sleepEmergency}", "Sleep force bypasses", if (r.sleepEmergency == 0L) Ink.GREEN else Ink.RED),
            )
        )

        // What I actually did each morning, from the alarm log.
        val m = r.mornings
        if (m.count > 0) {
            w.section("My mornings")
            w.tiles(
                listOf(
                    Tile(m.averageUp?.let { "${it} min" } ?: "–", "Average time from alarm to up", Ink.TEAL),
                    Tile("${m.pushups}", "Push-ups" + if (m.cameraPushups > 0) " (${m.cameraPushups} on camera)" else "", Ink.GREEN),
                    Tile("${m.jogged}", "Jogging steps", Ink.GREEN),
                    Tile("${m.walked}", "Steps walked outside", Ink.GREEN),
                )
            )
            w.space(10f)
            w.tiles(
                listOf(
                    Tile("${m.finished} of ${m.count}", "Mornings finished", if (m.finished == m.count) Ink.GREEN else Ink.ORANGE),
                    Tile("${m.checks} of ${m.checks + m.missed}", "Awake checks answered", if (m.missed == 0) Ink.GREEN else Ink.RED),
                    Tile("${m.pledges}", "Pledges typed", Ink.PURPLE),
                )
            )
            w.space(10f)
            w.table(
                listOf("Day", "Alarm", "Up in", "Walk", "Push-ups", "Jog", "Checks", "Result"),
                m.mornings.map { x ->
                    listOf(
                        x.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d")),
                        "L${x.level} ${x.rang}",
                        x.upMinutes?.let { "${it} min" } ?: "–",
                        if (x.walked > 0) "${x.walked}" else "–",
                        x.pushupsText,
                        if (x.jogged > 0) "${x.jogged}" else "–",
                        if (x.checks + x.missed > 0) "${x.checks}/${x.checks + x.missed}" else "–",
                        x.resultShort,
                    )
                },
                weights = listOf(0.9f, 1.1f, 0.8f, 0.7f, 1.4f, 0.7f, 0.8f, 1.4f),
                textSize = 9f,
            )
            w.paragraph("Up in = minutes from the alarm to scanning the first code. L4 = level 4. Checks = awake checks answered / asked. Camera = push-ups watched by the camera; dark = counted by the sensor because it was too dark.", 8.5f, Ink.MUTED)
        }

        w.finish()
        val file = File(context.cacheDir, "reports/${fileName(r)}")
        file.parentFile?.mkdirs()
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** Copies the PDF into the phone's Downloads folder (replacing an older copy) and returns its address. */
    fun saveToDownloads(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(file.name)
        )
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        return uri
    }

    /** PDFs don't show emojis reliably, so they're left out. */
    private fun plain(s: String) = s.replace(Regex("[\\p{So}\\p{Cs}\\uFE0F]"), "").trim()
}

/** The PDF's colours. */
private object Ink {
    val GREEN = Color.parseColor("#1B8F4C")
    val TEAL = Color.parseColor("#0F766E")
    val BLUE = Color.parseColor("#2563EB")
    val ORANGE = Color.parseColor("#D97706")
    val RED = Color.parseColor("#DC2626")
    val PURPLE = Color.parseColor("#7C3AED")
    val TEXT = Color.parseColor("#10201A")
    val MUTED = Color.parseColor("#5E6B66")
    val LINE = Color.parseColor("#E2E8E5")
    val SOFT = Color.parseColor("#F4F6F5")

    fun grade(g: String) = when (g) {
        "A" -> GREEN
        "B" -> TEAL
        "C" -> ORANGE
        "D" -> Color.parseColor("#EA580C")
        else -> RED
    }

    /** A pale version of [color] for backgrounds. */
    fun pale(color: Int) = Color.argb(28, Color.red(color), Color.green(color), Color.blue(color))
}

private class Tile(val value: String, val label: String, val color: Int)

/** Draws onto A4 pages from top to bottom, starting a new page when one is full. */
private class PdfWriter(private val doc: PdfDocument) {
    private val width = 595f
    private val height = 842f
    private val margin = 40f
    private val contentWidth = width - 2 * margin
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = 0f
    private var pageNumber = 0

    private fun paint(color: Int, size: Float = 11f, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun newPage() {
        page?.let { endPage(it) }
        pageNumber++
        page = doc.startPage(PdfDocument.PageInfo.Builder(width.toInt(), height.toInt(), pageNumber).create())
        canvas = page!!.canvas
        y = margin
    }

    private fun endPage(p: PdfDocument.Page) {
        val footer = paint(Ink.MUTED, 8.5f)
        val made = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
        canvas.drawLine(margin, height - 34f, width - margin, height - 34f, Paint().apply { color = Ink.LINE })
        canvas.drawText("Status Guard · made $made", margin, height - 20f, footer)
        footer.textAlign = Paint.Align.RIGHT
        canvas.drawText("Page $pageNumber", width - margin, height - 20f, footer)
        doc.finishPage(p)
    }

    /** Makes sure [space] points fit on this page, otherwise moves to a new one. */
    private fun ensure(space: Float) {
        if (page == null || y + space > height - 50f) newPage()
    }

    fun finish() {
        page?.let { endPage(it) }
        page = null
    }

    fun space(points: Float) {
        y += points
    }

    /** Green band at the top with the title and a round grade badge. */
    fun header(title: String, subtitle: String, grade: String) {
        newPage()
        canvas.drawRect(0f, 0f, width, 120f, Paint().apply { color = Ink.GREEN })
        canvas.drawText("STATUS GUARD", margin, 42f, paint(Color.argb(200, 255, 255, 255), 10f, bold = true).apply { letterSpacing = 0.15f })
        canvas.drawText(title, margin, 72f, paint(Color.WHITE, 26f, bold = true))
        canvas.drawText(subtitle, margin, 96f, paint(Color.argb(220, 255, 255, 255), 12f))

        val cx = width - margin - 38f
        canvas.drawCircle(cx, 60f, 38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val letter = paint(Ink.grade(grade), 36f, bold = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(grade, cx, 73f, letter)
        canvas.drawText("GRADE", cx, 112f, paint(Color.WHITE, 8f, bold = true).apply { textAlign = Paint.Align.CENTER })
        y = 150f
    }

    /** A section title with a coloured bar in front. */
    fun section(title: String) {
        ensure(60f)
        y += 18f
        canvas.drawRoundRect(RectF(margin, y, margin + 4f, y + 18f), 2f, 2f, Paint().apply { color = Ink.GREEN })
        canvas.drawText(title, margin + 12f, y + 14f, paint(Ink.TEXT, 14f, bold = true))
        y += 30f
    }

    /** Wrapped text. */
    fun paragraph(text: String, size: Float, color: Int, bold: Boolean = false) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint(color, size, bold), contentWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .build()
        ensure(layout.height.toFloat() + 4f)
        canvas.save()
        canvas.translate(margin, y)
        layout.draw(canvas)
        canvas.restore()
        y += layout.height + 4f
    }

    /** A row of coloured boxes, each with a big number and a label. */
    fun tiles(tiles: List<Tile>, big: Boolean = false) {
        val gap = 10f
        val w = (contentWidth - gap * (tiles.size - 1)) / tiles.size
        val h = if (big) 84f else 64f
        val valueSize = if (big) 30f else 18f
        val labelTop = if (big) 52f else 38f
        ensure(h)
        tiles.forEachIndexed { i, t ->
            val x = margin + i * (w + gap)
            canvas.drawRoundRect(RectF(x, y, x + w, y + h), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.pale(t.color) })
            canvas.drawText(t.value, x + 12f, y + (if (big) 40f else 30f), paint(t.color, valueSize, bold = true))
            val label = StaticLayout.Builder.obtain(t.label, 0, t.label.length, paint(Ink.MUTED, 8.5f), (w - 20f).toInt()).build()
            canvas.save()
            canvas.translate(x + 12f, y + labelTop)
            label.draw(canvas)
            canvas.restore()
        }
        y += h + 4f
    }

    /** Vertical bars, one per day, with the value on top. */
    fun barChart(values: List<Float>, labels: List<String>, color: Int, format: (Float) -> String) {
        val h = 150f
        ensure(h + 10f)
        val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1f
        val slot = contentWidth / values.size
        val bottom = y + h - 18f
        val top = y + 16f
        canvas.drawLine(margin, bottom, margin + contentWidth, bottom, Paint().apply { this.color = Ink.LINE })
        val valuePaint = paint(Ink.TEXT, 9f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val labelPaint = paint(Ink.MUTED, 9f).apply { textAlign = Paint.Align.CENTER }
        val heaviest = values.indices.maxByOrNull { values[it] }
        values.forEachIndexed { i, v ->
            val cx = margin + slot * i + slot / 2
            val barTop = bottom - (bottom - top) * (v / max)
            val barColor = if (i == heaviest && v > 0) Ink.ORANGE else color
            canvas.drawRoundRect(RectF(cx - slot * 0.28f, barTop, cx + slot * 0.28f, bottom), 5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = barColor })
            canvas.drawText(format(v), cx, barTop - 4f, valuePaint)
            canvas.drawText(labels[i], cx, bottom + 13f, labelPaint)
        }
        y += h + 4f
        paragraph("Orange = my heaviest day.", 8.5f, Ink.MUTED)
    }

    /** Horizontal bars for the top apps, with time and share of phone time. */
    fun appBars(apps: List<Pair<String, Long>>, total: Long) {
        val colors = listOf(Ink.GREEN, Ink.TEAL, Ink.BLUE, Ink.PURPLE, Ink.ORANGE)
        val max = apps.maxOf { it.second }.coerceAtLeast(1)
        val nameWidth = 130f
        val barMax = contentWidth - nameWidth - 110f
        apps.forEachIndexed { i, (name, ms) ->
            ensure(24f)
            canvas.drawText(name.take(22), margin, y + 13f, paint(Ink.TEXT, 10.5f))
            val barW = barMax * ms / max
            canvas.drawRoundRect(RectF(margin + nameWidth, y + 3f, margin + nameWidth + barW, y + 16f), 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[i % colors.size] })
            canvas.drawText("${PhoneUsage.format(ms)} · ${ms * 100 / maxOf(1, total)}%", margin + nameWidth + barW + 8f, y + 13f, paint(Ink.MUTED, 10f))
            y += 24f
        }
    }

    /** A table with a green header row and striped rows. */
    /** A striped table. [weights] makes some columns wider than others (equal if null). */
    fun table(headers: List<String>, rows: List<List<String>>, weights: List<Float>? = null, textSize: Float = 10f) {
        val rowH = 22f
        val w = weights ?: headers.map { 1f }
        val unit = contentWidth / w.sum()
        val x = w.runningFold(0f) { acc, it -> acc + it * unit }
        val pad = if (headers.size > 6) 6f else 10f
        ensure(rowH * (rows.size + 1))
        canvas.drawRoundRect(RectF(margin, y, margin + contentWidth, y + rowH), 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.GREEN })
        headers.forEachIndexed { c, text -> canvas.drawText(text, margin + x[c] + pad, y + 15f, paint(Color.WHITE, textSize, bold = true)) }
        y += rowH
        rows.forEachIndexed { r, row ->
            if (r % 2 == 1) canvas.drawRect(margin, y, margin + contentWidth, y + rowH, Paint().apply { color = Ink.SOFT })
            row.forEachIndexed { c, text -> canvas.drawText(text, margin + x[c] + pad, y + 15f, paint(Ink.TEXT, textSize, bold = c == 0)) }
            y += rowH
        }
        canvas.drawLine(margin, y, margin + contentWidth, y, Paint().apply { color = Ink.LINE })
    }
}
