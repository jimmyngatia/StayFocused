package com.personal.statusguard

import android.content.Context
import org.json.JSONArray
import java.time.LocalDate

/** Your own whys and quotes, shown on the lockout, pause, sleep and alarm screens. */
class Words(context: Context) {
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

    /** Today's intention, or null if you didn't write one. */
    fun todaysIntention(): String? =
        if (sp.getString("intention_date", "") == LocalDate.now().toString()) sp.getString("intention", null)?.ifBlank { null }
        else null

    fun intentionFor(date: LocalDate): String =
        if (sp.getString("intention_date", "") == date.toString()) sp.getString("intention", "")!! else ""
}
