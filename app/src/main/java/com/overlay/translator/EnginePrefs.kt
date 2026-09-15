package com.overlay.translator

import android.content.Context

/**
 * User-editable engine and mode preferences. Every value persists in
 * SharedPreferences; the settings screen and the runtime routers both read
 * from here. No API keys are shipped; keys are entered by the user.
 */
object EnginePrefs {
    private const val P = "ot_engines"
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(P, 0)

    // Connectivity modes for the OCR/translate route.
    const val MODE_OFFLINE = "offline"
    const val MODE_MIXED = "mixed"
    const val MODE_COMBO = "combo"
    const val MODE_ONLINE = "online"

    // OCR engine ids.
    const val OCR_LOCAL = "local_cyrillic"
    const val OCR_GLENS = "glens"
    const val OCR_ZEN = "zen"
    const val OCR_GOOGLE_AI = "googleai"
    const val OCR_OPENROUTER = "openrouter"

    // Translate engine ids.
    const val TR_DICT = "dict"
    const val TR_GOOGLE = "google"
    const val TR_GOOGLE_AI = "googleai"
    const val TR_ZEN = "zen"
    const val TR_OPENROUTER = "openrouter"
    const val TR_MYMEMORY = "mymemory"
    const val TR_AUTO = "auto"

    // TTS source ids.
    const val TTS_AUTO = "auto"
    const val TTS_SYSTEM = "system"
    const val TTS_EDGE = "edge"

    fun ocr(ctx: Context) = sp(ctx).getString("ocr", OCR_LOCAL) ?: OCR_LOCAL
    fun setOcr(ctx: Context, v: String) = sp(ctx).edit().putString("ocr", v).apply()

    fun tr(ctx: Context) = sp(ctx).getString("tr", TR_DICT) ?: TR_DICT
    fun setTr(ctx: Context, v: String) = sp(ctx).edit().putString("tr", v).apply()
    fun trTargetLang(ctx: Context) = "ru"
    fun setTrTargetLang(ctx: Context, v: String) = Unit

    fun netMode(ctx: Context) = sp(ctx).getString("net_mode", MODE_MIXED) ?: MODE_MIXED
    fun setNetMode(ctx: Context, v: String) = sp(ctx).edit().putString("net_mode", v).apply()

    fun ttsSource(ctx: Context) = sp(ctx).getString("tts_source", TTS_AUTO) ?: TTS_AUTO
    fun setTtsSource(ctx: Context, v: String) = sp(ctx).edit().putString("tts_source", v).apply()

    // Scan behavior.
    fun live(ctx: Context) = sp(ctx).getBoolean("live", false)
    fun setLive(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("live", v).apply()
    fun autoTranslate(ctx: Context) = sp(ctx).getBoolean("auto_translate", false)
    fun setAutoTranslate(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_translate", v).apply()
    fun speak(ctx: Context) = sp(ctx).getBoolean("speak", true)
    fun setSpeak(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("speak", v).apply()
    fun voiceName(ctx: Context) = sp(ctx).getString("voice_name", "") ?: ""
    fun setVoiceName(ctx: Context, v: String) = sp(ctx).edit().putString("voice_name", v).apply()

    // Scan source language: "AUTO", "RU", "EN".
    fun scanLang(ctx: Context) = sp(ctx).getString("scan_lang", "AUTO") ?: "AUTO"
    fun setScanLang(ctx: Context, v: String) = sp(ctx).edit().putString("scan_lang", v).apply()

    // Frame behavior: "auto" (fit content area, insets removed), "rect" (manual).
    fun regionMode(ctx: Context) = sp(ctx).getString("region_mode", "auto") ?: "auto"
    fun setRegionMode(ctx: Context, v: String) = sp(ctx).edit().putString("region_mode", v).apply()

    // Online engine credentials. Never shipped; user-configured.
    fun googleApiKey(ctx: Context) = sp(ctx).getString("google_api_key", "") ?: ""
    fun setGoogleApiKey(ctx: Context, v: String) = sp(ctx).edit().putString("google_api_key", v.trim()).apply()
    fun rotateGoogleKey(ctx: Context) = false
    fun googleModel(ctx: Context) = sp(ctx).getString("google_model", "") ?: ""
    fun setGoogleModel(ctx: Context, v: String) = sp(ctx).edit().putString("google_model", v.trim()).apply()
    fun openrouterKey(ctx: Context) = sp(ctx).getString("or_key", "") ?: ""
    fun setOpenrouterKey(ctx: Context, v: String) = sp(ctx).edit().putString("or_key", v.trim()).apply()
    fun orModel(ctx: Context) = sp(ctx).getString("or_model", "") ?: ""
    fun setOrModel(ctx: Context, v: String) = sp(ctx).edit().putString("or_model", v.trim()).apply()
    fun zenModel(ctx: Context) = sp(ctx).getString("zen_model", "") ?: ""
    fun setZenModel(ctx: Context, v: String) = sp(ctx).edit().putString("zen_model", v.trim()).apply()

    // Token accounting for online engines.
    fun tokenUsageCount(ctx: Context) = sp(ctx).getLong("token_usage", 0L)
    fun setTokenUsageCount(ctx: Context, v: Long) = sp(ctx).edit().putLong("token_usage", v).apply()
    fun incrementTokens(ctx: Context, plus: Long) {
        if (plus <= 0) return
        setTokenUsageCount(ctx, tokenUsageCount(ctx) + plus)
    }
}