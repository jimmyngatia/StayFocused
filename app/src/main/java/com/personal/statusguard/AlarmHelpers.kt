package com.personal.statusguard

import android.app.Activity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.time.LocalDate
import java.util.concurrent.Executors

/** The sentence you type at the end of a level 3 or 4 alarm. A different one each day. */
object Pledges {
    private val all = listOf(
        "I have not cheated. I am awake and I chose to rise.",
        "I know the importance of this morning, and I am standing up for it.",
        "I did not fool myself today. I really walked and I really did the push-ups.",
        "My bed can wait. My goals cannot.",
        "I am awake on purpose, and I will not go back to sleep.",
        "Discipline today is freedom tomorrow. I choose discipline.",
        "I keep the promises I make to myself, starting with this one.",
        "This hour belongs to God, to my goals and to me, not to my blanket.",
        "I did the work honestly. Nobody is watching but me, and that is enough.",
        "Little by little fills the measure. Today I add my part.",
        "I am not lying to myself. I am up, and I am staying up.",
        "Winners are made in the first hour of the day. I am in it now.",
    )

    /** Today's pledge: random, but the same all day. */
    fun today(): String = all[Math.floorMod(LocalDate.now().toEpochDay() * 7919, all.size.toLong()).toInt()]

    /** Typed right? Ignores capitals, extra spaces and punctuation. */
    fun matches(typed: String) = simplify(typed) == simplify(today())

    private fun simplify(text: String) =
        text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.isNotBlank() }.joinToString(" ")
}

/** Matches the words on a sign (the outside checkpoint) against what the camera reads. */
object SignWords {
    /** The words worth matching: letters and digits only, 3 or more characters, in capitals. */
    fun keyWords(text: String): List<String> =
        text.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.length >= 3 }.distinct()

    /** True when at least 70% of the saved words are seen. */
    fun matches(saved: String, seen: String): Boolean {
        val want = saved.split(" ").filter { it.isNotBlank() }
        if (want.isEmpty()) return false
        val got = keyWords(seen).toSet()
        return want.count { it in got } * 10 >= want.size * 7
    }
}

/**
 * Plain activities have no lifecycle for CameraX, so this small one stands in.
 * [start] when the camera should run, [stop] when it shouldn't.
 */
class CameraLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override fun getLifecycle(): Lifecycle = registry

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.currentState = Lifecycle.State.CREATED
    }
}

/**
 * Starts a camera with live analysis. [onFrame] runs on a background thread and must close the image.
 * [onReady] gets the camera (for the torch), [onError] if there's no such camera.
 */
fun startCamera(
    activity: Activity,
    owner: CameraLifecycle,
    front: Boolean,
    preview: PreviewView?,
    sharp: Boolean = false, // 720p frames: better for small barcodes and text
    onFrame: (ImageProxy) -> Unit,
    onReady: (Camera) -> Unit = {},
    onError: (Exception) -> Unit = {},
) {
    val future = ProcessCameraProvider.getInstance(activity)
    future.addListener({
        try {
            val provider = future.get()
            provider.unbindAll()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .apply {
                    if (sharp) setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        ).build()
                    )
                }
                .build()
                .also { it.setAnalyzer(Executors.newSingleThreadExecutor(), onFrame) }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val camera = if (preview != null) {
                val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                provider.bindToLifecycle(owner, selector, p, analysis)
            } else provider.bindToLifecycle(owner, selector, analysis)
            owner.start()
            onReady(camera)
        } catch (e: Exception) {
            onError(e)
        }
    }, activity.mainExecutor)
}

/** Stops whatever camera is running. */
fun stopCamera(activity: Activity, owner: CameraLifecycle) {
    owner.stop()
    try {
        ProcessCameraProvider.getInstance(activity).get().unbindAll()
    } catch (_: Exception) {
    }
}
