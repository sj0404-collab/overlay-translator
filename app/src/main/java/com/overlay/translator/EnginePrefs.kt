package com.overlay.translator

import android.content.Context

/** Preferences for the local-only overlay build. No API keys are shipped. */
object EnginePrefs {
    private const val P = "ot_engines"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(P, 0)

    fun ocr(ctx: Context) = sp(ctx).getString("ocr", "local_cyrillic") ?: "local_cyrillic"
    fun setOcr(ctx: Context, v: String) = sp(ctx).edit().putString("ocr", v).apply()

    // Compatibility accessors for optional legacy source files. They never
    // supply a bundled key and are not used by the local overlay route.
    fun googleApiKey(ctx: Context) = sp(ctx).getString("google_api_key", "") ?: ""
    fun setGoogleApiKey(ctx: Context, v: String) = sp(ctx).edit().putString("google_api_key", v).apply()
    fun rotateGoogleKey(ctx: Context) = false
    fun googleModel(ctx: Context) = sp(ctx).getString("google_model", "") ?: ""
    fun setGoogleModel(ctx: Context, v: String) = sp(ctx).edit().putString("google_model", v).apply()
    fun openrouterKey(ctx: Context) = sp(ctx).getString("or_key", "") ?: ""
    fun setOpenrouterKey(ctx: Context, v: String) = sp(ctx).edit().putString("or_key", v).apply()
    fun orModel(ctx: Context) = sp(ctx).getString("or_model", "") ?: ""
    fun setOrModel(ctx: Context, v: String) = sp(ctx).edit().putString("or_model", v).apply()
    fun zenModel(ctx: Context) = sp(ctx).getString("zen_model", "") ?: ""
    fun setZenModel(ctx: Context, v: String) = sp(ctx).edit().putString("zen_model", v).apply()

    fun tr(ctx: Context) = "off"
    fun setTr(ctx: Context, v: String) = Unit
    fun trTargetLang(ctx: Context) = "ru"
    fun setTrTargetLang(ctx: Context, v: String) = Unit

    fun live(ctx: Context) = sp(ctx).getBoolean("live", false)
    fun setLive(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("live", v).apply()
    fun autoTranslate(ctx: Context) = false
    fun setAutoTranslate(ctx: Context, v: Boolean) = Unit
    fun speak(ctx: Context) = sp(ctx).getBoolean("speak", true)
    fun setSpeak(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("speak", v).apply()
    fun voiceName(ctx: Context) = sp(ctx).getString("voice_name", "") ?: ""
    fun setVoiceName(ctx: Context, v: String) = sp(ctx).edit().putString("voice_name", v).apply()

    fun scanLang(ctx: Context) = "RU"
    fun setScanLang(ctx: Context, v: String) = Unit
    fun scanMode(ctx: Context) = "rect"
    fun setScanMode(ctx: Context, v: String) = Unit
    fun regionMode(ctx: Context) = "rect"
    fun setRegionMode(ctx: Context, v: String) = Unit

    fun tokenUsageCount(ctx: Context) = 0L
    fun setTokenUsageCount(ctx: Context, v: Long) = Unit
    fun incrementTokens(ctx: Context, plus: Long) = Unit
}
