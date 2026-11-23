package com.personal.statusguard

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

/** Asks for the friend's password and calls [onSuccess] only if it is right. */
fun askPassword(activity: Activity, title: String, onSuccess: () -> Unit) {
    val prefs = Prefs(activity)
    val input = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        hint = "Password"
    }
    val box = FrameLayout(activity).apply {
        setPadding(60, 20, 60, 0)
        addView(input)
    }
    AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("Unlock") { _, _ ->
            if (prefs.checkPassword(input.text.toString())) onSuccess()
            else Toast.makeText(activity, "Wrong password", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Cancel", null)
        .show()
}
