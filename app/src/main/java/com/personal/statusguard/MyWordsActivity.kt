package com.personal.statusguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Your whys, your quotes, and your intention for the day. No password needed. */
class MyWordsActivity : Activity() {
    private lateinit var words: Words

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_words)
        words = Words(this)

        findViewById<Button>(R.id.intentionButton).setOnClickListener {
            startActivity(PlannerActivity.intent(this, tomorrow = true))
        }
        findViewById<Button>(R.id.addWhyButton).setOnClickListener {
            add(R.id.whyInput) { words.whys = words.whys + it }
        }
        findViewById<Button>(R.id.addQuoteButton).setOnClickListener {
            add(R.id.quoteInput) { words.quotes = words.quotes + it }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun add(inputId: Int, save: (String) -> Unit) {
        val input = findViewById<EditText>(inputId)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        save(text)
        input.setText("")
        refresh()
    }

    private fun refresh() {
        val intention = words.todaysIntention()
        findViewById<TextView>(R.id.intentionText).text = intention?.let { "Today: $it" } ?: "Nothing written for today."
        findViewById<Button>(R.id.intentionButton).text = "Plan tomorrow (My day)"

        fill(R.id.whyList, words.whys) { item -> words.whys = words.whys - item }
        fill(R.id.quoteList, words.quotes) { item -> words.quotes = words.quotes - item }
    }

    /** One row per item, with a close icon to remove it. */
    private fun fill(listId: Int, items: List<String>, remove: (String) -> Unit) {
        val list = findViewById<LinearLayout>(listId)
        list.removeAllViews()
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "“$item”"
                textSize = 16f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_secondary))
                setBackgroundResource(android.R.drawable.list_selector_background)
                contentDescription = "Remove"
                setPadding(dp(Space.M), dp(Space.M), dp(Space.M), dp(Space.M))
                setOnClickListener {
                    remove(item)
                    refresh()
                }
            })
            list.addView(row)
        }
    }
}
