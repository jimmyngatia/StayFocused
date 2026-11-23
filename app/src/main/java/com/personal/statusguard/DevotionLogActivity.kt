package com.personal.statusguard

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** "What did you read today?" Asked after devotion time until answered; also opened from the home screen. */
class DevotionLogActivity : Activity() {
    private lateinit var store: DevotionStore
    private val today = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devotion_log)
        store = DevotionStore(this)

        val block = store.block
        findViewById<TextView>(R.id.dateText).text = today.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) +
            (if (block != null && store.isScheduled(today)) "  ·  devotion time ${block.startText()}–${block.endText()}" else "") +
            when {
                store.isDone(today) -> "\nAlready logged today. Saving again replaces it."
                store.isMissed(today) -> "\nYou said you didn't today. Did it after all? Write it here."
                else -> ""
            }
        val input = findViewById<EditText>(R.id.input).apply { setText(store.entry(today) ?: "") }
        findViewById<TextView>(R.id.streakText).text = "Devotion streak: ${store.streak()} ${if (store.streak() == 1) "day" else "days"}"

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Write what you read, e.g. Deuteronomy 2–3.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            store.logDone(today, text)
            DevotionStore.cancelAsk(this)
            PlannerWidget.refresh(this)
            Toast.makeText(this, "Saved. Devotion streak: ${store.streak()}", Toast.LENGTH_LONG).show()
            finish()
        }
        findViewById<Button>(R.id.notDoneButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("No devotions today?")
                .setMessage("Thank you for being honest. It resets your devotion streak and breaks today's general streak, " +
                    "and it shows in your weekly report. If you do them later today, come back and write what you read.")
                .setPositiveButton("Yes, I didn't") { _, _ ->
                    store.logNotDone(today)
                    DevotionStore.cancelAsk(this)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        // "Not yet" only makes sense while it's still asking.
        findViewById<Button>(R.id.laterButton).apply {
            visibility = if (store.needsAnswer()) View.VISIBLE else View.GONE
            setOnClickListener {
                store.promptedAt = System.currentTimeMillis()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isOpen = true
    }

    override fun onPause() {
        isOpen = false
        super.onPause()
    }

    companion object {
        var isOpen = false
            private set
    }
}
