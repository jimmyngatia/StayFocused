package com.personal.statusguard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Create or change one strict alarm: time, days, the codes, the level and its tasks. */
class AlarmEditActivity : Activity() {
    private lateinit var store: AlarmStore
    private var id = 0
    private var code = ""
    private var checkpoint = ""
    private var skipDate = ""
    private var away = false // left on purpose (scanner, camera, partner settings), so don't close on return
    private val dayBoxes = mutableMapOf<Int, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AlarmService.phase != null) {
            Toast.makeText(this, "Nice try. Finish the alarm first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_alarm_edit)
        store = AlarmStore(this)

        val existing = store.get(intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1))
        val a = existing ?: Alarm(store.newId(), "Morning devotions", 5, 30, (1..7).toSet(), "", true)
        id = a.id
        code = a.code
        checkpoint = a.checkpoint
        skipDate = a.skipDate

        field(R.id.label).setText(a.label)
        findViewById<TimePicker>(R.id.time).apply {
            setIs24HourView(true)
            hour = a.hour
            minute = a.minute
        }
        findViewById<CheckBox>(R.id.enabled).isChecked = a.enabled
        field(R.id.steps).setText(a.steps.toString())
        field(R.id.walkMinutes).setText(a.walkMinutes.toString())
        field(R.id.pushups).setText(a.pushups.toString())
        field(R.id.jogSteps).setText(a.jogSteps.toString())
        field(R.id.watchMinutes).setText(a.watchMinutes.toString())
        field(R.id.codeName).setText(a.codeName)
        field(R.id.checkpointName).setText(a.checkpointName)
        findViewById<Switch>(R.id.cameraPushups).apply {
            isChecked = a.cameraPushups
            setOnCheckedChangeListener { _, on ->
                if (on && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
                }
            }
        }
        findViewById<RadioGroup>(R.id.levelGroup).apply {
            setOnCheckedChangeListener { _, _ -> levelChanged() }
            check(levelIds[a.level - 1])
        }
        findViewById<RadioGroup>(R.id.checkpointGroup).apply {
            check(checkIds[checkTypes.indexOf(a.checkpointType).coerceAtLeast(0)])
            setOnCheckedChangeListener { _, _ ->
                checkpoint = "" // a different kind needs setting up again
                showCheckpoint()
            }
        }

        val daysBox = findViewById<LinearLayout>(R.id.days)
        for (day in 1..7) {
            val box = CheckBox(this).apply {
                text = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())
                isChecked = day in a.days
            }
            dayBoxes[day] = box
            daysBox.addView(box)
        }
        showCode()
        showCheckpoint()
        levelChanged()

        findViewById<Button>(R.id.scanButton).setOnClickListener {
            away = true
            scanCode(this) { value ->
                code = value
                showCode()
            }
        }
        findViewById<Button>(R.id.checkpointButton).setOnClickListener { setUpCheckpoint() }
        findViewById<Button>(R.id.partnerButton).setOnClickListener { openPartners() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { if (save()) afterSave { finish() } }
        findViewById<Button>(R.id.testButton).setOnClickListener {
            if (save()) startForegroundService(AlarmService.ringIntent(this, id, test = true))
        }
        val deleteButton = findViewById<Button>(R.id.deleteButton)
        deleteButton.visibility = if (existing == null) View.GONE else View.VISIBLE
        deleteButton.setOnClickListener {
            AlarmScheduler.cancel(this, id)
            store.delete(id)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        showPartner()
    }

    /**
     * Coming back to this screen (e.g. from recent apps) closes it, so editing always needs the
     * password again. Coming back from the scanner, camera or partner settings is the exception.
     */
    override fun onRestart() {
        super.onRestart()
        if (!away) finish()
        away = false
    }

    // ---- Accountability partner ----

    private fun showPartner() {
        val email = EmailSettings(this)
        val has = email.canSend && email.wakeEmails
        findViewById<TextView>(R.id.partnerText).text =
            if (has) "${email.recipients} will get an email if you go back to sleep (levels 3 and 4) or switch the phone off during an alarm."
            else "Strict alarms work best when someone knows. Add a friend: they'll get an email if you go back to sleep or switch the phone off to escape an alarm."
        findViewById<Button>(R.id.partnerButton).text = if (has) "Change partner" else "Add a partner"
    }

    private fun openPartners() {
        away = true
        startActivity(Intent(this, EmailSettingsActivity::class.java))
    }

    /** One friendly nudge the first time you save a strict alarm without a partner. */
    private fun afterSave(then: () -> Unit) {
        val prefs = getSharedPreferences("alarms", MODE_PRIVATE)
        if (level() < 2 || EmailSettings(this).canSend || prefs.getBoolean("partner_nudged", false)) return then()
        prefs.edit().putBoolean("partner_nudged", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Set this with a partner?")
            .setMessage("You're far less likely to go back to sleep when a friend gets an email about it. It takes 3 minutes.")
            .setPositiveButton("Add a partner") { _, _ -> openPartners() }
            .setNegativeButton("Not now") { _, _ -> then() }
            .show()
    }

    // ---- Levels ----

    private val levelIds = listOf(R.id.level1, R.id.level2, R.id.level3, R.id.level4)
    private val checkIds = listOf(R.id.checkNone, R.id.checkCode, R.id.checkWords)
    private val checkTypes = listOf(Alarm.CHECK_NONE, Alarm.CHECK_CODE, Alarm.CHECK_WORDS)

    private fun level() = levelIds.indexOf(findViewById<RadioGroup>(R.id.levelGroup).checkedRadioButtonId) + 1

    private fun checkType() = checkTypes[checkIds.indexOf(findViewById<RadioGroup>(R.id.checkpointGroup).checkedRadioButtonId).coerceAtLeast(0)]

    /** Shows only the cards that matter for the chosen level. */
    private fun levelChanged() {
        val level = level()
        fun show(id: Int, on: Boolean) {
            findViewById<View>(id).visibility = if (on) View.VISIBLE else View.GONE
        }
        show(R.id.walkBox, level >= 2)
        show(R.id.pushupsBox, level >= 3)
        show(R.id.jogBox, level >= 4)
        show(R.id.watchBox, level >= 2)
        findViewById<TextView>(R.id.levelNote).text = when (level) {
            1 -> "Scan the code and it stops."
            2 -> "Then the awake watch."
            else -> "Then type today's pledge (\"I have not cheated…\"), then the awake watch. Going back to sleep emails your partner."
        }
        // Counting steps needs the "Physical activity" permission.
        if (level >= 2 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
        }
    }

    private fun showCode() {
        findViewById<TextView>(R.id.codeText).text =
            if (code.isEmpty()) "No code yet. Scan the barcode on your Bible, notebook, etc."
            else "Code: $code"
    }

    private fun showCheckpoint() {
        val type = checkType()
        findViewById<View>(R.id.checkpointBox).visibility = if (type == Alarm.CHECK_NONE) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.checkpointText).text = when {
            checkpoint.isEmpty() -> "Not set up yet."
            type == Alarm.CHECK_WORDS -> "Sign words: ${checkpoint.lowercase()}"
            else -> "Code: $checkpoint"
        }
        findViewById<Button>(R.id.checkpointButton).text = if (type == Alarm.CHECK_WORDS) "Read the sign" else "Scan the outside code"
    }

    private fun setUpCheckpoint() {
        away = true
        if (checkType() == Alarm.CHECK_WORDS) ScanActivity.sign(this) { words ->
            checkpoint = words
            showCheckpoint()
        }
        else scanCode(this) { value ->
            if (value == code) Toast.makeText(this, "That's the same code as the first one. Use a different one outside.", Toast.LENGTH_LONG).show()
            else {
                checkpoint = value
                showCheckpoint()
            }
        }
    }

    private fun save(): Boolean {
        currentFocus?.clearFocus() // a time typed into the picker only counts once it loses focus
        val days = dayBoxes.filterValues { it.isChecked }.keys
        val level = level()
        fun number(id: Int) = field(id).text.toString().toIntOrNull()
        val steps = number(R.id.steps)
        val walkMinutes = number(R.id.walkMinutes)
        val pushups = number(R.id.pushups)
        val jog = number(R.id.jogSteps)
        val watch = number(R.id.watchMinutes)
        val type = if (level >= 2) checkType() else Alarm.CHECK_NONE
        val problem = when {
            code.isEmpty() -> "Scan a code first."
            days.isEmpty() -> "Pick at least one day."
            steps == null || steps !in 5..2000 -> "Steps must be between 5 and 2000."
            walkMinutes == null || walkMinutes !in 1..30 -> "Minutes allowed must be between 1 and 30."
            pushups == null || pushups !in 1..200 -> "Push-ups must be between 1 and 200."
            jog == null || jog !in 20..3000 -> "Jogging steps must be between 20 and 3000."
            watch == null || watch !in Alarm.MIN_WATCH..120 -> "The awake watch must be between ${Alarm.MIN_WATCH} and 120 minutes."
            type != Alarm.CHECK_NONE && checkpoint.isEmpty() -> "Set up the outside checkpoint, or choose \"None\"."
            else -> null
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return false
        }
        val time = findViewById<TimePicker>(R.id.time)
        val alarm = Alarm(
            id = id,
            label = field(R.id.label).text.toString().ifBlank { "Alarm" },
            hour = time.hour,
            minute = time.minute,
            days = days,
            code = code,
            codeName = field(R.id.codeName).text.toString().trim(),
            enabled = findViewById<CheckBox>(R.id.enabled).isChecked,
            level = level,
            steps = steps!!,
            pushups = pushups!!,
            skipDate = skipDate,
            walkMinutes = walkMinutes!!,
            checkpointType = type,
            checkpoint = if (type == Alarm.CHECK_NONE) "" else checkpoint,
            checkpointName = field(R.id.checkpointName).text.toString().trim(),
            cameraPushups = findViewById<Switch>(R.id.cameraPushups).isChecked,
            jogSteps = jog!!,
            watchMinutes = watch!!,
        )
        store.save(alarm)
        AlarmScheduler.schedule(this, alarm)
        return true
    }

    private fun field(id: Int) = findViewById<EditText>(id)
}
