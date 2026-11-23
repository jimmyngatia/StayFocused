package com.personal.statusguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * The camera scanner, with the flashlight on so it works in the dark:
 * - code mode: returns the first barcode or QR code it sees;
 * - sign setup: shows the words it reads and saves them;
 * - sign check: finishes by itself once it sees the same sign.
 */
class ScanActivity : Activity() {
    private val camera = CameraLifecycle()
    private val barcodes by lazy { BarcodeScanning.getClient() }
    private val text by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var mode = MODE_CODE
    private var expected: String? = null
    private var found = listOf<String>()
    private var cam: Camera? = null
    private var torch = true
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setContentView(R.layout.activity_scan)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CODE
        expected = intent.getStringExtra(EXTRA_EXPECT)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()

        findViewById<TextView>(R.id.titleText).text = when {
            mode == MODE_CODE -> if (name.isEmpty()) "Point at the code" else "Scan the code: $name"
            expected != null -> "Point at the sign: $name"
            else -> "Point the camera at the sign"
        }
        findViewById<TextView>(R.id.statusText).text = if (mode == MODE_CODE) "Looking for a barcode or QR code…" else "Looking for words…"
        findViewById<Button>(R.id.useButton).apply {
            visibility = if (mode == MODE_SIGN && expected == null) View.VISIBLE else View.GONE
            isEnabled = false
            setOnClickListener { finishWith(found.joinToString(" ")) }
        }
        findViewById<Button>(R.id.torchButton).setOnClickListener {
            torch = !torch
            cam?.cameraControl?.enableTorch(torch)
            (it as Button).text = if (torch) "Flashlight off" else "Flashlight on"
        }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else {
            Toast.makeText(this, "Scanning needs the camera. Allow it for Status Guard.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun start() = startCamera(
        this, camera, front = false, preview = findViewById<PreviewView>(R.id.preview), sharp = true,
        onFrame = ::read,
        onReady = {
            cam = it
            it.cameraControl.enableTorch(torch) // on by default: works at dawn and without electricity
        },
        onError = {
            Toast.makeText(this, "Camera couldn't start: ${it.message}", Toast.LENGTH_LONG).show()
            finish()
        },
    )

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun read(frame: ImageProxy) {
        val media = frame.image
        if (media == null || done) return frame.close()
        val image = InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees)
        if (mode == MODE_CODE) {
            barcodes.process(image)
                .addOnSuccessListener { list -> list.firstNotNullOfOrNull { it.rawValue }?.let { if (!done) finishWith(it) } }
                .addOnCompleteListener { frame.close() }
        } else {
            text.process(image)
                .addOnSuccessListener { result -> showWords(result.text) }
                .addOnCompleteListener { frame.close() }
        }
    }

    private fun showWords(seen: String) {
        val want = expected
        if (want != null) {
            if (!done && SignWords.matches(want, seen)) finishWith(want)
            else findViewById<TextView>(R.id.statusText).text = "Hold it steady, close enough to read."
            return
        }
        val words = SignWords.keyWords(seen)
        if (words.size < 3) {
            findViewById<TextView>(R.id.statusText).text = "Looking for words… (need at least 3)"
            return
        }
        found = words.take(12)
        findViewById<TextView>(R.id.statusText).text = "Found: " + found.joinToString(" ")
        findViewById<Button>(R.id.useButton).isEnabled = true
    }

    private fun finishWith(value: String) {
        done = true
        val callback = pending
        pending = null
        finish()
        callback?.invoke(value)
    }

    override fun onDestroy() {
        stopCamera(this, camera)
        barcodes.close()
        text.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_EXPECT = "expect"
        private const val EXTRA_NAME = "name"
        private const val MODE_CODE = "code"
        private const val MODE_SIGN = "sign"

        /** Who gets the result. Only one scan runs at a time. */
        private var pending: ((String) -> Unit)? = null

        private fun open(activity: Activity, intent: Intent, onResult: (String) -> Unit) {
            pending = onResult
            activity.startActivity(intent)
        }

        /** Scans one barcode or QR code. */
        fun code(activity: Activity, name: String = "", onResult: (String) -> Unit) = open(
            activity, Intent(activity, ScanActivity::class.java).putExtra(EXTRA_MODE, MODE_CODE).putExtra(EXTRA_NAME, name), onResult
        )

        /** Reads a sign. With [expected] words it waits for that sign; without, it returns the words it found. */
        fun sign(activity: Activity, expected: String? = null, name: String = "", onResult: (String) -> Unit) = open(
            activity,
            Intent(activity, ScanActivity::class.java).putExtra(EXTRA_MODE, MODE_SIGN).putExtra(EXTRA_NAME, name)
                .apply { if (expected != null) putExtra(EXTRA_EXPECT, expected) },
            onResult,
        )
    }
}
