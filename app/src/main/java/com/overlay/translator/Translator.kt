package com.overlay.translator

import android.content.Context

/**
 * Translates EN→RU. In offline mode only the local dictionary is used;
 * otherwise the selected engine runs, falling back to free online routes.
 */
class Translator(private val ctx: Context) {
    private val dictionary by lazy { LocalDictionary(ctx) }

    fun translate(text: String, engine: String, mode: String = EnginePrefs.MODE_MIXED): String {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return ""
        if (ScriptDetect.isMostlyCyrillic(cleaned)) return cleaned
        val eff = if (mode == EnginePrefs.MODE_OFFLINE) EnginePrefs.TR_DICT else engine
        return when (eff) {
            EnginePrefs.TR_DICT -> dictionary.translateFragment(cleaned)
            EnginePrefs.TR_GOOGLE -> MangaTranslatorService.translate(cleaned)
            EnginePrefs.TR_GOOGLE_AI -> onlineTokens(cleaned,
                LlmClient.translateGemini(ctx, cleaned)
                    ?: MangaTranslatorService.translate(cleaned))
            EnginePrefs.TR_ZEN -> onlineTokens(cleaned,
                LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx))
                    ?: MangaTranslatorService.translate(cleaned))
            EnginePrefs.TR_OPENROUTER -> onlineTokens(cleaned,
                LlmClient.translateOpenRouter(cleaned, EnginePrefs.openrouterKey(ctx), EnginePrefs.orModel(ctx))
                    ?: LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx))
                    ?: MangaTranslatorService.translate(cleaned))
            EnginePrefs.TR_MYMEMORY -> mymemory(cleaned)
                ?: MangaTranslatorService.translate(cleaned)
            else -> { // "auto" — Zen → Google Translate Free → MyMemory
                onlineTokens(cleaned,
                    LlmClient.translateZen(cleaned, EnginePrefs.zenModel(ctx))
                        ?: MangaTranslatorService.translate(cleaned)
                        ?: mymemory(cleaned))
            }
        }.let { RuText.clean(it ?: "") }
    }

    /** Rough token accounting (≈4 chars per token) for online LLM routes. */
    private fun onlineTokens(text: String, result: String?): String? {
        EnginePrefs.incrementTokens(ctx, ((text.length / 4) + (result?.length?.div(8) ?: 0)).toLong())
        return result
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