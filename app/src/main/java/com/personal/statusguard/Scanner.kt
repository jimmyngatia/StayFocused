package com.personal.statusguard

import android.app.Activity
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Scans a barcode or QR code with our own camera screen (flashlight on) and passes the text to [onResult]. */
fun scanCode(activity: Activity, onResult: (String) -> Unit) = ScanActivity.code(activity, onResult = onResult)

/** Downloads the code, face and text readers ahead of time so they work offline when an alarm rings. */
fun prepareScanner(activity: Activity) {
    try {
        ModuleInstall.getClient(activity).installModules(
            ModuleInstallRequest.newBuilder()
                .addApi(BarcodeScanning.getClient())
                .addApi(FaceDetection.getClient())
                .addApi(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS))
                .build()
        )
    } catch (_: Exception) {
        // Play services will fetch them on first use instead.
    }
}
