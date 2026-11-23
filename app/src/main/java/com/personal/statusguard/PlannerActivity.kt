package com.personal.statusguard

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** My day: the list for today or tomorrow, with optional times. Tick, add, change, delete. */
class PlannerActivity : Activity() {
    private lateinit var plan: PlanStore
    private var date: LocalDate = LocalDate.now()
    private var newTime: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planner)
        plan = PlanStore(this)
        if (intent.getBooleanExtra(EXTRA_TOMORROW, false)) date = LocalDate.now().plusDays(1)

        findViewById<Button>(R.id.todayButton).setOnClickListener { show(LocalDate.now()) }
        findViewById<Button>(R.id.tomorrowButton).setOnClickListener { show(LocalDate.now().plusDays(1)) }
        findViewById<Button>(R.id.timeButton).setOnClickListener { pickTime() }
        findViewById<Button>(R.id.addButton).setOnClickListener { add() }
        findViewById<EditText>(R.id.input).setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) add()
            action == EditorInfo.IME_ACTION_DONE
        }
        findViewById<CheckBox>(R.id.notificationSwitch).apply {
            isChecked = DayNotification.enabled(this@PlannerActivity)
            setOnCheckedChangeListener { _, on -> DayNotification.setEnabled(this@PlannerActivity, on) }
        }
        findViewById<Button>(R.id.leftoverMove).setOnClickListener { plan.moveLeftovers(); refresh() }
        findViewById<Button>(R.id.leftoverDismiss).setOnClickListener { plan.dismissLeftovers(); refresh() }

        show(date)
        if (intent.getBooleanExtra(EXTRA_ADD, false)) {
            findViewById<EditText>(R.id.input).apply {
                requestFocus()
                post { getSystemService(InputMethodManager::class.java).showSoftInput(this, 0) }
            }
        }
    }

    private fun show(day: LocalDate) {
        date = day
        val today = day == LocalDate.now()
        tab(R.id.todayButton, today)
        tab(R.id.tomorrowButton, !today)
        refresh()
    }

    /** The chosen day's tab is filled in. */
    private fun tab(id: Int, selected: Boolean) = findViewById<Button>(id).apply {
        setBackgroundResource(if (selected) R.drawable.button_primary else R.drawable.card_background)
        setTextColor(if (selected) getColor(R.color.on_cover) else getColor(R.color.accent))
        gravity = Gravity.CENTER
    }

    private fun refresh() {
        val today = date == LocalDate.now()
        findViewById<TextView>(R.id.dateText).text = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) +
            if (today) "" else "\nPlan tonight, wake up knowing what to do."

        // Yesterday's unticked points, offered once.
        val left = if (today) plan.leftovers() else emptyList()
        findViewById<View>(R.id.leftoverCard).visibility = if (left.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.leftoverText).text =
            "${left.size} left from yesterday:\n" + left.joinToString("\n") { "• " + it.text }

        val items = plan.items(date)
        findViewById<TextView>(R.id.summaryText).text = plan.summary(date) ?: "Nothing planned yet"
        val list = findViewById<LinearLayout>(R.id.list)
        list.removeAllViews()
        for (item in items) list.addView(row(item))
    }

    private fun row(item: PlanItem) = CheckBox(this).apply {
        text = (item.timeText()?.let { "$it   " } ?: "") + item.text
        isChecked = item.done
        textSize = 16f
        setTextColor(getColor(if (item.done) R.color.text_secondary else R.color.text_primary))
        paintFlags = if (item.done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        setPadding(dp(Space.S), dp(Space.S), 0, dp(Space.S))
        setOnClickListener {
            plan.toggle(date, item.id)
            refresh()
        }
        setOnLongClickListener {
            edit(item)
            true
        }
    }

    private fun add() {
        val input = findViewById<EditText>(R.id.input)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        plan.add(date, text, newTime)
        input.setText("")
        newTime = null
        showTime()
        refresh()
    }

    /** Optional time for the next point. Tapping again when one is set clears it. */
    private fun pickTime() {
        if (newTime != null) {
            newTime = null
            showTime()
            return
        }
        val now = LocalTime.now().plusHours(1)
        TimePickerDialog(this, { _, h, m ->
            newTime = h * 60 + m
            showTime()
        }, now.hour, 0, true).show()
    }

    private fun showTime() {
        findViewById<Button>(R.id.timeButton).text =
            newTime?.let { "At %02d:%02d (tap to remove)".format(it / 60, it % 60) } ?: "Add a time (optional)"
    }

    private fun edit(item: PlanItem) {
        val field = EditText(this).apply { setText(item.text); setSelection(text.length) }
        var time = item.time
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Space.XL), dp(Space.S), dp(Space.XL), 0)
            addView(field)
        }
        val timeButton = Button(this, null, 0, R.style.SecondaryButton).apply {
            fun label() { text = time?.let { "At %02d:%02d (tap to change)".format(it / 60, it % 60) } ?: "Add a time" }
            label()
            setOnClickListener {
                TimePickerDialog(this@PlannerActivity, { _, h, m -> time = h * 60 + m; label() },
                    (time ?: 9 * 60) / 60, (time ?: 0) % 60, true).show()
            }
            setOnLongClickListener { time = null; label(); true }
        }
        box.addView(timeButton)
        AlertDialog.Builder(this)
            .setTitle("Change point")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) plan.update(date, item.copy(text = text, time = time))
                refresh()
            }
            .setNeutralButton("Delete") { _, _ -> plan.delete(date, item.id); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val EXTRA_TOMORROW = "tomorrow"
        private const val EXTRA_ADD = "add"

        fun intent(context: Context, tomorrow: Boolean = false, addNow: Boolean = false) =
            Intent(context, PlannerActivity::class.java)
                .putExtra(EXTRA_TOMORROW, tomorrow)
                .putExtra(EXTRA_ADD, addNow)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
