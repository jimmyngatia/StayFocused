package com.personal.statusguard

import android.content.Context
import org.json.JSONArray
import java.time.LocalDate

/** Your own whys and quotes, shown on the lockout, pause, sleep and alarm screens. */
class Words(context: Context) {
    private val appContext = context.applicationContext
    private val sp = context.getSharedPreferences("words", Context.MODE_PRIVATE)

    var whys: List<String>
        get() = read("whys")
        set(v) = write("whys", v)

    var quotes: List<String>
        get() = read("quotes")
        set(v) = write("quotes", v)

    private fun read(key: String): List<String> {
        val array = JSONArray(sp.getString(key, "[]"))
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun write(key: String, list: List<String>) = sp.edit().putString(key, JSONArray(list).toString()).apply()

    /** Your intention for one day, written the night before. */
    fun setIntention(text: String, forDate: LocalDate) =
        sp.edit().putString("intention", text).putString("intention_date", forDate.toString()).apply()

    /** What you planned for today (the points still to do, from My day), or null if nothing. */
    fun todaysIntention(): String? {
        val plan = PlanStore(appContext).items(LocalDate.now()) // also brings in a line written the old way
        if (plan.isEmpty()) return null
        val left = plan.filter { !it.done }
        if (left.isEmpty()) return "all ${plan.size} done ✓"
        return left.joinToString(", ") { (it.timeText()?.let { t -> "$t " } ?: "") + it.text }
    }

    fun intentionFor(date: LocalDate): String =
        if (sp.getString("intention_date", "") == date.toString()) sp.getString("intention", "")!! else ""
}
