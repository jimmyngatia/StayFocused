package com.personal.statusguard

import org.json.JSONObject
import java.time.LocalDate

/** One logged strict-alarm morning: when it rang, how fast you got up, what you did, how it ended. */
class Morning(val date: LocalDate, val json: JSONObject) {
    val label get() = json.optString("label", "Alarm")
    val level get() = json.optInt("level", 1)
    val rang get() = json.optString("rang")
    val upMinutes get() = if (json.has("upMin")) json.optInt("upMin") else null // ring → first scan
    val doneMinutes get() = if (json.has("doneMin")) json.optInt("doneMin") else null // ring → tasks finished
    val finishedAt get() = json.optString("finishedAt")
    val walked get() = json.optInt("walk")
    val checkpoint get() = json.optInt("checkpoint")
    val pushups get() = json.optInt("pushups")
    val cameraPushups get() = json.optInt("pushupsCamera")
    val darkPushups get() = json.optInt("pushupsDark")
    val jogged get() = json.optInt("jog")
    val pledges get() = json.optInt("pledge")
    val checks get() = json.optInt("checks")
    val missed get() = json.optInt("missed")
    val result get() = json.optString("result", UNFINISHED)

    val finished get() = result == DONE || result == DONE_AFTER_SLEEP

    /** "Done", "Back to sleep, then done", "Switched off"… for tables. */
    val resultText
        get() = when (result) {
            DONE -> "Done"
            DONE_AFTER_SLEEP -> "Slept again, then done"
            SWITCHED_OFF -> "Phone switched off"
            SHORTCUT -> "Stopped (test or shortcut)"
            else -> "Not finished"
        }

    /** Short result for tables. */
    val resultShort
        get() = when (result) {
            DONE -> "Done"
            DONE_AFTER_SLEEP -> "Slept again"
            SWITCHED_OFF -> "Switched off"
            SHORTCUT -> "Stopped"
            else -> "Not finished"
        }

    /** "10 (camera)", "15 (dark)", "–" */
    val pushupsText
        get() = when {
            pushups == 0 -> "–"
            cameraPushups == pushups -> "$pushups camera"
            darkPushups > 0 && cameraPushups == 0 -> "$pushups dark"
            cameraPushups > 0 -> "$pushups ($cameraPushups cam)"
            else -> "$pushups"
        }

    companion object {
        const val UNFINISHED = "unfinished"
        const val DONE = "done"
        const val DONE_AFTER_SLEEP = "done_after_sleep"
        const val SWITCHED_OFF = "switched_off"
        const val SHORTCUT = "shortcut"
    }
}

/** A week of mornings, added up for the report. */
class MorningsWeek(val mornings: List<Morning>) {
    val count get() = mornings.size
    val finished get() = mornings.count { it.finished }
    val walked get() = mornings.sumOf { it.walked }
    val pushups get() = mornings.sumOf { it.pushups }
    val cameraPushups get() = mornings.sumOf { it.cameraPushups }
    val darkPushups get() = mornings.sumOf { it.darkPushups }
    val jogged get() = mornings.sumOf { it.jogged }
    val pledges get() = mornings.sumOf { it.pledges }
    val checks get() = mornings.sumOf { it.checks }
    val missed get() = mornings.sumOf { it.missed }
    val checkpoints get() = mornings.sumOf { it.checkpoint }

    /** Average minutes from the alarm to the first scan. */
    val averageUp: Int? get() = mornings.mapNotNull { it.upMinutes }.takeIf { it.isNotEmpty() }?.average()?.toInt()

    /** Average minutes from the alarm to finishing the tasks. */
    val averageDone: Int? get() = mornings.mapNotNull { it.doneMinutes }.takeIf { it.isNotEmpty() }?.average()?.toInt()

    /** The cleanest morning: finished without sleeping again, fastest up. */
    val best: Morning?
        get() = mornings.filter { it.result == Morning.DONE && it.upMinutes != null }.minByOrNull { it.upMinutes!! * 100 + (it.doneMinutes ?: 0) }

    /** "level 4 on 5 mornings, level 2 on 1" */
    val levelsText: String
        get() = mornings.groupingBy { it.level }.eachCount().entries.sortedByDescending { it.key }
            .joinToString(", ") { (level, n) -> "level $level on $n ${if (n == 1) "morning" else "mornings"}" }
}
