package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI

enum class ScanLang { EN, RU, BOTH }

class TessOcr(private val ctx: Context) {
    private val dataPath = AssetCopy.ensureTess(ctx).absolutePath
    private var api: TessBaseAPI? = null
    private val enWl = loadDict("labels/en_dict.txt")
    private val laWl = loadDict("labels/latin_dict.txt")
    private val ruWl = loadDict("labels/cyrillic_dict.txt")

    init { reopen(ScanLang.EN) }

    fun reopen(l: ScanLang) {
        try { api?.recycle() } catch (_: Exception) {}
        val next = TessBaseAPI()
        val code = when (l) {
            ScanLang.EN -> "eng"
            ScanLang.RU -> "rus"
            ScanLang.BOTH -> "eng+rus"
        }
        next.init(dataPath, code)
        next.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        next.setVariable("tessedit_pageseg_mode", "3")
        val wl = when (l) {
            ScanLang.EN -> enWl.ifEmpty { laWl }
            ScanLang.RU -> ruWl
            ScanLang.BOTH -> laWl + ruWl
        }
        if (wl.isNotEmpty()) next.setVariable("tessedit_char_whitelist", wl)
        api = next
    }

    fun read(bmp: Bitmap): String = try {
        api?.setImage(bmp)
        (api?.getUTF8Text() ?: "").trim()
    } catch (_: Exception) { "" }

    fun close() { try { api?.recycle() } catch (_: Exception) {} }

    private fun loadDict(path: String): String = runCatching {
        ctx.assets.open(path).bufferedReader().readLines()
            .map { it.trim('\r', '\n') }.filter { it.isNotEmpty() }.joinToString("")
    }.getOrDefault("")
}
