package com.personal.statusguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/** The stages a ringing alarm goes through. Which ones depends on the alarm's level. */
enum class Phase {
    RING,        // ringing at full volume, waiting for the first barcode
    WALK,        // levels 2-4: walk the steps outside (flips and shakes don't count)
    CHECKPOINT,  // levels 2-4, if set: scan the outside code or read the sign
    PUSHUPS,     // levels 3-4: phone flat and still on the floor
    JOG,         // level 4: steps at jogging pace only
    PLEDGE,      // levels 3-4: type today's pledge
    WAIT_AWAKE,  // levels 2-4: the awake watch, silent until the next random check
    AWAKE_CHECK, // tap "I'm awake" within 60 seconds or everything starts over
}

/** How push-ups are being counted. */
enum class PushMode { SENSOR, CAMERA, DARK }

/**
 * Runs a ringing alarm from start to finish. It keeps going even if you leave the alarm screen.
 * Only finishing it ends a real alarm; switching the phone off escapes it, but that's counted
 * and your accountability partners are told.
 */
class AlarmService : Service(), SensorEventListener {
    private lateinit var audio: AudioManager
    private lateinit var sensors: SensorManager
    private lateinit var vibrator: Vibrator
    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var torchId: String? = null
    private var torchOn = false
    private val tasks = ArrayDeque<Phase>() // what's left to do after the scan
    private var test = false // test rings don't count for anything
    private var wentBackToSleep = false // missed an awake check during this alarm
    private var rangAt = "" // when the alarm first rang, for partner emails
    private var shortcutCounted = false
    private var idleLoud = false // stopped mid-task, so the alarm is loud again
    private var lastProgress = 0L

    // The morning log (real alarms only)
    private var morning: org.json.JSONObject? = null
    private var morningDate = java.time.LocalDate.now()
    private var morningKey = ""
    private var ringMs = 0L
    private var cameraReps = 0
    private var sensorReps = 0

    // Push-up checks
    private var near = false
    private var nearSince = 0L
    private var lastRep = 0L
    private var lastUnsteady = 0L

    // Walking checks
    private var lastFlip = 0L
    private var lastStepNs = 0L
    private val gravity = ArrayDeque<FloatArray>() // [time, x, y, z] over the last second
    private var hasGravity = false

    // Jogging: counted from the bounces the motion sensor feels, not the step sensor
    private var bounceArmed = true
    private var lastBounce = 0L

    private val alarmSound = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Sets the alarm volume back to full every second, in case someone turned it down. */
    private val keepVolume = object : Runnable {
        override fun run() {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            handler.postDelayed(this, 1000)
        }
    }

    /** The harsh extras for "too slow": emergency beeping and a blinking flashlight. */
    private val harshExtras = object : Runnable {
        private var ticks = 0
        override fun run() {
            setTorch(!torchOn)
            if (ticks++ % 3 == 0) tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 900)
            handler.postDelayed(this, 300)
        }
    }

    /** Moves on when a stage's countdown runs out. */
    private val timeUp = Runnable {
        when (phase) {
            Phase.WALK, Phase.CHECKPOINT -> {
                tooSlow = true
                playSound()
                showScreen(alert = true)
            }
            Phase.WAIT_AWAKE -> enter(Phase.AWAKE_CHECK)
            Phase.AWAKE_CHECK -> missedCheck()
            else -> {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StatusGuard:alarm")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (test || phase == null) stopSelf() // only test rings can be stopped
            ACTION_SCANNED -> if (phase == Phase.RING) {
                if (practicing) complete() else afterScan()
            }
            ACTION_CHECKPOINT -> if (phase == Phase.CHECKPOINT) nextTask()
            ACTION_CAMERA_REP -> if (phase == Phase.PUSHUPS && pushMode == PushMode.CAMERA) tryRep()
            ACTION_CAMERA_DARK -> if (phase == Phase.PUSHUPS && pushMode == PushMode.CAMERA) goDark()
            ACTION_PLEDGE -> if (phase == Phase.PLEDGE) {
                madeProgress()
                nextTask()
            }
            ACTION_AWAKE -> if (phase == Phase.AWAKE_CHECK) {
                checksDone++
                log { plus("checks", 1) }
                waitForNextCheck()
            }
            else -> {
                if (phase != null) return START_NOT_STICKY // one alarm at a time
                test = intent?.getBooleanExtra(EXTRA_TEST, false) ?: false
                val step = intent?.getStringExtra(EXTRA_PRACTICE)
                if (step != null) practice(step) else start(intent?.getIntExtra(AlarmScheduler.EXTRA_ID, -1) ?: -1)
            }
        }
        return START_NOT_STICKY
    }

    private fun start(id: Int) {
        practicing = false
        alarm = AlarmStore(this).get(id) ?: Alarm(id, "Alarm", 0, 0, emptySet(), "", true)
        isTest = test
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        // Keep the phone awake (screen may be off) for the tasks and the awake watch.
        if (!wakeLock.isHeld) wakeLock.acquire(3 * 60 * 60_000L)
        wentBackToSleep = false
        shortcutCounted = false
        rangAt = clockNow()
        ringMs = System.currentTimeMillis()
        morning = null
        if (!test) {
            val a = alarm!!
            morningDate = java.time.LocalDate.now()
            morningKey = rangAt.replace(":", "") + "-" + a.id
            morning = org.json.JSONObject().put("label", a.label).put("level", a.level).put("rang", rangAt).put("result", Morning.UNFINISHED)
            History(this).alarmStarted(a.label, morningDate, morningKey)
            log {}
        }
        enter(Phase.RING)
    }

    /** Updates this morning's record in the log. Does nothing for tests and practice. */
    private fun log(change: org.json.JSONObject.() -> Unit) {
        val m = morning ?: return
        m.change()
        History(this).saveMorning(morningDate, morningKey, m)
    }

    private fun org.json.JSONObject.plus(field: String, amount: Int) = put(field, optInt(field) + amount)

    private fun minutesSinceRing() = ((System.currentTimeMillis() - ringMs) / 60_000).toInt()

    /**
     * Practice one step on its own ([step] is a [Practice] key), with your alarms' settings.
     * Nothing counts: no streaks, no report, no emails.
     */
    private fun practice(step: String) {
        test = true
        isTest = true
        practicing = true
        val alarms = AlarmStore(this).all()
        val base = alarms.firstOrNull { it.checkpoint.isNotEmpty() && step == Practice.CHECKPOINT }
            ?: alarms.maxByOrNull { it.level }
            ?: Alarm(0, "Practice", 0, 0, emptySet(), "", true)
        alarm = base.copy(
            label = "Practice",
            cameraPushups = step == Practice.CAMERA,
            checkpointType = if (step == Practice.CHECKPOINT) base.checkpointType else Alarm.CHECK_NONE,
        )
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (!wakeLock.isHeld) wakeLock.acquire(60 * 60_000L)
        tasks.clear()
        parts = 1
        part = 1
        watchEnd = 0
        when (step) {
            Practice.SCAN -> enter(Phase.RING)
            Practice.WALK -> enter(Phase.WALK)
            Practice.CHECKPOINT -> {
                deadline = System.currentTimeMillis() + base.walkMinutes * 60_000L
                enter(Phase.CHECKPOINT)
            }
            Practice.PUSHUPS, Practice.CAMERA -> enter(Phase.PUSHUPS)
            Practice.JOG -> enter(Phase.JOG)
            Practice.PLEDGE -> enter(Phase.PLEDGE)
            else -> enter(Phase.AWAKE_CHECK)
        }
    }

    /** Finished properly. The morning streak grows, unless you went back to sleep on the way. */
    private fun complete() {
        log {
            if (!has("doneMin")) put("doneMin", minutesSinceRing())
            put("finishedAt", clockNow())
            put("result", if (wentBackToSleep) Morning.DONE_AFTER_SLEEP else Morning.DONE)
        }
        if (!test) {
            val history = History(this)
            history.add(History.ALARMS_DONE)
            if (!wentBackToSleep) history.alarmStreak++
        }
        stopSelf()
    }

    private fun afterScan() {
        log { if (!has("upMin")) put("upMin", minutesSinceRing()) }
        val a = alarm!!
        tasks.clear()
        if (a.walks) {
            tasks += Phase.WALK
            if (a.checkpointType != Alarm.CHECK_NONE && a.checkpoint.isNotEmpty()) tasks += Phase.CHECKPOINT
        }
        if (a.pushupsOn) tasks += Phase.PUSHUPS
        if (a.jogs) tasks += Phase.JOG
        if (a.pledge) tasks += Phase.PLEDGE
        parts = tasks.count { it != Phase.CHECKPOINT }
        part = 0
        nextTask()
    }

    /** Starts the next task, or the awake watch once they're all done. */
    private fun nextTask() {
        // Log the task that just finished.
        val done = progress
        when (phase) {
            Phase.WALK -> log { plus("walk", done) }
            Phase.CHECKPOINT -> log { plus("checkpoint", 1) }
            Phase.PUSHUPS -> log {
                plus("pushups", done)
                plus("pushupsCamera", cameraReps)
                if (pushMode == PushMode.DARK) plus("pushupsDark", sensorReps)
            }
            Phase.JOG -> log { plus("jog", done) }
            Phase.PLEDGE -> log { plus("pledge", 1) }
            else -> {}
        }
        if (tasks.isEmpty() && phase != Phase.RING || tasks.isEmpty() && alarm!!.level == 1) log { put("doneMin", minutesSinceRing()) }
        val next = tasks.removeFirstOrNull()
        when {
            next != null -> {
                if (next != Phase.CHECKPOINT) part++
                enter(next)
            }
            practicing -> complete()
            alarm!!.watches -> {
                // Tasks done: the awake watch (if it's on for this alarm). Random checks until the time is up.
                watchEnd = System.currentTimeMillis() + alarm!!.watchMinutes.coerceAtLeast(Alarm.MIN_WATCH) * 60_000L
                checksDone = 0
                waitForNextCheck()
            }
            else -> complete()
        }
    }

    /** Waits a random 2 to 6 minutes for the next check, or finishes once the watch is over. */
    private fun waitForNextCheck() {
        val remaining = watchEnd - System.currentTimeMillis()
        if (remaining <= 60_000L) return complete()
        nextGap = minOf(Random.nextLong(2 * 60_000L, 6 * 60_000L + 1), remaining)
        enter(Phase.WAIT_AWAKE)
    }

    /** No answer within 60 seconds: you went back to sleep. Count it, pay for it, and start over. */
    private fun missedCheck() {
        if (practicing) return stopSelf() // in practice, a missed check just ends it
        val a = alarm!!
        if (!test) {
            val history = History(this)
            history.add(History.AWAKE_MISSED)
            log { plus("missed", 1) }
            // Going back to sleep costs the morning streak (and breaks the general streak for today).
            history.alarmStreak = 0
            if (a.level >= 3) {
                EmailReport.queuePartnerEmail(
                    this, EmailReport.WENT_BACK_TO_SLEEP,
                    "label" to a.label, "rang" to rangAt, "missed" to clockNow(), "tasks" to tasksDone(a),
                )
            }
        }
        wentBackToSleep = true
        enter(Phase.RING)
    }

    /** "walked 30 steps outside, did 10 push-ups and jogged 100 steps" */
    private fun tasksDone(a: Alarm): String {
        val list = mutableListOf("walked ${a.steps} steps outside")
        if (a.pushupsOn) list += "did ${a.pushups} push-ups"
        if (a.jogs) list += "jogged ${a.jogSteps} steps"
        if (a.pledge) list += "typed the daily pledge"
        return if (list.size == 1) list[0] else list.dropLast(1).joinToString(", ") + " and " + list.last()
    }

    private fun clockNow() = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun enter(next: Phase) {
        handler.removeCallbacks(timeUp)
        handler.removeCallbacks(nudge)
        sensors.unregisterListener(this)
        val a = alarm!!
        val previous = phase
        phase = next
        progress = 0
        hint = ""
        if (next != Phase.CHECKPOINT) {
            deadline = 0
            tooSlow = false
        }
        if (next != previous || next == Phase.RING) playSound() // before the nudge, which quiet() would cancel
        // Stopped mid-task? The alarm gets loud again (walking has its own time limit instead).
        handler.removeCallbacks(idleWatch)
        idleLoud = false
        lastProgress = SystemClock.elapsedRealtime()
        if (next in listOf(Phase.PUSHUPS, Phase.JOG, Phase.PLEDGE)) handler.postDelayed(idleWatch, 5000)
        when (next) {
            Phase.RING -> showScreen(alert = true)
            Phase.WALK -> {
                target = a.steps
                countdown(a.walkMinutes * 60_000L)
                startSteps()
                showScreen()
            }
            Phase.CHECKPOINT -> {
                // Same time limit as the walk; it keeps running.
                val left = deadline - System.currentTimeMillis()
                if (!tooSlow) {
                    if (left > 0) handler.postDelayed(timeUp, left) else {
                        tooSlow = true
                        playSound()
                    }
                }
                showScreen(alert = true)
            }
            Phase.PUSHUPS -> {
                pushMode = if (a.cameraPushups) PushMode.CAMERA else PushMode.SENSOR
                cameraReps = 0
                sensorReps = 0
                target = a.pushups
                near = false
                lastRep = 0
                lastUnsteady = SystemClock.elapsedRealtime()
                listen(Sensor.TYPE_ACCELEROMETER)
                if (pushMode != PushMode.CAMERA) listen(Sensor.TYPE_PROXIMITY)
                showScreen(alert = true)
            }
            Phase.JOG -> {
                target = a.jogSteps
                startJog()
                showScreen(alert = true)
            }
            Phase.PLEDGE -> showScreen(alert = true)
            Phase.WAIT_AWAKE -> {
                countdown(nextGap)
                // One tiny tick: "I'm counting."
                vibrator.vibrate(VibrationEffect.createOneShot(60, 80))
            }
            Phase.AWAKE_CHECK -> {
                countdown(60_000L)
                checkQuote = HONESTY.random()
                nudge.run() // soft buzz + beep now, and again after 30 seconds
                handler.postDelayed(nudge, 30_000L)
                showScreen(alert = true)
            }
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun countdown(ms: Long) {
        deadline = System.currentTimeMillis() + ms
        handler.postDelayed(timeUp, ms)
    }

    // ---- Sound: full until the first scan, softer during the tasks, silent for the awake watch ----

    private fun playSound() {
        quiet()
        when (phase) {
            Phase.RING -> ring(volume = 1f)
            Phase.WALK, Phase.CHECKPOINT -> if (tooSlow) ring(volume = 1f, harsh = true) else ring(volume = SOFT, vibrate = false)
            Phase.PUSHUPS -> {
                ring(volume = SOFT, vibrate = false)
                tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) // the per-push-up beep
            }
            Phase.JOG, Phase.PLEDGE -> ring(volume = SOFT, vibrate = false)
            else -> {}
        }
    }

    private fun ring(volume: Float, harsh: Boolean = false, vibrate: Boolean = true) {
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: Settings.System.DEFAULT_RINGTONE_URI
        player = MediaPlayer().apply {
            setAudioAttributes(alarmSound)
            try {
                setDataSource(this@AlarmService, sound)
            } catch (_: Exception) {
                setDataSource(this@AlarmService, Settings.System.DEFAULT_RINGTONE_URI)
            }
            isLooping = true
            prepare()
            setVolume(volume, volume)
            start()
        }
        handler.post(keepVolume)
        if (vibrate) {
            val pattern = if (harsh) longArrayOf(0, 1000, 150) else longArrayOf(0, 800, 600)
            val effect = VibrationEffect.createWaveform(pattern, intArrayOf(0, 255, 0), 0)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, alarmSound)
            }
        }
        if (harsh) {
            tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            handler.post(harshExtras)
        }
    }

    /** The task's quiet volume: a third of full, and push-ups fade it further with each one. */
    private fun softVolume() {
        if (idleLoud || tooSlow) return
        val v = if (phase == Phase.PUSHUPS && target > 0) maxOf(0.03f, SOFT * (target - progress) / target) else SOFT
        player?.setVolume(v, v)
    }

    /** Called on every push-up or jog step (and the pledge): if the alarm had got loud again, it calms down. */
    private fun madeProgress() {
        lastProgress = SystemClock.elapsedRealtime()
        if (idleLoud) {
            idleLoud = false
            hintUntil = 0 // clear "you stopped"
            vibrator.cancel()
            softVolume()
        }
    }

    /** No progress for a while during push-ups, jogging or the pledge: back to full volume, with vibration. */
    private val idleWatch = object : Runnable {
        override fun run() {
            val limit = if (phase == Phase.PLEDGE) PLEDGE_IDLE_MS else IDLE_MS
            if (!idleLoud && SystemClock.elapsedRealtime() - lastProgress > limit) {
                idleLoud = true
                player?.setVolume(1f, 1f)
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect, alarmSound)
                }
                say("You stopped, so the alarm is loud again. Keep going to quiet it.")
                hintUntil = SystemClock.elapsedRealtime() + 60_000
                showScreen(alert = true)
            }
            handler.postDelayed(this, 5000)
        }
    }

    /** The gentle awake-check signal: a short double buzz and one soft beep. Not meant to wake you. */
    private val nudge = Runnable {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 120, 150), -1))
        try {
            val soft = ToneGenerator(AudioManager.STREAM_ALARM, 30)
            soft.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            handler.postDelayed({ soft.release() }, 400)
        } catch (_: Exception) {
        }
    }

    private fun quiet() {
        handler.removeCallbacks(keepVolume)
        handler.removeCallbacks(harshExtras)
        player?.release()
        player = null
        tone?.release()
        tone = null
        setTorch(false)
        vibrator.cancel()
    }

    private fun setTorch(on: Boolean) {
        if (on == torchOn) return
        try {
            val camera = getSystemService(CameraManager::class.java)
            val id = torchId ?: camera.cameraIdList.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchId = id
            camera.setTorchMode(id, on)
            torchOn = on
        } catch (_: Exception) {
            // Flashlight busy (camera in use) or missing; the sound still works.
        }
    }

    // ---- Sensors ----

    private fun listen(type: Int): Boolean {
        val sensor = sensors.getDefaultSensor(type) ?: return false
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        return true
    }

    /**
     * Walking and jogging use the step sensor plus gravity (to catch flips). If steps can't be
     * counted (no sensor, or "Physical activity" turned off), the steps are skipped but it counts
     * as a shortcut.
     */
    private fun startSteps() {
        lastFlip = 0
        lastStepNs = 0
        gravity.clear()
        val allowed = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (!allowed || !listen(Sensor.TYPE_STEP_DETECTOR)) {
            if (!test && !shortcutCounted) {
                History(this).add(History.ALARMS_STOPPED)
                History(this).alarmStreak = 0
                shortcutCounted = true
            }
            say(
                if (allowed) "This phone can't count steps, so they're skipped." else
                    "Step counting is off, so steps are skipped. That counts as a shortcut. Allow \"Physical activity\" for Status Guard."
            )
            handler.postDelayed({ if (phase == Phase.WALK) nextTask() }, 4000)
            return
        }
        hasGravity = listen(Sensor.TYPE_GRAVITY)
        if (!hasGravity) listen(Sensor.TYPE_ACCELEROMETER)
    }

    /**
     * Jogging is counted from the motion sensor: each jogging step is a hard bounce (well over what
     * walking makes), and bounces must come at jogging pace. No permission needed.
     */
    private fun startJog() {
        lastFlip = 0
        lastBounce = 0
        bounceArmed = true
        gravity.clear()
        strongest = 0f
        if (!listen(Sensor.TYPE_ACCELEROMETER)) {
            say("This phone can't feel movement, so jogging is skipped.")
            handler.postDelayed({ if (phase == Phase.JOG) nextTask() }, 4000)
        }
    }

    private fun onBounce(v: FloatArray, now: Long) {
        val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        // The strongest bounce in the last second, shown on screen as "bounce strength".
        if (now - strongestAt > 1000 || mag > strongest) {
            strongest = mag
            strongestAt = now
        }
        if (mag < BOUNCE_RESET) bounceArmed = true
        if (!bounceArmed || mag < BOUNCE_HIT) return
        bounceArmed = false
        val gap = now - lastBounce
        lastBounce = now
        // No flip check here: the phone swings a lot when you jog, and faking it is a workout anyway.
        when {
            gap < 200 -> {} // too fast for feet: shaking
            gap > 900 -> say("Faster! Only jogging pace counts.")
            else -> countOne()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> onStep(event.timestamp, now)
            Sensor.TYPE_GRAVITY -> trackFlips(event.values, now)
            Sensor.TYPE_ACCELEROMETER -> when (phase) {
                Phase.PUSHUPS -> trackSteady(event.values, now)
                Phase.JOG -> onBounce(event.values, now)
                else -> trackFlips(event.values, now)
            }
            Sensor.TYPE_PROXIMITY -> {
                // Chest close to the screen = the bottom. Coming back up counts the push-up,
                // if you stayed down long enough (a quick hand wave is too short).
                val isNear = event.values[0] < minOf(event.sensor.maximumRange, 5f)
                if (isNear && !near) nearSince = now
                if (!isNear && near) {
                    if (now - nearSince < 250) say("Too quick. Go all the way down and up.") else tryRep()
                }
                near = isNear
            }
        }
    }

    /** A push-up counts only with the phone flat and still, and not faster than real push-ups. */
    private fun tryRep() {
        val now = SystemClock.elapsedRealtime()
        when {
            now - lastUnsteady < 700 -> say("Keep the phone flat and still on the floor.")
            now - lastRep < 900 -> say("Slower. Real push-ups, all the way down and up.")
            else -> {
                lastRep = now
                if (pushMode == PushMode.CAMERA) cameraReps++ else sensorReps++
                countOne()
            }
        }
    }

    /** Flat (screen up) and still, like a phone lying on the floor. */
    private fun trackSteady(v: FloatArray, now: Long) {
        val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        val flat = v[2] > 8f && hypot(v[0], v[1]) < 3f
        if (!flat || kotlin.math.abs(mag - SensorManager.GRAVITY_EARTH) > 1.2f) lastUnsteady = now
    }

    /** Remembers which way is down over the last second; a big turn means the phone was flipped. */
    private fun trackFlips(v: FloatArray, now: Long) {
        gravity.addLast(floatArrayOf(now.toFloat(), v[0], v[1], v[2]))
        while (gravity.size > 2 && now - gravity.first()[0] > 1000) gravity.removeFirst()
        val old = gravity.first()
        val dot = old[1] * v[0] + old[2] * v[1] + old[3] * v[2]
        val lengths = sqrt(old[1] * old[1] + old[2] * old[2] + old[3] * old[3]) * sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (lengths < 1f) return
        val degrees = Math.toDegrees(acos((dot / lengths).coerceIn(-1f, 1f).toDouble()))
        if (degrees > 100) {
            if (now - lastFlip > 1500) say("That looked like flipping, not walking. Those steps don't count.")
            lastFlip = now
        }
    }

    private fun onStep(stepNs: Long, now: Long) {
        val gapMs = if (lastStepNs == 0L) Long.MAX_VALUE else (stepNs - lastStepNs) / 1_000_000
        lastStepNs = stepNs
        if (now - lastFlip < 1500) return // flipping, not walking
        if (gapMs < 250) return // faster than any human step: shaking
        countOne()
    }

    /** Dark or no face found: count with the sensors instead, and do 50% more. */
    private fun goDark() {
        pushMode = PushMode.DARK
        target = (alarm!!.pushups * 3 + 1) / 2
        lastUnsteady = SystemClock.elapsedRealtime()
        listen(Sensor.TYPE_PROXIMITY)
        say("Too dark for the camera. Counting with the sensor now, so it's ${target} push-ups.")
    }

    private fun say(text: String) {
        hint = text
        hintUntil = SystemClock.elapsedRealtime() + 3500
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun countOne() {
        progress++
        madeProgress()
        if (phase == Phase.PUSHUPS) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200) // a loud beep for each push-up
        softVolume()
        if (progress >= target) {
            // Stop counting, and give the last beep a moment before moving on.
            sensors.unregisterListener(this)
            handler.postDelayed({ nextTask() }, 300)
        }
    }

    // ---- Screens and notifications ----

    /**
     * Brings the alarm screen to the front. With [alert] it also posts a full-screen
     * notification, which opens the screen even if Android blocks the direct way.
     */
    private fun showScreen(alert: Boolean = false) {
        try {
            startActivity(Intent(this, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        if (alert) getSystemService(NotificationManager::class.java).notify(ATTENTION_ID, notification(fullScreen = true))
    }

    private fun notification(fullScreen: Boolean = false): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Strict alarms", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, AlarmRingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (phase) {
            Phase.WALK -> "Walk $target steps outside"
            Phase.CHECKPOINT -> "Scan the checkpoint: ${alarm?.checkpointName.orEmpty()}"
            Phase.PUSHUPS -> "Do $target push-ups"
            Phase.JOG -> "Jog $target steps"
            Phase.PLEDGE -> "Type today's pledge"
            Phase.WAIT_AWAKE -> "Awake watch: random checks. Stay up!"
            Phase.AWAKE_CHECK -> "Still awake? Tap here"
            else -> "Scan your code to stop the alarm"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(alarm?.label ?: "Alarm")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(!fullScreen)
            .setContentIntent(open)
            .apply { if (fullScreen) setFullScreenIntent(open, true) }
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sensors.unregisterListener(this)
        quiet()
        getSystemService(NotificationManager::class.java).cancel(ATTENTION_ID)
        phase = null
        alarm = null
        if (!test) History(this).alarmRunning = false
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "strict_alarms"
        private const val NOTIFICATION_ID = 1
        private const val ATTENTION_ID = 2
        private const val SOFT = 0.35f // music volume after the first scan
        private const val IDLE_MS = 60_000L // no push-up or jog step for this long: loud again
        private const val PLEDGE_IDLE_MS = 120_000L
        const val BOUNCE_HIT = 14f // m/s²: a jogging step (still is 9.8, walking reaches about 11-13)
        private const val BOUNCE_RESET = 10.5f
        const val ACTION_STOP = "stop"
        const val ACTION_SCANNED = "scanned"
        const val ACTION_CHECKPOINT = "checkpoint"
        const val ACTION_CAMERA_REP = "camera_rep"
        const val ACTION_CAMERA_DARK = "camera_dark"
        const val ACTION_PLEDGE = "pledge"
        const val ACTION_AWAKE = "awake"

        // What the alarm screen shows. phase == null means no alarm is running.
        var phase: Phase? = null
            private set
        var alarm: Alarm? = null
            private set
        var isTest = false
            private set
        var progress = 0
            private set
        var target = 0
            private set
        var deadline = 0L
            private set
        var tooSlow = false
            private set
        var pushMode = PushMode.SENSOR
            private set
        var hint = "" // a short message like "That looked like flipping"
            private set
        var hintUntil = 0L
            private set
        var part = 0 // which task we're on ("Part 1 of 3")
            private set
        var parts = 0
            private set
        var watchEnd = 0L // when the awake watch is over
            private set
        var checksDone = 0
            private set
        var checkQuote = "" // the honesty line for the current awake check
            private set
        private var nextGap = 0L
        var strongest = 0f // jogging: strongest bounce in the last second
            private set
        private var strongestAt = 0L
        var practicing = false // running one step on its own
            private set

        /** Shown with each awake check. */
        val HONESTY = listOf(
            "You're doing this for yourself. Nobody else is watching.",
            "If you're tapping this from under the blanket, you're only fooling yourself.",
            "Future you will remember whether this tap was honest.",
            "Lying to an alarm is still lying to yourself.",
            "Sit up. Feet on the floor. Then tap.",
            "You'll be proud of this morning tonight. Or you won't. Your choice, right now.",
            "Winners are made in the first hour of the day.",
            "Go to the ant, you sluggard; consider its ways and be wise. (Proverbs 6:6)",
            "A little sleep, a little slumber... and poverty will come on you like a thief. (Proverbs 6:10-11)",
            "The bed will still be there tonight. Today won't wait.",
            "Be honest: are you up, or just tapping?",
        )

        private const val EXTRA_TEST = "test"
        private const val EXTRA_PRACTICE = "practice"

        fun practiceIntent(context: Context, step: String) =
            Intent(context, AlarmService::class.java).putExtra(EXTRA_PRACTICE, step)

        fun ringIntent(context: Context, id: Int, test: Boolean = false) =
            Intent(context, AlarmService::class.java).putExtra(AlarmScheduler.EXTRA_ID, id).putExtra(EXTRA_TEST, test)

        fun send(context: Context, action: String) =
            context.startService(Intent(context, AlarmService::class.java).setAction(action))
    }
}

/** The steps you can practise on their own from the alarms screen. */
object Practice {
    const val SCAN = "scan"
    const val WALK = "walk"
    const val CHECKPOINT = "checkpoint"
    const val PUSHUPS = "pushups"
    const val CAMERA = "camera"
    const val JOG = "jog"
    const val PLEDGE = "pledge"
    const val AWAKE = "awake"
}
