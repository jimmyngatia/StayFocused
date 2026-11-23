package com.personal.statusguard

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/** The screen for a running alarm. It follows whatever stage the alarm is in. */
class AlarmRingActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var quote: String
    private var wasRunning = false

    // Camera push-ups
    private val camera = CameraLifecycle()
    private var cameraOn = false
    private val faces by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.1f)
                .build()
        )
    }
    private var low = 0f // face size at the top of this push-up (arms straight)
    private var high = 0f // face size at the bottom of this push-up
    private var lastSize = 0f
    private var down = false
    private var lastFaceAt = 0L
    private var cameraSince = 0L

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Appear over the lock screen, wake the display and keep it on.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm_ring)

        val (text, source) = Encouragements.pick(this)
        quote = "“$text”\n— $source"

        findViewById<Button>(R.id.actionButton).setOnClickListener {
            when (AlarmService.phase) {
                Phase.RING -> unlockThen { scanFor(AlarmService.alarm?.code) { AlarmService.send(this, AlarmService.ACTION_SCANNED) } }
                Phase.CHECKPOINT -> checkpoint()
                Phase.PLEDGE -> submitPledge()
                Phase.AWAKE_CHECK -> AlarmService.send(this, AlarmService.ACTION_AWAKE)
                Phase.WAIT_AWAKE -> goHome()
                null -> if (AlarmService.practicing) finish() else goHome() // "Back" / "Let's go" on the done screen
                else -> {}
            }
        }
        // Only test rings can be stopped. A real alarm can only be finished.
        findViewById<Button>(R.id.emergencyButton).setOnClickListener {
            AlarmService.send(this, AlarmService.ACTION_STOP)
            finish()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (AlarmService.phase == Phase.WAIT_AWAKE || AlarmService.phase == null) goHome()
            }
        }
    }

    @Deprecated("Used on Android 12 only")
    override fun onBackPressed() {
        if (AlarmService.phase == Phase.WAIT_AWAKE || AlarmService.phase == null) goHome()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        stopFaceCamera()
        super.onPause()
    }

    override fun onDestroy() {
        faces.close()
        super.onDestroy()
    }

    private fun render() {
        val phase = AlarmService.phase
        if (phase == null) {
            stopFaceCamera()
            // The alarm is over: celebrate, or just close if it was stopped another way.
            if (wasRunning) showDone() else finish()
            return
        }
        wasRunning = true
        val alarm = AlarmService.alarm ?: return
        val secondsLeft = maxOf(0, (AlarmService.deadline - System.currentTimeMillis()) / 1000)
        val clock = "%d:%02d".format(secondsLeft / 60, secondsLeft % 60)
        val left = maxOf(0, AlarmService.target - AlarmService.progress).toString()

        findViewById<View>(R.id.emergencyButton).visibility = if (AlarmService.isTest) View.VISIBLE else View.GONE

        // "Part 1 of 4" while doing the tasks.
        val doingTasks = phase in listOf(Phase.WALK, Phase.CHECKPOINT, Phase.PUSHUPS, Phase.JOG, Phase.PLEDGE)
        findViewById<TextView>(R.id.partText).apply {
            visibility = if (doingTasks && AlarmService.parts > 1) View.VISIBLE else View.GONE
            text = "PART ${AlarmService.part} OF ${AlarmService.parts}"
        }
        findViewById<TextView>(R.id.hintText).apply {
            val show = AlarmService.hintUntil > SystemClock.elapsedRealtime() && AlarmService.hint.isNotEmpty()
            visibility = if (show) View.VISIBLE else View.GONE
            text = AlarmService.hint
        }
        findViewById<View>(R.id.pledgeInput).visibility = if (phase == Phase.PLEDGE) View.VISIBLE else View.GONE

        val cameraPushups = phase == Phase.PUSHUPS && AlarmService.pushMode == PushMode.CAMERA
        if (cameraPushups) startFaceCamera() else stopFaceCamera()
        if (cameraPushups) checkDark()

        val slow = AlarmService.tooSlow
        when (phase) {
            Phase.RING -> {
                val where = if (alarm.codeName.isEmpty()) "Go scan your code." else "Go scan the code on\n${alarm.codeName}"
                show(alarm.label, alarm.timeText(), "$where\n\n$quote", "Scan code to stop", R.drawable.ic_camera)
            }
            Phase.WALK -> show(
                if (slow) "Too slow!" else "Walk outside", left,
                "steps to go" + (if (slow) "" else " · $clock left") +
                    "\nTake your phone and walk outside. Flipping or shaking it doesn't count." +
                    if (alarm.checkpointType != Alarm.CHECK_NONE) "\nThen scan the checkpoint: ${alarm.checkpointName.ifEmpty { "outside" }}." else "",
                null,
            )
            Phase.CHECKPOINT -> show(
                if (slow) "Too slow!" else "Checkpoint", if (slow) "" else clock,
                "Now ${if (alarm.checkpointType == Alarm.CHECK_WORDS) "read the sign" else "scan the code"} at\n${alarm.checkpointName.ifEmpty { "your checkpoint" }}",
                if (alarm.checkpointType == Alarm.CHECK_WORDS) "Read the sign" else "Scan checkpoint",
                R.drawable.ic_camera,
            )
            Phase.PUSHUPS -> show(
                "Push-ups", left,
                "to go. Phone face-up and still on the floor under your chest.\n" + when (AlarmService.pushMode) {
                    PushMode.CAMERA -> "Keep your face in the picture: down close, then back up." +
                        (if (lastSize > 0f) "\nFace size: %.0f%% · %s".format(lastSize * 100, if (down) "down, now push up" else "up, now go down") else "")
                    PushMode.DARK -> "Dark mode: counted by the sensor (50% more push-ups)."
                    PushMode.SENSOR -> "Chest down to the phone, then all the way up."
                },
                null,
            )
            Phase.JOG -> show(
                "Now jog!", left,
                "jogging steps to go. Walking pace doesn't count.\n\n" +
                    "Bounce strength: %.1f (jogging needs %.0f+)".format(AlarmService.strongest, AlarmService.BOUNCE_HIT),
                null,
            )
            Phase.PLEDGE -> show("Last step", "", "Type this exactly:\n\n“${Pledges.today()}”", "Submit")
            Phase.WAIT_AWAKE -> {
                val watchLeft = maxOf(0, (AlarmService.watchEnd - System.currentTimeMillis()) / 60_000 + 1)
                show(
                    if (AlarmService.checksDone == 0) "Well done! Stay up." else "Good. Keep going.",
                    "$watchLeft min",
                    "left on the awake watch. Checks done: ${AlarmService.checksDone}.\n\n" +
                        "At random times a soft buzz and beep will ask if you're still awake. " +
                        "Answer within 60 seconds, or the alarm rings again and everything starts over.",
                    "Got it",
                )
            }
            Phase.AWAKE_CHECK -> show(
                "Still awake?", clock,
                "${AlarmService.checkQuote}\n\nTap within 60 seconds, or the alarm rings again and everything starts over." +
                    if (alarm.level >= 3) "\nYour partners will be told you went back to sleep." else "",
                "I'm awake",
            )
        }
        lightPage(cameraPushups)
    }

    // ---- Checkpoint and pledge ----

    private fun checkpoint() {
        val alarm = AlarmService.alarm ?: return
        unlockThen {
            if (alarm.checkpointType == Alarm.CHECK_WORDS) {
                ScanActivity.sign(this, alarm.checkpoint, alarm.checkpointName) { AlarmService.send(this, AlarmService.ACTION_CHECKPOINT) }
            } else scanFor(alarm.checkpoint) { AlarmService.send(this, AlarmService.ACTION_CHECKPOINT) }
        }
    }

    private fun submitPledge() {
        val input = findViewById<EditText>(R.id.pledgeInput)
        if (Pledges.matches(input.text.toString())) {
            input.text.clear()
            AlarmService.send(this, AlarmService.ACTION_PLEDGE)
        } else Toast.makeText(this, "Not exactly. Read it again and type it carefully.", Toast.LENGTH_LONG).show()
    }

    // ---- Camera push-ups ----

    private fun startFaceCamera() {
        if (cameraOn) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            AlarmService.send(this, AlarmService.ACTION_CAMERA_DARK) // no camera allowed: sensor mode
            return
        }
        cameraOn = true
        low = 0f
        high = 0f
        down = false
        cameraSince = SystemClock.elapsedRealtime()
        lastFaceAt = 0L
        findViewById<View>(R.id.cameraPreview).visibility = View.VISIBLE
        startCamera(
            this, camera, front = true, preview = findViewById<PreviewView>(R.id.cameraPreview),
            onFrame = ::findFace,
            onError = { AlarmService.send(this, AlarmService.ACTION_CAMERA_DARK) },
        )
    }

    private fun stopFaceCamera() {
        if (!cameraOn) return
        cameraOn = false
        stopCamera(this, camera)
        findViewById<View>(R.id.cameraPreview).visibility = View.GONE
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun findFace(frame: ImageProxy) {
        val media = frame.image ?: return frame.close()
        val rotation = frame.imageInfo.rotationDegrees
        val width = if (rotation == 90 || rotation == 270) frame.height else frame.width
        faces.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { list ->
                val face = list.maxByOrNull { it.boundingBox.width() }
                onFace(face?.let { it.boundingBox.width().toFloat() / width })
            }
            .addOnCompleteListener { frame.close() }
    }

    /**
     * Face size in the picture tells where you are: big = down at the phone, small = arms straight.
     * One push-up = going clearly bigger, then back to the "up" size.
     */
    private fun onFace(size: Float?) {
        if (!cameraOn) return
        val now = SystemClock.elapsedRealtime()
        if (size == null) {
            // Very close faces can't be seen whole: losing a growing face right away means you're down.
            if (!down && low > 0 && lastSize >= low * 1.25f && now - lastFaceAt < 1000) {
                down = true
                high = maxOf(lastSize, low * 2f)
            }
            return
        }
        lastFaceAt = now
        lastSize = size
        // Each push-up is measured against itself, so it adapts to where the phone is and how tall you are.
        if (!down) {
            if (low == 0f || size < low) low = size
            if (size >= low * DOWN) {
                down = true
                high = size
            }
        } else {
            if (size > high) high = size
            if (size <= high * UP) {
                down = false
                low = size // this "up" is the start of the next one
                AlarmService.send(this, AlarmService.ACTION_CAMERA_REP)
            }
        }
    }

    /** No face for 15 seconds (too dark, or not set up right): switch to sensor push-ups. */
    private fun checkDark() {
        val since = maxOf(lastFaceAt, cameraSince)
        if (cameraOn && SystemClock.elapsedRealtime() - since > 15_000) {
            stopFaceCamera()
            AlarmService.send(this, AlarmService.ACTION_CAMERA_DARK)
        }
    }

    /** During camera push-ups the page turns white at full brightness, to light your face. */
    private fun lightPage(on: Boolean) {
        val root = findViewById<View>(R.id.root)
        val wanted = if (on) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window.attributes.screenBrightness != wanted) {
            window.attributes = window.attributes.apply { screenBrightness = wanted }
            if (on) root.setBackgroundColor(Color.WHITE) else root.setBackgroundResource(R.drawable.alarm_background)
            val color = if (on) Color.BLACK else getColor(R.color.on_cover)
            for (id in listOf(R.id.titleText, R.id.bigText, R.id.infoText, R.id.hintText, R.id.partText)) {
                findViewById<TextView>(id).setTextColor(color)
            }
        }
    }

    // ---- Done ----

    /** The finish line: your morning streak and the intention you wrote last night. */
    private fun showDone() {
        handler.removeCallbacks(refresh)
        if (AlarmService.practicing) {
            for (id in listOf(R.id.partText, R.id.emergencyButton, R.id.hintText, R.id.pledgeInput)) findViewById<View>(id).visibility = View.GONE
            return show("Practice done!", "", "That step works. Nothing was counted.", "Back")
        }
        val streak = History(this).alarmStreak
        val intention = Words(this).todaysIntention()
        for (id in listOf(R.id.partText, R.id.emergencyButton, R.id.hintText, R.id.pledgeInput)) findViewById<View>(id).visibility = View.GONE
        show(
            "All done!",
            "$streak",
            (if (streak == 1) "morning in a row" else "mornings in a row") +
                "\n\n" + (intention?.let { "Today you said you'd:\n$it" } ?: "Have a great day!"),
            "Let's go",
        )
    }

    private fun show(title: String, big: String, info: String, button: String?, icon: Int = 0) {
        findViewById<TextView>(R.id.titleText).text = title
        findViewById<TextView>(R.id.bigText).apply {
            text = big
            visibility = if (big.isEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.infoText).text = info
        findViewById<Button>(R.id.actionButton).apply {
            visibility = if (button == null) View.GONE else View.VISIBLE
            text = button
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        }
    }

    /** The scanner and camera can't open over the lock screen, so unlock first. */
    private fun unlockThen(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return action()
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
        })
    }

    /** Scans a code and runs [onMatch] if it's the [expected] one. */
    private fun scanFor(expected: String?, onMatch: () -> Unit) = scanCode(this) { value ->
        if (expected.isNullOrEmpty() || value == expected) onMatch()
        else Toast.makeText(this, "That's not the right code. Go find it!", Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val DOWN = 1.4f // face 40% bigger than at the top = you're down
        private const val UP = 0.75f // back to 3/4 of the bottom size = you're up again

        /**
         * If an alarm is running, jump back to its screen. Called whenever the app is opened,
         * so a running alarm is never lost. During the awake watch it stays put.
         */
        fun reopenIfRunning(activity: Activity) {
            val phase = AlarmService.phase ?: return
            if (phase != Phase.WAIT_AWAKE) activity.startActivity(Intent(activity, AlarmRingActivity::class.java))
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
