package com.personal.statusguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** One point on a day's plan, e.g. "Study for exam" at 14:00. [time] is minutes after midnight, or null. */
data class PlanItem(val id: Long, val text: String, val time: Int?, val done: Boolean) {
    fun timeText() = time?.let { "%02d:%02d".format(it / 60, it % 60) }
}

/**
 * My day: a list of things to do for each day, with an optional time each. No reminders.
 * Tick them off in the app or on the home screen widget; the weekly report shows how each day went.
 */
class PlanStore(private val context: Context) {
    private val sp = context.getSharedPreferences("planner", Context.MODE_PRIVATE)

    /** The day's points: the ones with a time first (by time), then the rest in the order added. */
    fun items(date: LocalDate): List<PlanItem> {
        importIntention(date)
        val array = JSONArray(sp.getString("items_$date", "[]"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            PlanItem(o.getLong("id"), o.getString("text"), o.optInt("time", -1).takeIf { it >= 0 }, o.getBoolean("done"))
        }.sortedWith(compareBy<PlanItem> { it.time == null }.thenBy { it.time ?: 0 }.thenBy { it.id })
    }

    private fun write(date: LocalDate, list: List<PlanItem>) {
        val array = JSONArray()
        for (p in list) array.put(JSONObject().put("id", p.id).put("text", p.text).put("time", p.time ?: -1).put("done", p.done))
        sp.edit().putString("items_$date", array.toString()).apply()
        PlannerWidget.refresh(context)
    }

    private fun newId() = System.nanoTime()

    fun add(date: LocalDate, text: String, time: Int?) = write(date, items(date) + PlanItem(newId(), text, time, false))

    fun update(date: LocalDate, item: PlanItem) = write(date, items(date).map { if (it.id == item.id) item else it })

    fun delete(date: LocalDate, id: Long) = write(date, items(date).filter { it.id != id })

    fun toggle(date: LocalDate, id: Long) = write(date, items(date).map { if (it.id == id) it.copy(done = !it.done) else it })

    // ---- Leftovers: yesterday's unticked points, offered once for today ----

    fun leftovers(today: LocalDate = LocalDate.now()): List<PlanItem> {
        if (sp.getBoolean("offered_$today", false)) return emptyList()
        return items(today.minusDays(1)).filter { !it.done }
    }

    fun moveLeftovers(today: LocalDate = LocalDate.now()) {
        val left = leftovers(today)
        val existing = items(today).map { it.text }.toSet()
        write(today, items(today) + left.filter { it.text !in existing }.map { it.copy(id = newId(), done = false) })
        sp.edit().putBoolean("offered_$today", true).apply()
    }

    fun dismissLeftovers(today: LocalDate = LocalDate.now()) = sp.edit().putBoolean("offered_$today", true).apply()

    /**
     * The old "Tomorrow, I will…" line becomes the day's list the first time that day is opened,
     * one point per comma or line ("Read, study 4 exams, Do devotions" → 3 points).
     */
    private fun importIntention(date: LocalDate) {
        if (sp.contains("items_$date") || sp.getBoolean("imported_$date", false)) return
        sp.edit().putBoolean("imported_$date", true).apply()
        val text = Words(context).intentionFor(date).trim()
        if (text.isEmpty()) return
        val points = text.split(",", "\n", ";").map { it.trim() }.filter { it.isNotEmpty() }
        val array = JSONArray()
        points.forEachIndexed { i, p -> array.put(JSONObject().put("id", i.toLong()).put("text", p).put("time", -1).put("done", false)) }
        sp.edit().putString("items_$date", array.toString()).apply()
    }

    /** "3 of 5 done", or null when nothing is planned. */
    fun summary(date: LocalDate): String? {
        val list = items(date)
        if (list.isEmpty()) return null
        return "${list.count { it.done }} of ${list.size} done"
    }
}
