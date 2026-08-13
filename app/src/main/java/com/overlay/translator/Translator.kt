package com.overlay.translator

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class Translator(ctx: Context) {
    enum class Mode { LOCAL_THEN_ONLINE, ONLINE, DICT }

    private val dict = HashMap<String, String>()

    init {
        ctx.assets.open("models/en_ru_dict.tsv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val p = line.split('\t')
                if (p.size >= 2) dict[p[0].lowercase(Locale.US)] = p[1]
            }
        }
    }

    fun translate(text: String, mode: Mode): String {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return ""
        val local = dictTranslate(cleaned)
        return when (mode) {
            Mode.DICT -> local
            Mode.ONLINE -> online(cleaned) ?: local
            Mode.LOCAL_THEN_ONLINE -> {
                val unknown = cleaned.split(Regex("[^A-Za-z']+")).filter { it.length > 2 && !dict.containsKey(it.lowercase(Locale.US)) }
                if (unknown.size <= cleaned.split(" ").size / 3 && local.isNotBlank()) local
                else online(cleaned) ?: local
            }
        }
    }

    private fun dictTranslate(text: String): String {
        // longest-phrase first
        val lower = text.lowercase(Locale.US)
        val keys = dict.keys.sortedByDescending { it.length }
        var rest = lower
        val out = StringBuilder()
        var i = 0
        val words = text.split(Regex("\\s+"))
        val used = BooleanArray(words.size)
        // word-by-word with bigrams
        var idx = 0
        while (idx < words.size) {
            val w = words[idx]
            val pair = if (idx + 1 < words.size) (w + " " + words[idx + 1]).lowercase(Locale.US) else null
            val key1 = w.lowercase(Locale.US).replace(Regex("[^a-z']"), "")
            when {
                pair != null && dict.containsKey(pair) -> {
                    out.append(dict[pair]).append(' ')
                    idx += 2
                }
                dict.containsKey(key1) -> {
                    out.append(dict[key1]).append(' ')
                    idx += 1
                }
                else -> {
                    out.append(w).append(' ')
                    idx += 1
                }
            }
        }
        return out.toString().trim()
    }

    private fun online(text: String): String? {
        return try {
            val q = URLEncoder.encode(text.take(450), "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=$q&langpair=en|ru")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.requestMethod = "GET"
            val body = c.inputStream.bufferedReader().readText()
            c.disconnect()
            val tr = JSONObject(body).getJSONObject("responseData").optString("translatedText")
            if (tr.isNullOrBlank() || tr.contains("MYMEMORY WARNING", true)) null else tr
        } catch (_: Exception) {
            null
        }
    }
}
