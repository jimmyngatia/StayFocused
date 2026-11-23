package com.personal.statusguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.time.LocalDate

/**
 * Covers WhatsApp during a focus time once the quick check is used up.
 * "Open anyway" waits a little (longer each time), then opens WhatsApp for a few minutes.
 */
class FocusBlockActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: FocusStore
    private var pkg = "com.whatsapp"
    private var waitLeft = 0

    private val countdown = object : Runnable {
        override fun run() {
            val button = findViewById<Button>(R.id.extraConfirmButton)
            if (waitLeft > 0) {
                button.isEnabled = false
                button.text = "Wait $waitLeft s…"
                waitLeft--
                handler.postDelayed(this, 1000)
            } else {
                button.isEnabled = true
                button.text = "Open WhatsApp for ${store.extraMinutes} min"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_focus_block)
        store = FocusStore(this)
        findViewById<Button>(R.id.okButton).setOnClickListener { goHome() }
        findViewById<Button>(R.id.extraButton).setOnClickListener { showExtra() }
        findViewById<Button>(R.id.extraConfirmButton).setOnClickListener { openAnyway() }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goHome() }
        }
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    /** Leaving stops the wait: it starts again from the top next time. */
    override fun onPause() {
        handler.removeCallbacks(countdown)
        hideExtra()
        super.onPause()
    }

    private fun show(intent: Intent) {
        pkg = intent.getStringExtra(EXTRA_PKG) ?: "com.whatsapp"
        val (block, day) = store.current() ?: return goHome() // the focus time is over
        val extraEnded = intent.getBooleanExtra(EXTRA_ENDED, false)
        val history = History(this)

        text(R.id.titleText, if (extraEnded) "Your ${store.extraMinutes} minutes are up" else block.name)
        text(
            R.id.reasonText,
            (if (extraEnded) "${block.name}: " else "") +
                "WhatsApp is closed until ${block.endText()}. You've used your ${block.quickMinutes}-minute quick check."
        )
        val (quote, source) = Encouragements.pick(this)
        text(R.id.quoteText, "“$quote”\n— $source")

        val tries = history.get(LocalDate.now(), History.WA_FOCUS_TRIES)
        val extras = store.extras(block, day)
        text(
            R.id.statsText,
            (if (extraEnded) "" else "This is your ${ordinal(maxOf(1, tries))} WhatsApp try during focus times today.\n") +
                if (extras == 0) "You haven't opened it anyway this time." else "Opened anyway $extras ${if (extras == 1) "time" else "times"} this time."
        )
        text(R.id.streakText, "WhatsApp streak: ${days(history.whatsappStreak())} without opening anyway")
        hideExtra()
    }

    private fun showExtra() {
        val (block, day) = store.current() ?: return goHome()
        val history = History(this)
        text(
            R.id.costText,
            "WhatsApp streak ${history.whatsappStreak()} → 0  ·  General streak ${history.overallStreak()} → 0\n" +
                "Your other streaks are not touched.\n\n" +
                "WhatsApp opens for ${store.extraMinutes} minutes, then closes again. Each time this focus time, " +
                "the wait gets longer. It shows in your weekly report."
        )
        findViewById<View>(R.id.extraButton).visibility = View.GONE
        findViewById<View>(R.id.extraBox).visibility = View.VISIBLE
        waitLeft = store.nextWaitSeconds(block, day)
        handler.removeCallbacks(countdown)
        handler.post(countdown)
    }

    private fun hideExtra() {
        handler.removeCallbacks(countdown)
        findViewById<View>(R.id.extraButton).visibility = View.VISIBLE
        findViewById<View>(R.id.extraBox).visibility = View.GONE
    }

    private fun openAnyway() {
        if (waitLeft > 0) return
        val (block, day) = store.current() ?: return goHome()
        store.openAnyway(this, block, day)
        Toast.makeText(this, "WhatsApp streak reset. WhatsApp is open for ${store.extraMinutes} minutes.", Toast.LENGTH_LONG).show()
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    @Deprecated("Used on Android 12 only")
    override fun onBackPressed() = goHome()

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).text = value
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_ENDED = "extra_ended"

        fun intent(context: Context, pkg: String, extraEnded: Boolean) =
            Intent(context, FocusBlockActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_ENDED, extraEnded)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
