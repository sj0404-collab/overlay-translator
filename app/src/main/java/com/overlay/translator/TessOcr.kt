package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI

enum class ScanLang { EN, RU, BOTH }

class TessOcr(ctx: Context) {
    private val dataPath = AssetCopy.ensureTess(ctx).absolutePath
    private val api = TessBaseAPI()
    private val enWl = loadDict(ctx, "labels/en_dict.txt")
    private val laWl = loadDict(ctx, "labels/latin_dict.txt")
    private val ruWl = loadDict(ctx, "labels/cyrillic_dict.txt")
    var lang: ScanLang = ScanLang.EN
        private set

    init {
        reopen(ScanLang.EN)
    }

    fun reopen(l: ScanLang) {
        lang = l
        try { api.recycle() } catch (_: Exception) {}
        val code = when (l) {
            ScanLang.EN -> "eng"
            ScanLang.RU -> "rus"
            ScanLang.BOTH -> "eng+rus"
        }
        api.init(dataPath, code)
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        api.setVariable("tessedit_char_blacklist", "|[]{}<>`")
        val wl = when (l) {
            ScanLang.EN -> if (enWl.isNotEmpty()) enWl else laWl
            ScanLang.RU -> ruWl
            ScanLang.BOTH -> laWl + ruWl
        }
        if (wl.isNotEmpty()) api.setVariable("tessedit_char_whitelist", wl)
    }

    fun read(bmp: Bitmap): String {
        return try {
            api.setImage(bmp)
            (api.getUTF8Text() ?: "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    fun close() {
        try { api.recycle() } catch (_: Exception) {}
    }

    private fun loadDict(ctx: Context, path: String): String {
        return runCatching {
            ctx.assets.open(path).bufferedReader().readLines()
                .map { it.trim('\r', '\n') }
                .filter { it.isNotEmpty() }
                .joinToString("")
        }.getOrDefault("")
    }
}
