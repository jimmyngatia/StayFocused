package com.personal.statusguard

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Lists the strict alarms. Editing needs the password; switching off for one day doesn't. */
class AlarmsActivity : Activity() {
    private lateinit var store: AlarmStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarms)
        store = AlarmStore(this)

        // Adding an alarm only makes things stricter, so it's free.
        findViewById<Button>(R.id.addButton).setOnClickListener {
            startActivity(Intent(this, AlarmEditActivity::class.java))
        }
        findViewById<Button>(R.id.fullScreenButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        prepareScanner(this)
        addPracticeButtons()
    }

    /** Two buttons per row: each runs one step of an alarm on its own. */
    private fun addPracticeButtons() {
        val steps = listOf(
            Practice.SCAN to "Scan code", Practice.WALK to "Walk",
            Practice.CHECKPOINT to "Checkpoint", Practice.JOG to "Jog",
            Practice.PUSHUPS to "Push-ups", Practice.CAMERA to "Camera push-ups",
            Practice.PLEDGE to "Pledge", Practice.AWAKE to "Awake check",
        )
        val box = findViewById<LinearLayout>(R.id.practiceBox)
        steps.chunked(2).forEach { pair ->
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEachIndexed { i, (step, name) ->
                    addView(Button(this@AlarmsActivity, null, 0, R.style.SecondaryButton).apply {
                        text = name
                        setOnClickListener { practise(step) }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = dp(Space.S)
                    })
                }
            })
        }
    }

    private fun practise(step: String) {
        if (AlarmService.phase != null) return AlarmRingActivity.reopenIfRunning(this)
        if (step == Practice.CHECKPOINT && store.all().none { it.checkpoint.isNotEmpty() }) {
            android.widget.Toast.makeText(this, "Set up an outside checkpoint in one of your alarms first.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(AlarmService.practiceIntent(this, step))
    }

    override fun onResume() {
        super.onResume()
        AlarmRingActivity.reopenIfRunning(this)
        // Android 14+ may need permission to show the alarm screen over everything.
        val canFullScreen = Build.VERSION.SDK_INT < 34 ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        findViewById<View>(R.id.fullScreenWarning).visibility = if (canFullScreen) View.GONE else View.VISIBLE
        showList()
    }

    private fun showList() {
        val list = findViewById<LinearLayout>(R.id.alarmList)
        list.removeAllViews()
        val alarms = store.all()
        if (alarms.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No alarms yet."
                alpha = 0.7f
                setPadding(0, dp(Space.XL), 0, 0)
            })
        }
        for (alarm in alarms) list.addView(card(alarm))
    }

    private fun card(alarm: Alarm): View {
        val card = layoutInflater.inflate(R.layout.item_alarm, findViewById(R.id.alarmList), false)
        val next = alarm.nextRing()
        card.findViewById<TextView>(R.id.timeText).text = alarm.timeText()
        card.findViewById<TextView>(R.id.levelText).text = "LEVEL ${alarm.level}" + if (alarm.level >= 2 && !alarm.watchOn) "  ·  NO AWAKE WATCH" else ""
        card.findViewById<TextView>(R.id.labelText).text = alarm.label
        card.findViewById<TextView>(R.id.detailText).text =
            alarm.daysText() + (if (alarm.codeName.isEmpty()) "" else "  ·  code: ${alarm.codeName}") + "\n" + when {
                !alarm.enabled -> "Off"
                next != null -> "Next: ${dayWord(next)} ${alarm.timeText()}"
                else -> "Not scheduled"
            }
        card.alpha = if (alarm.enabled) 1f else 0.5f

        card.findViewById<View>(R.id.editArea).setOnClickListener {
            val prefs = Prefs(this)
            if (prefs.hasPassword()) askPassword(this, "Password to edit") { edit(alarm) } else edit(alarm)
        }

        // "Switch off for tomorrow": skip just the next ring. Undoing it is also free.
        val upcoming = alarm.nextRing(honourSkip = false)
        card.findViewById<Button>(R.id.skipButton).apply {
            if (upcoming == null) {
                visibility = View.GONE
                return@apply
            }
            val day = upcoming.toLocalDate().toString()
            val skipped = alarm.skipDate == day
            text = if (skipped) "Off for ${dayWord(upcoming)} · Undo" else "Switch off for ${dayWord(upcoming)} (shows in report)"
            setOnClickListener {
                val changed = alarm.copy(skipDate = if (skipped) "" else day)
                store.save(changed)
                // No password needed, but it shows in the weekly report.
                History(this@AlarmsActivity).alarmPaused(upcoming.toLocalDate(), paused = !skipped)
                AlarmScheduler.schedule(this@AlarmsActivity, changed)
                showList()
            }
        }
        return card
    }

    private fun edit(alarm: Alarm) =
        startActivity(Intent(this, AlarmEditActivity::class.java).putExtra(AlarmScheduler.EXTRA_ID, alarm.id))

    private fun dayWord(time: LocalDateTime): String {
        val date = time.toLocalDate()
        return when (date) {
            LocalDate.now() -> "today"
            LocalDate.now().plusDays(1) -> "tomorrow"
            else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        }
    }
}
