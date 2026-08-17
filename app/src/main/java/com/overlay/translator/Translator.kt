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
            ctx.assets.open(AssetCopy.labelsAsset("en_ru_dict.tsv")).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    if (p.size >= 2) dict[p[0].lowercase(Locale.US)] = p[1]
                }
            }
        }
    }

    fun translate(text: String, engine: String, targetLang: String = EnginePrefs.trTargetLang(ctx)): String {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return ""
        if (ScriptDetect.isMostlyCyrillic(cleaned)) return cleaned
        dict[cleaned.lowercase(Locale.US)]?.let { return RuText.clean(it) }
        val raw = when (engine) {
            "dict" -> dictTranslate(cleaned)
            "google" -> MangaTranslatorService.translate(cleaned, targetLang)
                ?: dictTranslate(cleaned)
            "mymemory" -> mymemory(cleaned) ?: MangaTranslatorService.translate(cleaned, targetLang)
                ?: dictTranslate(cleaned)
            "deepl" -> MangaTranslatorService.translate(cleaned, targetLang)
                ?: mymemory(cleaned) ?: dictTranslate(cleaned)
            "local" -> LocalNmt.translate(cleaned) ?: dictTranslate(cleaned)
            "googleai" -> LlmClient.translateGemini(ctx, cleaned)
                ?: MangaTranslatorService.translate(cleaned, targetLang)
                ?: dictTranslate(cleaned)
            "zen" -> LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx))
                ?: LocalNmt.translate(cleaned)
                ?: MangaTranslatorService.translate(cleaned, targetLang)
                ?: dictTranslate(cleaned)
            "openrouter" -> LlmClient.translateOpenRouter(cleaned, EnginePrefs.openrouterKey(ctx), EnginePrefs.orModel(ctx))
                ?: LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx))
                ?: MangaTranslatorService.translate(cleaned, targetLang)
                ?: dictTranslate(cleaned)
            else -> { // auto: phrase/dict → ML Kit NMT → Zen → MyMemory → GoogleFree
                val local = dictTranslate(cleaned)
                val unknown = cleaned.split(Regex("[^A-Za-z']+"))
                    .filter { it.length > 2 && !dict.containsKey(it.lowercase(Locale.US)) }
                if (unknown.isEmpty() && local.isNotBlank()) local
                else LocalNmt.translate(cleaned)
                    ?: LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx))
                    ?: MangaTranslatorService.translate(cleaned, targetLang)
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

    private fun mymemory(text: String): String? = try {
        val q = URLEncoder.encode(text.take(450), "UTF-8")
        val c = URL("https://api.mymemory.translated.net/get?q=$q&langpair=en|ru").openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        val body = c.inputStream.bufferedReader().readText()
        c.disconnect()
        val tr = JSONObject(body).getJSONObject("responseData").optString("translatedText")
        if (tr.isNullOrBlank() || tr.contains("MYMEMORY", true)) null else tr
    } catch (_: Exception) { null }
}
