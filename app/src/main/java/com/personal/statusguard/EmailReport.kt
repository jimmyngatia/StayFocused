package com.personal.statusguard

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/** Gmail settings for sending the weekly report. Changed only with the password. */
class EmailSettings(context: Context) {
    private val sp = context.getSharedPreferences("email", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** The Gmail address that sends the report. */
    var sender: String
        get() = sp.getString("sender", "")!!
        set(v) = sp.edit().putString("sender", v).apply()

    /** Google "App password" (16 letters), not the normal Gmail password. */
    var appPassword: String
        get() = sp.getString("app_password", "")!!
        set(v) = sp.edit().putString("app_password", v).apply()

    /** Who receives it, separated by commas. */
    var recipients: String
        get() = sp.getString("recipients", "")!!
        set(v) = sp.edit().putString("recipients", v).apply()

    /** Day of the week to send (1 = Monday ... 7 = Sunday). Also the day of the report notification. */
    var day: Int
        get() = sp.getInt("day", 7)
        set(v) = sp.edit().putInt("day", v).apply()

    /** Time to send, minutes after midnight. */
    var minute: Int
        get() = sp.getInt("minute", 19 * 60)
        set(v) = sp.edit().putInt("minute", v).apply()

    /** What happened last time, shown in the settings ("Sent ✓ …" or "Failed: …"). */
    var lastResult: String
        get() = sp.getString("last_result", "")!!
        set(v) = sp.edit().putString("last_result", v).apply()

    /** Email the partners when you go back to sleep (levels 3-4) or switch the phone off during an alarm. On by default. */
    var wakeEmails: Boolean
        get() = sp.getBoolean("wake_emails", true)
        set(v) = sp.edit().putBoolean("wake_emails", v).apply()

    /** Gmail is set up, so emails can be sent at all. */
    val canSend get() = sender.isNotBlank() && appPassword.isNotBlank() && recipients.isNotBlank()

    val isReady get() = enabled && canSend
}

/** Sends the weekly report (message + PDF) through Gmail. */
object EmailReport {
    /** Sends right now. Runs the network work on the calling thread, so never call it on the main thread. */
    fun sendNow(context: Context, weekEnding: java.time.LocalDate = java.time.LocalDate.now()) {
        val report = WeeklyReport.build(context, weekEnding)
        val pdf = PdfReport.create(context, report)
        send(
            context,
            "Status Guard weekly report" + (if (report.name == "The user") "" else ": ${report.name}") + " · Grade ${report.grade} · ${report.rangeText}",
            report.messageText(),
            pdf,
        )
    }

    /** Sends one email through the saved Gmail account, with an optional attachment. */
    private fun send(context: Context, subject: String, body: String, attachment: java.io.File? = null) {
        val settings = EmailSettings(context)
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "20000")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(settings.sender.trim(), settings.appPassword.replace(" ", ""))
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.sender.trim(), "Status Guard"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(settings.recipients))
            setSubject(subject, "utf-8")
            setContent(MimeMultipart().apply {
                addBodyPart(MimeBodyPart().apply { setText(body, "utf-8") })
                if (attachment != null) addBodyPart(MimeBodyPart().apply {
                    dataHandler = DataHandler(FileDataSource(attachment))
                    fileName = attachment.name
                })
            })
        }
        Transport.send(message)
    }

    /**
     * Queues this week's send. It waits for internet (even across restarts), retries if Gmail fails,
     * and always reports the week it was queued for. Each week has its own place in the queue,
     * so being offline for a while never drops an older week's email.
     */
    fun queue(context: Context) {
        val week = java.time.LocalDate.now()
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, java.util.concurrent.TimeUnit.MINUTES)
            .setInputData(androidx.work.workDataOf("week" to week.toString()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("weekly_email_$week", ExistingWorkPolicy.KEEP, request)
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
        EmailSettings(context).lastResult = "Queued $time. It goes out as soon as there's internet."
    }

    const val WENT_BACK_TO_SLEEP = "sleep"
    const val SWITCHED_OFF = "off"

    /**
     * Tells the accountability partners about a failed morning: [kind] is [WENT_BACK_TO_SLEEP]
     * or [SWITCHED_OFF]. Queued, so it goes out as soon as there's internet.
     */
    fun queuePartnerEmail(context: Context, kind: String, vararg details: Pair<String, String>) {
        val settings = EmailSettings(context)
        if (!settings.canSend || !settings.wakeEmails) return
        val request = OneTimeWorkRequestBuilder<PartnerWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, java.util.concurrent.TimeUnit.MINUTES)
            .setInputData(androidx.work.workDataOf("kind" to kind, *details))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    class PartnerWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            val settings = EmailSettings(applicationContext)
            if (!settings.canSend) return Result.success()
            val name = Prefs(applicationContext).name.ifBlank { "your friend" }
            val label = inputData.getString("label") ?: "Alarm"
            val rang = inputData.getString("rang").orEmpty()
            val intro = "Hi,\n\nI'm $name's strict alarm (Status Guard). You're getting this because $name chose you as an accountability partner.\n\n"
            val (subject, story) = when (inputData.getString("kind")) {
                SWITCHED_OFF -> "$name switched off the phone to escape the alarm" to
                    "This morning the alarm \"$label\" started ringing at $rang. Before finishing it, the phone was switched off " +
                    "(or the app was force-stopped), which is the only way to escape it. So $name didn't get up the way they promised.\n\n" +
                    "It's counted in $name's weekly report, and the morning streak is back to 0."
                else -> "$name went back to sleep this morning" to
                    "This morning I woke $name up at $rang (\"$label\"). $name scanned the code and " +
                    "${inputData.getString("tasks") ?: "did the morning tasks"}. " +
                    "But at ${inputData.getString("missed").orEmpty()} I checked if $name was still awake, and nobody answered for a whole minute. " +
                    "So it looks like $name went back to sleep.\n\n" +
                    "The alarm is ringing again now and everything starts over. This also shows in $name's weekly report."
            }
            val body = intro + story + "\n\nA friendly message to $name later today might help.\n\n— Status Guard, sent automatically"
            return try {
                send(applicationContext, subject, body)
                Result.success()
            } catch (e: Exception) {
                if (runAttemptCount < 10) Result.retry() else Result.failure()
            }
        }
    }

    class SendWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            val settings = EmailSettings(applicationContext)
            if (!settings.isReady) return Result.success()
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
            val week = inputData.getString("week")?.let { java.time.LocalDate.parse(it) } ?: java.time.LocalDate.now()
            return try {
                sendNow(applicationContext, week)
                settings.lastResult = "Sent ✓ $time"
                Result.success()
            } catch (e: Exception) {
                settings.lastResult = "Not sent yet ($time): ${e.message}. Trying again."
                if (runAttemptCount < 10) Result.retry()
                else {
                    Routine.notify(
                        applicationContext, 12, "Weekly report email failed",
                        "Check the email settings in the report screen. ${e.message}",
                        Intent(applicationContext, ReportActivity::class.java)
                    )
                    Result.failure()
                }
            }
        }
    }
}
