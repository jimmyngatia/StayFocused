package com.personal.statusguard

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Account: your name, and the one password that protects every setting. Opening it needs the current password. */
class PasswordActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password)
        prefs = Prefs(this)
        findViewById<TextView>(R.id.passwordHint).text =
            if (prefs.hasPassword()) "Leave both boxes empty to keep the current password."
            else "There is no way to reset it, so your friend should keep it safe."
        findViewById<EditText>(R.id.yourName).setText(prefs.name)
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
    }

    /** Leaving closes this screen, so it can't be reopened from recent apps without the password. */
    override fun onStop() {
        super.onStop()
        finish()
    }

    private fun save() {
        val password = findViewById<EditText>(R.id.newPassword).text.toString()
        val confirm = findViewById<EditText>(R.id.confirmPassword).text.toString()
        prefs.name = findViewById<EditText>(R.id.yourName).text.toString()
        if (password.isEmpty() && confirm.isEmpty() && prefs.hasPassword()) {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            return finish()
        }
        val problem = when {
            password.length < 8 -> "The password needs at least 8 characters."
            password != confirm -> "The two passwords don't match."
            else -> null
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }
        prefs.setPassword(password)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
