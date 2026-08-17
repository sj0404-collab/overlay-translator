package com.overlay.translator

import android.content.Context

/**
 * Translates EN→RU through online engines only.
 * Local dictionary, Local NMT and phrase-bank routes have been removed
 * so the APK stays small (no bundled TSV data / ML Kit model).
 */
class Translator(private val ctx: Context) {

    fun translate(text: String, engine: String): String {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return ""
        if (ScriptDetect.isMostlyCyrillic(cleaned)) return cleaned
        return when (engine) {
            "google" -> MangaTranslatorService.translate(cleaned)
            "googleai" -> LlmClient.translateGemini(ctx, cleaned)
                ?: MangaTranslatorService.translate(cleaned)
            "zen" -> LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx))
                ?: MangaTranslatorService.translate(cleaned)
            "openrouter" -> LlmClient.translateOpenRouter(
                cleaned,
                EnginePrefs.openrouterKey(ctx),
                EnginePrefs.orModel(ctx),
            )
                ?: LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx))
                ?: MangaTranslatorService.translate(cleaned)
            "mymemory" -> mymemory(cleaned)
                ?: MangaTranslatorService.translate(cleaned)
            else -> { // "auto" — try Zen → Google Translate Free → MyMemory
                LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx))
                    ?: MangaTranslatorService.translate(cleaned)
                    ?: mymemory(cleaned)
            }
        }.let { RuText.clean(it) }
    }

    @Suppress("SameParameterValue")
    private fun mymemory(text: String): String? = runCatching {
        val q = java.net.URLEncoder.encode(text.take(450), "UTF-8")
        val c = java.net.URL("https://api.mymemory.translated.net/get?q=$q&langpair=en|ru")
            .openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        val body = c.inputStream.bufferedReader().readText()
        c.disconnect()
        val tr = org.json.JSONObject(body).getJSONObject("responseData").optString("translatedText")
        if (tr.isNullOrBlank() || tr.contains("MYMEMORY", true)) null else tr
    }.getOrNull()
}