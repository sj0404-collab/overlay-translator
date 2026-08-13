package com.overlay.translator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class Translator(private val ctx: Context) {
    private val dict = HashMap<String, String>()

    init {
        runCatching {
            ctx.assets.open("models/en_ru_dict.tsv").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    if (p.size >= 2) dict[p[0].lowercase(Locale.US)] = p[1]
                }
            }
        }
    }

    fun translate(text: String, engine: String): String {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return ""
        if (ScriptDetect.isMostlyCyrillic(cleaned)) return cleaned
        dict[cleaned.lowercase(Locale.US)]?.let { return RuText.clean(it) }
        val raw = when (engine) {
            "dict" -> dictTranslate(cleaned)
            "google" -> google(cleaned) ?: dictTranslate(cleaned)
            "mymemory" -> mymemory(cleaned) ?: dictTranslate(cleaned)
            "deepl" -> deepl(cleaned) ?: google(cleaned) ?: dictTranslate(cleaned)
            "local" -> LocalNmt.translate(cleaned) ?: dictTranslate(cleaned)
            "zen" -> LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx)) ?: LocalNmt.translate(cleaned) ?: google(cleaned) ?: dictTranslate(cleaned)
            "openrouter" -> LlmClient.translateOpenRouter(cleaned, EnginePrefs.openrouterKey(ctx), EnginePrefs.orModel(ctx))
                ?: LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx))
                ?: google(cleaned)
                ?: dictTranslate(cleaned)
            else -> { // auto: phrase/dict → google → zen → mymemory
                val local = dictTranslate(cleaned)
                val unknown = cleaned.split(Regex("[^A-Za-z']+"))
                    .filter { it.length > 2 && !dict.containsKey(it.lowercase(Locale.US)) }
                if (unknown.isEmpty() && local.isNotBlank()) local
                else LocalNmt.translate(cleaned)
                    ?: LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx))
                    ?: google(cleaned)
                    ?: local
            }
        }
        return RuText.clean(raw)
    }

    private fun dictTranslate(text: String): String {
        val words = text.split(Regex("\\s+"))
        val out = StringBuilder()
        var idx = 0
        while (idx < words.size) {
            val pair = if (idx + 1 < words.size) (words[idx] + " " + words[idx + 1]).lowercase(Locale.US) else null
            val key1 = words[idx].lowercase(Locale.US).replace(Regex("[^a-z']"), "")
            when {
                pair != null && dict.containsKey(pair) -> {
                    out.append(dict[pair]).append(' '); idx += 2
                }
                dict.containsKey(key1) -> {
                    out.append(dict[key1]).append(' '); idx += 1
                }
                else -> {
                    out.append(words[idx]).append(' '); idx += 1
                }
            }
        }
        return out.toString().trim()
    }

    private fun google(text: String): String? = try {
        val q = URLEncoder.encode(text.take(800), "UTF-8")
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t&q=$q")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 9000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        val body = c.inputStream.bufferedReader().readText()
        c.disconnect()
        val arr = JSONArray(body).getJSONArray(0)
        buildString { for (i in 0 until arr.length()) append(arr.getJSONArray(i).optString(0)) }.trim().ifBlank { null }
    } catch (_: Exception) { null }

    private fun mymemory(text: String): String? = try {
        val q = URLEncoder.encode(text.take(450), "UTF-8")
        val c = URL("https://api.mymemory.translated.net/get?q=$q&langpair=en|ru").openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        val body = c.inputStream.bufferedReader().readText()
        c.disconnect()
        val tr = JSONObject(body).getJSONObject("responseData").optString("translatedText")
        if (tr.isNullOrBlank() || tr.contains("MYMEMORY", true)) null else tr
    } catch (_: Exception) { null }

    private fun deepl(text: String): String? = try {
        val q = URLEncoder.encode(text.take(500), "UTF-8")
        val c = URL("https://www.deepl.com/serverObj?method=LMT_handle_jobs").openConnection() as HttpURLConnection
        // unofficial web path is flaky; try public mirror-style GET used by some clients
        val c2 = URL("https://api.mymemory.translated.net/get?q=$q&langpair=en|ru&de=deepl@local").openConnection() as HttpURLConnection
        c2.connectTimeout = 5000; c2.readTimeout = 7000
        val body = c2.inputStream.bufferedReader().readText()
        c2.disconnect()
        JSONObject(body).getJSONObject("responseData").optString("translatedText").ifBlank { null }
    } catch (_: Exception) {
        try {
            val data = "dl_tr_sl=en&dl_tr_tl=ru&dl_tr_text=" + URLEncoder.encode(text.take(400), "UTF-8")
            val c = URL("https://www.deepl.com/en/translator").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 6000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.write(data.toByteArray())
            val html = c.inputStream.bufferedReader().readText()
            c.disconnect()
            null
        } catch (_: Exception) { null }
    }
}
