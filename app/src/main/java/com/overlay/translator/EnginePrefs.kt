package com.overlay.translator

import android.content.Context

object EnginePrefs {
    private const val P = "ot_engines"

    fun ocr(ctx: Context) = ctx.getSharedPreferences(P, 0).getString("ocr", "manga") ?: "manga"
    fun setOcr(ctx: Context, v: String) = ctx.getSharedPreferences(P, 0).edit().putString("ocr", v).apply()

    fun tr(ctx: Context) = ctx.getSharedPreferences(P, 0).getString("tr", "auto") ?: "auto"
    fun setTr(ctx: Context, v: String) = ctx.getSharedPreferences(P, 0).edit().putString("tr", v).apply()

    fun zenModel(ctx: Context) = ctx.getSharedPreferences(P, 0).getString("zen", "big-pickle") ?: "big-pickle"
    fun setZenModel(ctx: Context, v: String) = ctx.getSharedPreferences(P, 0).edit().putString("zen", v).apply()

    fun openrouterKey(ctx: Context) = ctx.getSharedPreferences(P, 0).getString("or_key", "") ?: ""
    fun setOpenrouterKey(ctx: Context, v: String) = ctx.getSharedPreferences(P, 0).edit().putString("or_key", v).apply()

    fun live(ctx: Context) = ctx.getSharedPreferences(P, 0).getBoolean("live", false)
    fun setLive(ctx: Context, v: Boolean) = ctx.getSharedPreferences(P, 0).edit().putBoolean("live", v).apply()

    fun speak(ctx: Context) = ctx.getSharedPreferences(P, 0).getBoolean("speak", true)
    fun setSpeak(ctx: Context, v: Boolean) = ctx.getSharedPreferences(P, 0).edit().putBoolean("speak", v).apply()

    fun scanLang(ctx: Context) = ctx.getSharedPreferences(P, 0).getString("slang", "AUTO") ?: "AUTO"
    fun setScanLang(ctx: Context, v: String) = ctx.getSharedPreferences(P, 0).edit().putString("slang", v).apply()
}
