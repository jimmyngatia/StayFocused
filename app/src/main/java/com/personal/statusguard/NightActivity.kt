package com.personal.statusguard

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The night screen during sleep time: a dim clock, how long you've slept, a good-night quote,
 * the apps you can still use, and a force bypass (5 minutes, cuts every streak in half).
 * Shows over the lock screen, so turning the phone on at night lands here.
 */
class NightActivity : Activity() {
    private lateinit var store: SleepStore
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        // Dim: easy on the eyes at night. Only while this screen is showing.
        window.attributes = window.attributes.apply { screenBrightness = 0.08f }
        setContentView(R.layout.activity_night)
        store = SleepStore(this)

        val (quote, source) = NightQuotes.pick(this)
        text(R.id.quoteText, "“$quote”")
        text(R.id.sourceText, "— $source")
        buildApps()

        findViewById<Button>(R.id.bypassButton).setOnClickListener { showBypass(true) }
        findViewById<Button>(R.id.bypassCancelButton).setOnClickListener { showBypass(false) }
        findViewById<Button>(R.id.bypassConfirmButton).setOnClickListener { bypass() }
        // Back stays here: this screen is the phone's home at night.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { showBypass(false) }
        }

        // A soft fade in.
        findViewById<View>(R.id.content).apply {
            alpha = 0f
            animate().alpha(1f).setDuration(900).start()
        }
    }

    @Deprecated("Used on Android 12 only")
    override fun onBackPressed() = showBypass(false)

    override fun onResume() {
        super.onResume()
        isVisible = true
        handler.post(tick)
    }

    override fun onPause() {
        isVisible = false
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun update() {
        val now = LocalDateTime.now()
        val schedule = store.current(now)
        if (schedule == null || store.isBypassed()) {
            finish() // morning, or a bypass started elsewhere
            return
        }
        text(R.id.clockText, now.format(DateTimeFormatter.ofPattern("HH:mm")))
        text(R.id.dateText, now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")))

        val slept = store.sleptTonightMs()
        if (slept < 60_000) {
            text(R.id.sleptText, "Time to rest")
            text(R.id.sleptCaption, "Your sleep is counted while the screen stays off.")
        } else {
            text(R.id.sleptText, "${PhoneUsage.format(slept)} asleep tonight")
            text(R.id.sleptCaption, "Counted while your phone was untouched.")
        }

        var wake = now.toLocalDate().atTime(schedule.wake / 60, schedule.wake % 60)
        if (!wake.isAfter(now)) wake = wake.plusDays(1)
        val left = Duration.between(now, wake).toMillis()
        text(R.id.wakeText, "Wake-up ${schedule.wakeText()}  ·  in ${PhoneUsage.format(left)}")

        val h = History(this)
        val overall = h.overallStreak()
        val sleep = h.cleanStreak(History.SLEEP_ATTEMPTS)
        text(
            R.id.costText,
            "Streak $overall → 0  ·  Sleep $sleep → ${sleep / 2}\n" +
                "Status, social and morning streaks are cut in half too.\n" +
                "The whole phone opens for ${SleepStore.BYPASS_MINUTES} minutes, then the night screen comes back. " +
                "It shows in your weekly report."
        )
    }

    /** One tile per app you can still use: icon and name. */
    private fun buildApps() {
        val row = findViewById<LinearLayout>(R.id.appsRow)
        val apps = store.nightApps().filter { packageManager.getLaunchIntentForPackage(it) != null }
        findViewById<View>(R.id.appsLabel).visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        for (pkg in apps) {
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(Space.S), dp(Space.S), dp(Space.S), dp(Space.S))
                setBackgroundResource(android.R.drawable.list_selector_background)
                addView(ImageView(this@NightActivity).apply {
                    setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
                    alpha = 0.85f
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(TextView(this@NightActivity).apply {
                    text = PhoneUsage.appName(this@NightActivity, pkg)
                    textSize = 11f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.night_soft))
                    setPadding(0, dp(Space.XS), 0, 0)
                }, LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT))
                setOnClickListener { unlockThen { open(pkg) } }
            })
        }
    }

    private fun open(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    private fun showBypass(show: Boolean) {
        findViewById<View>(R.id.bypassButton).visibility = if (show) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bypassBox).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun bypass() {
        store.startBypass()
        Toast.makeText(this, "Streaks cut. The phone is open for ${SleepStore.BYPASS_MINUTES} minutes.", Toast.LENGTH_LONG).show()
        unlockThen {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    /** This screen shows over the lock screen; opening anything else needs the phone unlocked first. */
    private fun unlockThen(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return action()
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
        })
    }

    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).text = value
    }

    companion object {
        /** True while the night screen is in front (so it isn't opened twice). */
        var isVisible = false
            private set

        fun intent(context: Context) =
            Intent(context, NightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
}

/** Gentle good-night quotes for the night screen. Your own quotes (My words) are mixed in. */
object NightQuotes {
    private val quotes = listOf(
        "Sleep is the best meditation." to "Dalai Lama",
        "In peace I will lie down and sleep, for you alone, Lord, make me dwell in safety." to "Psalm 4:8",
        "Rest is not idleness. It is the quiet work of becoming whole again." to "Unknown",
        "Each night, when I go to sleep, I die. And the next morning, when I wake up, I am reborn." to "Mahatma Gandhi",
        "Tomorrow is a new day with no mistakes in it yet." to "L. M. Montgomery",
        "Come unto me, all ye that labour and are heavy laden, and I will give you rest." to "Matthew 11:28",
        "Let her sleep, for when she wakes, she will move mountains." to "Napoleon Bonaparte",
        "The best bridge between despair and hope is a good night's sleep." to "E. Joseph Cossman",
        "Finish each day and be done with it. Tomorrow is a new day." to "Ralph Waldo Emerson",
        "When you lie down, you will not be afraid; your sleep will be sweet." to "Proverbs 3:24",
        "A well-spent day brings happy sleep." to "Leonardo da Vinci",
        "Whatever is still undone tonight will wait for a rested you." to "Status Guard",
        "Put the phone down. The world will still be there in the morning." to "Status Guard",
    )

    fun pick(context: Context): Pair<String, String> {
        val mine = Words(context).quotes
        return if (mine.isNotEmpty() && Math.random() < 0.3) mine.random() to "My words" else quotes.random()
    }
}
