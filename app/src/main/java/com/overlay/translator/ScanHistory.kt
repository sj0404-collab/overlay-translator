package com.overlay.translator

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last [MAX] scan results (ocr + translation + timestamp).
 * Persisted in SharedPreferences so it survives app restarts.
 */
object ScanHistory {
    private const val PREFS = "ot_history"
    private const val KEY = "scans"
    private const val MAX = 50

    private data class Entry(val time: Long, val ocr: String, val tr: String, val engine: String)

    fun add(ctx: Context, ocr: String, tr: String, engine: String) {
        val sp = ctx.getSharedPreferences(PREFS, 0)
        val arr = load(sp)
        arr.put(JSONObject().apply {
            put("t", System.currentTimeMillis())
            put("o", ocr)
            put("tr", tr)
            put("e", engine)
        })
        // Keep only last MAX
        while (arr.length() > MAX) arr.remove(0)
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun recent(ctx: Context, count: Int = 10): List<Triple<String, String, String>> {
        val arr = load(ctx.getSharedPreferences(PREFS, 0))
        val out = mutableListOf<Triple<String, String, String>>()
        for (i in (arr.length() - 1).coerceAtLeast(0) downTo (arr.length() - count).coerceAtLeast(0)) {
            val obj = arr.optJSONObject(i) ?: continue
            val ts = obj.optLong("t", 0)
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val time = if (ts > 0) fmt.format(Date(ts)) else "?"
            out.add(Triple(time, obj.optString("o", ""), obj.optString("tr", "")))
        }
        return out
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, 0).edit().remove(KEY).apply()
    }

    private fun load(sp: SharedPreferences): JSONArray {
        return try { JSONArray(sp.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
    }
}
