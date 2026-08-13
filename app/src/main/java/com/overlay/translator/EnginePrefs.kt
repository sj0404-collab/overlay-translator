package com.overlay.translator

import android.content.Context

object EnginePrefs {
    private const val P = "ot_engines"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(P, 0)

    fun ocr(ctx: Context) = sp(ctx).getString("ocr", "zen") ?: "zen"
    fun setOcr(ctx: Context, v: String) = sp(ctx).edit().putString("ocr", v).apply()

    fun tr(ctx: Context) = sp(ctx).getString("tr", "zen") ?: "zen"
    fun setTr(ctx: Context, v: String) = sp(ctx).edit().putString("tr", v).apply()

    fun zenModel(ctx: Context) = sp(ctx).getString("zen", "mimo-v2.5-free") ?: "mimo-v2.5-free"
    fun setZenModel(ctx: Context, v: String) = sp(ctx).edit().putString("zen", v).apply()

    fun orModel(ctx: Context) = sp(ctx).getString("or_model", LlmClient.OR_FREE.first()) ?: LlmClient.OR_FREE.first()
    fun setOrModel(ctx: Context, v: String) = sp(ctx).edit().putString("or_model", v).apply()

    fun openrouterKey(ctx: Context) = sp(ctx).getString("or_key", "") ?: ""
    fun setOpenrouterKey(ctx: Context, v: String) = sp(ctx).edit().putString("or_key", v).apply()

    fun live(ctx: Context) = sp(ctx).getBoolean("live", false)
    fun setLive(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("live", v).apply()

    fun speak(ctx: Context) = sp(ctx).getBoolean("speak", true)
    fun setSpeak(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("speak", v).apply()

    fun scanLang(ctx: Context) = sp(ctx).getString("slang", "RU") ?: "RU"
    fun setScanLang(ctx: Context, v: String) = sp(ctx).edit().putString("slang", v).apply()

    fun scanMode(ctx: Context) = sp(ctx).getString("smode", "rect") ?: "rect"
    fun setScanMode(ctx: Context, v: String) = sp(ctx).edit().putString("smode", v).apply()

    fun regionMode(ctx: Context) = sp(ctx).getString("rmode", "rect") ?: "rect"
    fun setRegionMode(ctx: Context, v: String) = sp(ctx).edit().putString("rmode", v).apply()
}
