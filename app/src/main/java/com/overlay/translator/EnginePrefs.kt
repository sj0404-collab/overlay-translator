package com.overlay.translator

import android.content.Context

/**
 * Local pref store for the overlay translator. Backed by SharedPreferences
 * so the service can read settings before the rest of the app DI graph
 * exists. Adds keys for the engines cloned from Yomihon's reader build.
 */
object EnginePrefs {
    private const val P = "ot_engines"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(P, 0)

    /* ────────────── OCR engines ────────────── */
    fun ocr(ctx: Context) = sp(ctx).getString("ocr", "zen") ?: "zen"
    fun setOcr(ctx: Context, v: String) = sp(ctx).edit().putString("ocr", v).apply()

    /* ────────────── Cloud OCR keys ──────────── */
    fun googleApiKey(ctx: Context) = sp(ctx).getString("google_api_key", "") ?: ""
    fun setGoogleApiKey(ctx: Context, v: String) = sp(ctx).edit().putString("google_api_key", v).apply()

    fun googleModel(ctx: Context) = sp(ctx).getString("google_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
    fun setGoogleModel(ctx: Context, v: String) = sp(ctx).edit().putString("google_model", v).apply()

    fun orModel(ctx: Context) = sp(ctx).getString("or_model", "google/gemini-2.5-flash") ?: "google/gemini-2.5-flash"
    fun setOrModel(ctx: Context, v: String) = sp(ctx).edit().putString("or_model", v).apply()

    fun openrouterKey(ctx: Context) = sp(ctx).getString("or_key", "") ?: ""
    fun setOpenrouterKey(ctx: Context, v: String) = sp(ctx).edit().putString("or_key", v).apply()

    /* ────────────── Zen (free cloud LLM) ────── */
    fun zenModel(ctx: Context) = sp(ctx).getString("zen_model", LlmClient.ZEN_FREE.first()) ?: LlmClient.ZEN_FREE.first()
    fun setZenModel(ctx: Context, v: String) = sp(ctx).edit().putString("zen_model", v).apply()

    /* ────────────── Translate route ─────────── */
    fun tr(ctx: Context) = sp(ctx).getString("tr", "zen") ?: "zen"
    fun setTr(ctx: Context, v: String) = sp(ctx).edit().putString("tr", v).apply()

    fun trTargetLang(ctx: Context) = sp(ctx).getString("tr_tl", "ru") ?: "ru"
    fun setTrTargetLang(ctx: Context, v: String) = sp(ctx).edit().putString("tr_tl", v).apply()

    /* ────────────── Realtime flags ──────────── */
    fun live(ctx: Context) = sp(ctx).getBoolean("live", false)
    fun setLive(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("live", v).apply()

    fun autoTranslate(ctx: Context) = sp(ctx).getBoolean("auto_translate", false)
    fun setAutoTranslate(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_translate", v).apply()

    fun speak(ctx: Context) = sp(ctx).getBoolean("speak", true)
    fun setSpeak(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("speak", v).apply()

    /* ────────────── Scan options ────────────── */
    fun scanLang(ctx: Context) = sp(ctx).getString("slang", "AUTO") ?: "AUTO"
    fun setScanLang(ctx: Context, v: String) = sp(ctx).edit().putString("slang", v).apply()

    fun scanMode(ctx: Context) = sp(ctx).getString("smode", "rect") ?: "rect"
    fun setScanMode(ctx: Context, v: String) = sp(ctx).edit().putString("smode", v).apply()

    fun regionMode(ctx: Context) = sp(ctx).getString("rmode", "rect") ?: "rect"
    fun setRegionMode(ctx: Context, v: String) = sp(ctx).edit().putString("rmode", v).apply()

    /* ────────────── Token usage counter ─────── */
    fun tokenUsageCount(ctx: Context) = sp(ctx).getLong("token_usage", 0L)
    fun setTokenUsageCount(ctx: Context, v: Long) = sp(ctx).edit().putLong("token_usage", v).apply()

    fun incrementTokens(ctx: Context, plus: Long) {
        setTokenUsageCount(ctx, tokenUsageCount(ctx) + plus)
    }
}
