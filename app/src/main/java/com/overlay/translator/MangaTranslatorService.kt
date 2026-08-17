package com.overlay.translator

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free, no-key Google Translate gateway lifted straight from Yomihon's
 * reader build ([MangaTranslatorService]). Used by the overlay as the
 * cheapest possible translation route for short snippets.
 */
object MangaTranslatorService {

    fun translate(
        text: String,
        targetLang: String = "ru",
        sourceLang: String = "auto",
    ): String {
        if (text.isBlank()) return ""
        return runCatching {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
                "&sl=$sourceLang&tl=$targetLang&dt=t&q=$encoded"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            connection.disconnect()
            val arr = JSONArray(body)
            val sentences = arr.optJSONArray(0) ?: return@runCatching text
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.optJSONArray(i) ?: continue
                sb.append(sentence.optString(0, ""))
            }
            sb.toString().trim().ifBlank { text }
        }.getOrElse { text }
    }
}
