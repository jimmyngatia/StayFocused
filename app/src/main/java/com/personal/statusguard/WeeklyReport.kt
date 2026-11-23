package com.personal.statusguard

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One social media app's week. */
class SocialAppWeek(val app: SocialApp, val secondsPerDay: List<Long>, val tries: Long, val forceOpens: Long) {
    val totalSeconds get() = secondsPerDay.sum()
}

/** TikTok, YouTube Shorts and Instagram over the week, against the shared daily limit. */
class SocialWeek(
    val enabled: Boolean,
    val limitMinutes: Int,
    val windows: String,
    val apps: List<SocialAppWeek>,
    val triesPerDay: List<Long>,
    val forcePerDay: List<Long>,
    val peakHour: Int?,
) {
    val secondsPerDay get() = apps.first().secondsPerDay.indices.map { i -> apps.sumOf { it.secondsPerDay[i] } }
    val totalSeconds get() = apps.sumOf { it.totalSeconds }
    val tries get() = triesPerDay.sum()
    val forceOpens get() = forcePerDay.sum()

    /** Days the whole pot was used up. */
    val fullDays get() = secondsPerDay.count { limitMinutes > 0 && it >= limitMinutes * 60L }
    val hasData get() = enabled || totalSeconds > 0 || tries > 0 || forceOpens > 0
}

/** "1h 05m" / "12m" from seconds. */
fun formatSeconds(seconds: Long) = PhoneUsage.format(seconds * 1000)

/** The last 7 days, analysed: phone time, status, social media, attempts, mornings, sleep, and a grade. */
class WeeklyReport(
    val days: List<LocalDate>,
    val phonePerDay: List<Long>,
    val statusMinutesPerDay: List<Long>,
    val attemptsPerDay: List<Long>,
    val topApps: List<Pair<String, Long>>,
    val hasPhoneData: Boolean,
    val phoneTotal: Long,
    val phoneChange: Int?, // % compared with last week, null if unknown
    val unlocks: Long,
    val statusAttempts: Long,
    val sleepAttempts: Long,
    val sleepEmergency: Long, // force bypasses of the night screen
    val sleepMsPerDay: List<Long>, // sleep counted for the night ending on each day
    val sleepBypassPerDay: List<Long>,
    val resisted: Long,
    val peakHour: Int?,
    val alarmsDone: Long,
    val alarmsStopped: Long,
    val alarmsEscaped: Long,
    val backToSleepDays: Int, // mornings an awake check was missed
    val alarmsPaused: Long, // "switch off for tomorrow"
    val mornings: MorningsWeek, // the morning log: what happened with each alarm
    val awakeMissed: Long, // missed awake checks in total
    val hasSleepSchedule: Boolean,
    val score: Int,
    val grade: String,
    val gradeComment: String,
    val whatsappStreak: Int,
    val planLog: List<String?>, // per day: "4 of 6 done: ✓ Study, ✗ Call mum", or null if nothing planned
    val planDone: Int,
    val planTotal: Int,
    val devotionLog: List<String?>, // per day: what I read, "not done", or null (no devotion that day)
    val devotionDone: Int,
    val devotionDays: Int, // days that had devotion time or a log
    val devotionExtras: Long, // "Open anyway" during devotion time
    val devotionTries: Long,
    val devotionStreak: Int,
    val hasDevotion: Boolean,
    val sleepStreak: Int,
    val focusSeconds: Long, // WhatsApp time used during focus times
    val focusTries: Long, // WhatsApp covered during focus times
    val focusExtras: Long, // "Open anyway" during focus times
    val hasFocusTimes: Boolean,
    val alarmStreak: Int,
    val social: SocialWeek,
    val socialStreak: Int,
    val overallStreak: Int,
    val cleanDays: Int, // days this week with every rule kept
    val name: String, // whose report it is (from Account), "The user" if not set
) {
    val attemptsTotal get() = attemptsPerDay.sum()
    val sleepNights get() = sleepMsPerDay.count { it >= 60_000 }
    val sleepAverage get() = if (sleepNights == 0) 0L else sleepMsPerDay.sum() / sleepNights
    val statusTotal get() = statusMinutesPerDay.sum()

    /** e.g. "10 Nov – 16 Nov 2026" */
    val rangeText: String
        get() = days.first().format(DateTimeFormatter.ofPattern("d MMM")) + " – " +
            days.last().format(DateTimeFormatter.ofPattern("d MMM yyyy"))

    fun dayName(i: Int): String = days[i].dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

    /** One line for notifications and the email subject. */
    val headline: String
        get() = (if (hasPhoneData) "${PhoneUsage.format(phoneTotal)} phone time" +
            (phoneChange?.let { if (it >= 0) " (+$it%)" else " ($it%)" } ?: "") + " · " else "") +
            "$attemptsTotal blocked attempts · $alarmsDone alarms finished" +
            (if (social.forceOpens > 0) " · ${social.forceOpens} force opens" else "")

    /**
     * The week in plain, first-person words ("This week I used my phone for…"),
     * grouped by topic. Used in the PDF, the email and the report screen.
     */
    fun story(): List<Pair<String, String>> {
        val parts = mutableListOf<Pair<String, String>>()

        parts += "Highlights" to highlights()

        if (hasDevotion) parts += "Devotions" to buildString {
            append(if (devotionDays == 0) "I had no devotion days this week. " else "I did my devotions on $devotionDone of $devotionDays days. ")
            val lines = days.indices.mapNotNull { i -> devotionLog[i]?.let { "${dayName(i)}: $it" } }
            if (lines.isNotEmpty()) append("\n" + lines.joinToString("\n") + "\n")
            append("My devotion streak is ${if (devotionStreak == 1) "1 day" else "$devotionStreak days"}. ")
            if (devotionTries > 0) append("During devotion time other apps were closed on me $devotionTries times. ")
            if (devotionExtras > 0) append("I opened them anyway $devotionExtras times.")
        }

        if (planTotal > 0) parts += "My days" to buildString {
            append("I planned $planTotal things this week and did $planDone of them.")
            val lines = days.indices.mapNotNull { i -> planLog[i]?.let { "${dayName(i)}: $it" } }
            if (lines.isNotEmpty()) append("\n" + lines.joinToString("\n"))
        }

        parts += "Phone" to if (hasPhoneData && phoneTotal > 0) {
            val most = phonePerDay.indices.maxBy { phonePerDay[it] }
            val least = phonePerDay.indices.minBy { phonePerDay[it] }
            buildString {
                append("This week I used my phone for ${PhoneUsage.format(phoneTotal)}, about ${PhoneUsage.format(phoneTotal / 7)} a day. ")
                when {
                    phoneChange == null -> {}
                    phoneChange <= -5 -> append("That is ${-phoneChange}% less than last week. ")
                    phoneChange >= 5 -> append("That is $phoneChange% more than last week. ")
                    else -> append("That is about the same as last week. ")
                }
                append("My heaviest day was ${dayName(most)} (${PhoneUsage.format(phonePerDay[most])}) and my lightest was ${dayName(least)} (${PhoneUsage.format(phonePerDay[least])}). ")
                if (unlocks > 0) append("I unlocked my phone $unlocks times, about ${unlocks / 7} times a day. ")
                if (topApps.isNotEmpty()) {
                    append("Most of my time went to ")
                    append(topApps.take(3).joinToString(", ") { (app, ms) ->
                        "$app (${PhoneUsage.format(ms)}, ${ms * 100 / maxOf(1, phoneTotal)}%)"
                    })
                    append(".")
                }
            }
        } else "My phone time isn't available this week (usage access is off)."

        parts += "WhatsApp status" to buildString {
            append("I watched $statusTotal minutes of WhatsApp status. ")
            if (statusAttempts == 0L) append("I didn't try to open status outside my rules even once. ")
            else {
                append("I tried to open it outside my rules $statusAttempts times and was blocked")
                append(peakHour?.let { ", mostly around %02d:00. ".format(it) } ?: ". ")
            }
            if (resisted > 0) append("$resisted times I paused and chose not to open status at all.")
        }

        if (hasFocusTimes || focusExtras > 0) parts += "WhatsApp focus times" to buildString {
            append("During my WhatsApp focus times I used WhatsApp for ${formatSeconds(focusSeconds)}. ")
            append(if (focusTries == 0L) "It never had to be closed. " else "It was closed on me $focusTries times when my quick-check minutes were used up. ")
            append(if (focusExtras == 0L) "I never opened it anyway." else "I opened it anyway $focusExtras times.")
        }

        if (social.hasData) parts += "Social media" to buildString {
            val s = social
            val used = s.apps.filter { it.totalSeconds > 0 }
            if (s.totalSeconds == 0L) append("I spent no time at all on TikTok, YouTube Shorts or Instagram. ")
            else {
                append("I spent ${formatSeconds(s.totalSeconds)} on entertainment apps, about ${formatSeconds(s.totalSeconds / 7)} a day")
                if (s.enabled) append(", against my ${s.limitMinutes}-minute daily limit")
                append(". That was " + used.sortedByDescending { it.totalSeconds }
                    .joinToString(", ") { "${it.app.label} ${formatSeconds(it.totalSeconds)}" } + ". ")
                if (s.fullDays > 0) append("I used up my whole limit on ${s.fullDays} ${if (s.fullDays == 1) "day" else "days"}. ")
            }
            if (s.tries == 0L) append("I never tried to open them outside my allowed times. ")
            else {
                append("I tried to open them outside my allowed times ${s.tries} times and was blocked (" +
                    s.apps.filter { it.tries > 0 }.joinToString(", ") { "${it.app.label} ${it.tries}" } + ")")
                append(s.peakHour?.let { ", mostly around %02d:00. ".format(it) } ?: ". ")
            }
            if (s.forceOpens == 0L) append("I never used force open.")
            else append("I force-opened them ${s.forceOpens} times (" +
                s.apps.filter { it.forceOpens > 0 }.joinToString(", ") { "${it.app.label} ${it.forceOpens}" } +
                "), and each one reset my social streak.")
        }

        val alarms = alarmsDone + alarmsStopped + alarmsEscaped
        parts += "Mornings" to when {
            alarms == 0L -> "I had no strict alarms this week."
            alarmsStopped + alarmsEscaped == 0L -> "I finished all $alarmsDone of my strict alarms properly, with no shortcuts."
            else -> "I finished $alarmsDone of $alarms strict alarms properly. " +
                (if (alarmsStopped > 0) "$alarmsStopped times I took a shortcut (stopped it, or had step counting switched off). " else "") +
                (if (alarmsEscaped > 0) "$alarmsEscaped times I escaped by switching the phone off." else "")
        } + (if (alarmsPaused > 0) " I switched an alarm off for the day $alarmsPaused ${if (alarmsPaused == 1L) "time" else "times"}." else "") + when {
            alarms == 0L -> ""
            backToSleepDays == 0 -> " I answered every awake check."
            else -> " On $backToSleepDays ${if (backToSleepDays == 1) "morning" else "mornings"} I went back to sleep after the alarm " +
                "and missed an awake check ($awakeMissed missed in total), so the alarm had to ring again."
        } + morningDetails()

        if (hasSleepSchedule) parts += "Sleep" to buildString {
            if (sleepNights > 0) {
                val best = sleepMsPerDay.indices.maxBy { sleepMsPerDay[it] }
                append("This week I slept with my phone untouched for ${PhoneUsage.format(sleepAverage)} a night on average, over $sleepNights nights. ")
                append("My longest night was before ${dayName(best)} (${PhoneUsage.format(sleepMsPerDay[best])}). ")
            } else append("No sleep was counted this week. ")
            append(if (sleepAttempts == 0L) "I didn't try to open any apps during sleep time" else "During sleep time I tried to open apps $sleepAttempts times")
            append(if (sleepEmergency > 0) ", and I forced the phone open $sleepEmergency times." else ", and I never forced the phone open.")
        }

        fun days(n: Int) = if (n == 1) "1 day" else "$n days"
        parts += "Streaks" to "My general streak is ${days(overallStreak)} with every streak held, and I had no slips on $cleanDays of the last 7 days. " +
            "My WhatsApp streak is ${days(whatsappStreak)}, I have $alarmStreak mornings in a row, and ${days(sleepStreak)} without forcing my phone open at night" +
            (if (social.hasData) ". My social media streak is ${days(socialStreak)}." else ".")

        return parts
    }

    /** What I actually did in the mornings, from the alarm log. Empty if nothing was logged. */
    private fun morningDetails(): String {
        val m = mornings
        if (m.count == 0) return ""
        return buildString {
            append("\n\nMy alarm log has ${m.count} ${if (m.count == 1) "morning" else "mornings"} (${m.levelsText}). ")
            val up = m.averageUp
            val done = m.averageDone
            if (up != null) {
                append("On average I was up and scanning ${minutes(up)} after the alarm rang")
                append(if (done != null && done > up) ", and done with all my tasks after ${minutes(done)}. " else ". ")
            }
            val did = mutableListOf<String>()
            if (m.walked > 0) did += "walked ${m.walked} steps outside"
            if (m.checkpoints > 0) did += "reached my outside checkpoint ${m.checkpoints} ${if (m.checkpoints == 1) "time" else "times"}"
            if (m.pushups > 0) did += "did ${m.pushups} push-ups" + when {
                m.cameraPushups > 0 && m.darkPushups > 0 -> " (${m.cameraPushups} watched by the camera, ${m.darkPushups} counted in the dark)"
                m.cameraPushups > 0 -> " (${m.cameraPushups} watched by the camera)"
                m.darkPushups > 0 -> " (${m.darkPushups} counted in the dark)"
                else -> ""
            }
            if (m.jogged > 0) did += "jogged ${m.jogged} steps"
            if (m.pledges > 0) did += "typed my pledge ${m.pledges} ${if (m.pledges == 1) "time" else "times"}"
            if (did.isNotEmpty()) append("In total I " + (if (did.size == 1) did[0] else did.dropLast(1).joinToString(", ") + " and " + did.last()) + ". ")
            val asked = m.checks + m.missed
            if (asked > 0) append("I answered ${m.checks} of $asked awake checks. ")
            m.best?.let {
                val day = it.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                append("My best morning was $day: I was up ${minutes(it.upMinutes ?: 0)} after the ${it.rang} alarm")
                append(if (it.finishedAt.isNotEmpty()) " and done with everything by ${it.finishedAt}." else ".")
            }
        }
    }

    private fun minutes(n: Int) = if (n <= 0) "under a minute" else if (n == 1) "1 minute" else "$n minutes"

    /** A few wins and a few concerns, in short plain words. */
    fun highlights(): String {
        val wins = mutableListOf<String>()
        val watch = mutableListOf<String>()
        val alarms = alarmsDone + alarmsStopped + alarmsEscaped
        if (alarms > 0 && alarmsStopped + alarmsEscaped == 0L && backToSleepDays == 0) wins += "I finished every strict alarm without going back to sleep"
        if (mornings.pushups > 0 || mornings.jogged > 0) wins += "I did " + listOfNotNull(
            mornings.pushups.takeIf { it > 0 }?.let { "$it push-ups" },
            mornings.jogged.takeIf { it > 0 }?.let { "$it jogging steps" },
        ).joinToString(" and ") + " before breakfast"
        if (cleanDays >= 5) wins += "I kept every rule on $cleanDays of 7 days"
        if (phoneChange != null && phoneChange <= -5) wins += "my phone time went down ${-phoneChange}% from last week"
        if (social.hasData && social.forceOpens == 0L) wins += "I never force-opened social media"
        if (sleepNights > 0 && sleepAverage >= 7 * 3_600_000L) wins += "I slept over 7 hours a night with my phone untouched"

        if (backToSleepDays > 0) watch += "I went back to sleep after the alarm on $backToSleepDays ${if (backToSleepDays == 1) "morning" else "mornings"}"
        if (alarmsEscaped > 0) watch += "I switched the phone off during an alarm $alarmsEscaped ${if (alarmsEscaped == 1L) "time" else "times"}"
        if (social.forceOpens > 0) watch += "I force-opened social media ${social.forceOpens} times"
        if (phoneChange != null && phoneChange >= 10) watch += "my phone time went up $phoneChange% from last week"
        if (alarmsPaused > 0) watch += "I switched alarms off for a day $alarmsPaused ${if (alarmsPaused == 1L) "time" else "times"}"
        if (attemptsTotal > 0 && peakHour != null) watch += "I was most tempted around %02d:00".format(peakHour)

        fun list(items: List<String>) = items.take(3).joinToString("; ").replaceFirstChar { it.uppercase() } + "."
        return (if (wins.isEmpty()) "Wins: none this week." else "Wins: " + list(wins)) +
            "\n" + (if (watch.isEmpty()) "Concerns: none this week." else "Concerns: " + list(watch))
    }

    /** The story with an icon per topic, for the report screen. */
    fun insights(): List<Triple<Int, String, String>> = story().map { (topic, text) ->
        val icon = when (topic) {
            "Phone" -> R.drawable.ic_phone
            "WhatsApp status" -> R.drawable.ic_chat
            "Mornings" -> R.drawable.ic_sun
            "Highlights" -> R.drawable.ic_flag
            "Devotions" -> R.drawable.ic_book
            "My days" -> R.drawable.ic_check
            "Sleep" -> R.drawable.ic_moon
            "Social media" -> R.drawable.ic_play
            else -> R.drawable.ic_flame
        }
        Triple(icon, topic, text)
    }

    /** The email / share message: just the grade and streaks. Everything else is in the PDF. */
    fun messageText(): String {
        fun days(n: Int) = if (n == 1) "1 day" else "$n days"
        return "Status Guard weekly report" + (if (name == "The user") "" else " for $name") + " · $rangeText\n\n" +
            "Grade $grade ($score/100): $gradeComment\n\n" +
            "My streak: ${days(overallStreak)} with every rule kept ($cleanDays of the last 7 days)\n" +
            (if (planTotal > 0) "My days: $planDone of $planTotal planned things done\n" else "") +
            (if (hasDevotion) "Devotions: $devotionDone of $devotionDays days  ·  streak ${days(devotionStreak)}\n" else "") +
            "Mornings: $alarmStreak in a row\n" +
            "WhatsApp: ${days(whatsappStreak)}  ·  Sleep: ${days(sleepStreak)}" +
            (if (social.hasData) "  ·  Social media: ${days(socialStreak)}" else "") +
            "\n\nThe full report is in the attached PDF."
    }

    companion object {
        /** The 7 days ending on [today]. A late email still reports the week it was meant for. */
        fun build(context: Context, today: LocalDate = LocalDate.now()): WeeklyReport {
            val h = History(context)
            val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val lastWeek = (13 downTo 7).map { today.minusDays(it.toLong()) }

            // Save phone time into our own history, since Android forgets it after about a week.
            val usage = PhoneUsage.days(context, today.minusDays(13), today)
            for ((date, day) in usage) {
                h.keepMax(date, History.PHONE_MS, day.totalMs)
                h.keepMax(date, History.UNLOCKS, day.unlocks.toLong())
            }
            fun total(dates: List<LocalDate>, field: String) = dates.sumOf { h.get(it, field) }

            val phone = week.map { h.get(it, History.PHONE_MS) }
            val phoneTotal = phone.sum()
            val lastPhone = total(lastWeek, History.PHONE_MS)
            val change = if (lastPhone > 0) ((phoneTotal - lastPhone) * 100 / lastPhone).toInt() else null

            val topApps = usage.filterKeys { it in week }.values
                .flatMap { it.perApp.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { it.value.sum() }
                .entries.sortedByDescending { it.value }.take(5)
                .map { PhoneUsage.appName(context, it.key) to it.value }

            val statusAttempts = total(week, History.STATUS_ATTEMPTS)
            val sleepAttempts = total(week, History.SLEEP_ATTEMPTS)
            val sleepEmergency = total(week, History.SLEEP_EMERGENCY)
            val alarmsStopped = total(week, History.ALARMS_STOPPED)
            val alarmsEscaped = total(week, History.ALARMS_ESCAPED)
            val focusTries = total(week, History.WA_FOCUS_TRIES)
            val focusExtras = total(week, History.WA_EXTRA)

            val socialPrefs = SocialPrefs(context)
            val social = SocialWeek(
                enabled = socialPrefs.enabled,
                limitMinutes = socialPrefs.limitMinutes,
                windows = socialPrefs.timeWindows,
                apps = SocialApp.all(context).map { app ->
                    SocialAppWeek(app, week.map { h.get(it, app.secondsField) }, total(week, app.triesField), total(week, app.forceField))
                },
                triesPerDay = week.map { d -> SocialApp.all(context).sumOf { h.get(d, it.triesField) } },
                forcePerDay = week.map { d -> SocialApp.all(context).sumOf { h.get(d, it.forceField) } },
                peakHour = (0..23).maxByOrNull { total(week, "social_hour_$it") }?.takeIf { total(week, "social_hour_$it") > 0 },
            )

            // ---- Grade ----
            var score = 100
            score -= minOf(40, 4 * (statusAttempts + sleepAttempts + social.tries + focusTries).toInt())
            score -= 8 * focusExtras.toInt()
            val plans = PlanStore(context).let { p -> week.map { p.items(it) } }
            val devotion = DevotionStore(context)
            devotion.settleMissed()
            val devotionLog = week.map { d ->
                when {
                    devotion.isDone(d) -> devotion.entry(d) ?: "done"
                    devotion.isMissed(d) -> "not done"
                    d == today && devotion.isScheduled(d) -> "not logged yet"
                    else -> null
                }
            }
            val devotionDays = week.count { devotion.isScheduled(it) || devotion.isDone(it) }
            val devotionMissed = week.count { devotion.isMissed(it) }
            val devotionExtras = total(week, History.DEV_EXTRA)
            score -= 8 * devotionMissed + 8 * devotionExtras.toInt()
            score -= 8 * social.forceOpens.toInt()
            score -= 10 * (alarmsStopped + alarmsEscaped).toInt()
            val backToSleepDays = week.count { h.get(it, History.AWAKE_MISSED) > 0 }
            score -= 10 * backToSleepDays
            score -= 8 * sleepEmergency.toInt()
            if (change != null && change <= -10) score += 5
            if (change != null && change >= 10) score -= 10
            score = score.coerceIn(0, 100)
            val (grade, comment) = when {
                score >= 90 -> "A" to "Excellent week. Nearly every rule kept."
                score >= 75 -> "B" to "Good week, with a few slips."
                score >= 60 -> "C" to "Average week, with several slips."
                score >= 45 -> "D" to "Weak week. Many rules broken."
                else -> "E" to "Poor week. Most rules broken."
            }

            return WeeklyReport(
                days = week,
                phonePerDay = phone,
                statusMinutesPerDay = week.map { h.get(it, History.STATUS_SECONDS) / 60 },
                attemptsPerDay = week.indices.map { i ->
                    h.get(week[i], History.STATUS_ATTEMPTS) + h.get(week[i], History.SLEEP_ATTEMPTS) +
                        h.get(week[i], History.WA_FOCUS_TRIES) + h.get(week[i], History.DEV_TRIES) + social.triesPerDay[i]
                },
                topApps = topApps,
                hasPhoneData = PhoneUsage.hasAccess(context),
                phoneTotal = phoneTotal,
                phoneChange = change,
                unlocks = total(week, History.UNLOCKS),
                statusAttempts = statusAttempts,
                sleepAttempts = sleepAttempts,
                sleepEmergency = sleepEmergency,
                sleepMsPerDay = week.map { h.get(it, History.SLEEP_MS) },
                sleepBypassPerDay = week.map { h.get(it, History.SLEEP_EMERGENCY) },
                resisted = total(week, History.RESISTED),
                peakHour = (0..23).maxByOrNull { total(week, "hour_$it") }?.takeIf { total(week, "hour_$it") > 0 },
                alarmsDone = total(week, History.ALARMS_DONE),
                alarmsStopped = alarmsStopped,
                alarmsEscaped = alarmsEscaped,
                backToSleepDays = backToSleepDays,
                alarmsPaused = total(week, History.ALARMS_PAUSED),
                mornings = MorningsWeek(week.flatMap { d -> h.mornings(d).map { Morning(d, it) } }),
                awakeMissed = total(week, History.AWAKE_MISSED),
                hasSleepSchedule = SleepStore(context).schedules().isNotEmpty(),
                score = score,
                grade = grade,
                gradeComment = comment,
                whatsappStreak = h.whatsappStreak(),
                planLog = plans.map { list ->
                    if (list.isEmpty()) null
                    else "${list.count { it.done }} of ${list.size} done: " + list.joinToString(", ") { (if (it.done) "✓ " else "✗ ") + it.text }
                },
                planDone = plans.sumOf { l -> l.count { it.done } },
                planTotal = plans.sumOf { it.size },
                devotionLog = devotionLog,
                devotionDone = week.count { devotion.isDone(it) },
                devotionDays = devotionDays,
                devotionExtras = devotionExtras,
                devotionTries = total(week, History.DEV_TRIES),
                devotionStreak = devotion.streak(),
                hasDevotion = devotion.block != null || week.any { devotion.isDone(it) },
                sleepStreak = h.sleepStreak(),
                focusSeconds = total(week, History.WA_FOCUS_SECONDS),
                focusTries = focusTries,
                focusExtras = focusExtras,
                hasFocusTimes = FocusStore(context).blocks().isNotEmpty(),
                alarmStreak = h.alarmStreak,
                social = social,
                socialStreak = h.socialStreak(),
                overallStreak = h.overallStreak(),
                name = Prefs(context).name.ifBlank { "The user" },
                cleanDays = week.count { !h.slipped(it) && !it.isBefore(h.firstDay) },
            )
        }
    }
}
