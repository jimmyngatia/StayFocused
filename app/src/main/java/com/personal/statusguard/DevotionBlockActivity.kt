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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.time.LocalDate

/**
 * Covers an app during devotion time once the quick check is used up.
 * "Open anyway" waits a little (longer each time), then opens the app for a few minutes.
 */
class DevotionBlockActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: DevotionStore
    private var pkg = ""
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
                button.text = "Open ${PhoneUsage.appName(this@DevotionBlockActivity, pkg)} for ${store.extraMinutes} min"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_focus_block)
        store = DevotionStore(this)
        findViewById<ImageView>(R.id.coverIcon).setImageResource(R.drawable.ic_book)
        findViewById<Button>(R.id.okButton).text = "Back to my devotion"
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

    override fun onPause() {
        hideExtra()
        super.onPause()
    }

    private fun show(intent: Intent) {
        pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        store.current() ?: return goHome() // devotion time is over
        val block = store.block ?: return goHome()
        val extraEnded = intent.getBooleanExtra(EXTRA_ENDED, false)
        val app = PhoneUsage.appName(this, pkg)

        text(R.id.titleText, if (extraEnded) "Your ${store.extraMinutes} minutes are up" else "Devotion time")
        text(
            R.id.reasonText,
            "$app is closed until ${block.endText()}. You've used your ${block.quickMinutes}-minute quick check. " +
                "Your Bible and the apps you chose still work."
        )
        val (quote, source) = Encouragements.pick(this)
        text(R.id.quoteText, "“$quote”\n— $source")
        val tries = History(this).get(LocalDate.now(), History.DEV_TRIES)
        text(R.id.statsText, if (extraEnded) "" else "This is your ${ordinal(maxOf(1, tries))} try during devotion time today.")
        text(R.id.streakText, "Devotion streak: ${store.streak()} ${if (store.streak() == 1) "day" else "days"}")
        hideExtra()
    }

    private fun showExtra() {
        val day = store.current() ?: return goHome()
        val history = History(this)
        text(
            R.id.costText,
            "General streak ${history.overallStreak()} → 0 for today.\n\n" +
                "${PhoneUsage.appName(this, pkg)} opens for ${store.extraMinutes} minutes, then closes again. " +
                "The wait gets longer each time. It shows in your weekly report."
        )
        findViewById<View>(R.id.extraButton).visibility = View.GONE
        findViewById<View>(R.id.extraBox).visibility = View.VISIBLE
        waitLeft = store.nextWaitSeconds(day)
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
        val day = store.current() ?: return goHome()
        store.openAnyway(day)
        Toast.makeText(this, "Open for ${store.extraMinutes} minutes.", Toast.LENGTH_LONG).show()
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    @Deprecated("Used on Android 12 only")
    override fun onBackPressed() = goHome()

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).apply {
            text = value
            visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_ENDED = "extra_ended"

        fun intent(context: Context, pkg: String, extraEnded: Boolean) =
            Intent(context, DevotionBlockActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_ENDED, extraEnded)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
