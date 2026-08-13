package com.overlay.translator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class Translator(ctx: Context) {
    enum class Mode { AUTO, LOCAL_THEN_ONLINE, ONLINE, DICT }

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
        if (ScriptDetect.isMostlyCyrillic(cleaned)) return cleaned
        val local = dictTranslate(cleaned)
        val fullHit = dict[cleaned.lowercase(Locale.US)]
        if (fullHit != null) return fullHit
        return when (mode) {
            Mode.DICT -> local
            Mode.ONLINE -> google(cleaned) ?: mymemory(cleaned) ?: local
            Mode.LOCAL_THEN_ONLINE, Mode.AUTO -> {
                val unknown = cleaned.split(Regex("[^A-Za-z']+"))
                    .filter { it.length > 2 && !dict.containsKey(it.lowercase(Locale.US)) }
                if (unknown.isEmpty() && local.isNotBlank()) local
                else google(cleaned) ?: mymemory(cleaned) ?: local
            }
        }
    }

    private fun dictTranslate(text: String): String {
        val words = text.split(Regex("\\s+"))
        val out = StringBuilder()
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

    /** Same idea as reference app: Google first, then fallbacks. No stolen API keys. */
    private fun google(text: String): String? {
        return try {
            val q = URLEncoder.encode(text.take(800), "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t&q=$q")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 7000
            c.readTimeout = 9000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val body = c.inputStream.bufferedReader().readText()
            c.disconnect()
            val arr = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                sb.append(arr.getJSONArray(i).optString(0))
            }
            sb.toString().trim().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun mymemory(text: String): String? {
        return try {
            val q = URLEncoder.encode(text.take(450), "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=$q&langpair=en|ru")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 8000
            val body = c.inputStream.bufferedReader().readText()
            c.disconnect()
            val tr = JSONObject(body).getJSONObject("responseData").optString("translatedText")
            if (tr.isNullOrBlank() || tr.contains("MYMEMORY WARNING", true)) null else tr
        } catch (_: Exception) {
            null
        }
    }
}
