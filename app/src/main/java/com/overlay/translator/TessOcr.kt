package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI

class TessOcr(private val ctx: Context) {
    private val dataPath = AssetCopy.ensureTess(ctx).absolutePath
    private var api: TessBaseAPI? = null

    init { reopen(ScanLang.RU) }

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
        api = next
    }

    fun read(bmp: Bitmap): String = try {
        api?.setImage(bmp)
        (api?.getUTF8Text() ?: "").trim()
    } catch (_: Exception) { "" }

    fun close() { try { api?.recycle() } catch (_: Exception) {} }
}
