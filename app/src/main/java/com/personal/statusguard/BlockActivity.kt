package com.personal.statusguard

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

/** The calm lockout screen that covers WhatsApp, with a random encouragement and a countdown. */
class BlockActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var countdownText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val updateCountdown = object : Runnable {
        override fun run() {
            val secondsLeft = (prefs.lockedUntil - System.currentTimeMillis()) / 1000
            if (secondsLeft <= 0) {
                countdownText.text = "WhatsApp is open again.\nStatus rules still apply."
                return
            }
            countdownText.text = "WhatsApp unlocks in %d:%02d".format(secondsLeft / 60, secondsLeft % 60)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block)
        prefs = Prefs(this)
        countdownText = findViewById(R.id.countdownText)

        // A new encouragement each time the screen appears.
        val (quote, source) = Encouragements.pick(this)
        findViewById<TextView>(R.id.headingText).text = Encouragements.headings.random()
        findViewById<TextView>(R.id.quoteText).text = "“$quote”"
        findViewById<TextView>(R.id.sourceText).text = "— $source"

        findViewById<Button>(R.id.okButton).setOnClickListener { goHome() }
        // Going back would land on WhatsApp again, so back goes home too.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goHome() }
        }
    }

    @Deprecated("Used on Android 12 only")
    override fun onBackPressed() = goHome()

    override fun onResume() {
        super.onResume()
        val tries = History(this).get(java.time.LocalDate.now(), History.STATUS_ATTEMPTS)
        findViewById<TextView>(R.id.reasonText).text = "${prefs.lockReason}\nThis is your ${ordinal(maxOf(1, tries))} try today."
        handler.post(updateCountdown)
    }

    override fun onPause() {
        handler.removeCallbacks(updateCountdown)
        super.onPause()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
