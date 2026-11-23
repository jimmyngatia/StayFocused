package com.personal.statusguard

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

/** A short breathing pause before status opens, even when status is allowed. */
class PauseActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var secondsLeft = 0
    private var chose = false

    private val tick = object : Runnable {
        override fun run() {
            val count = findViewById<TextView>(R.id.countText)
            val cont = findViewById<Button>(R.id.continueButton)
            if (secondsLeft > 0) {
                count.text = secondsLeft.toString()
                cont.text = "Continue to status in $secondsLeft…"
                cont.alpha = 0.5f
                secondsLeft--
                handler.postDelayed(this, 1000)
            } else {
                count.text = "✓"
                cont.text = "Continue to status"
                cont.isEnabled = true
                cont.alpha = 1f
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isOpen = true
        setContentView(R.layout.activity_pause)
        val prefs = Prefs(this)
        secondsLeft = prefs.pauseSeconds

        val (quote, source) = Encouragements.pick(this)
        findViewById<TextView>(R.id.quoteText).text = "“$quote”\n— $source"
        findViewById<TextView>(R.id.usageText).text =
            "Status today: ${prefs.usedSecondsToday() / 60} of ${prefs.todaysLimitMinutes()} min used"

        findViewById<Button>(R.id.neverMindButton).setOnClickListener { neverMind() }
        findViewById<Button>(R.id.continueButton).apply {
            isEnabled = false
            setOnClickListener {
                chose = true
                StatusGuardService.grantStatusPass()
                finish()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { neverMind() }
        }
        handler.post(tick)
    }

    @Deprecated("Used on Android 12 only")
    override fun onBackPressed() = neverMind()

    /** Chose not to: WhatsApp goes back to Chats, and it counts as a win. */
    private fun neverMind() {
        if (chose) return
        chose = true
        History(this).add(History.RESISTED)
        StatusGuardService.exitStatusRequested = true
        finish()
    }

    /** Leaving without choosing (e.g. Home) closes the pause; it shows again next time. */
    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        isOpen = false
        super.onDestroy()
    }

    companion object {
        var isOpen = false
            private set
    }
}
