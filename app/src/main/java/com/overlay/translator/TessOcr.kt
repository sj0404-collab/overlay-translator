package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI

class TessOcr(ctx: Context) {
    private val api = TessBaseAPI()
    private var lang = "eng"

    init {
        val dir = AssetCopy.ensureTess(ctx)
        api.init(dir.absolutePath, "eng+rus")
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
    }

    fun setMode(englishSource: Boolean) {
        lang = if (englishSource) "eng" else "rus"
        // already inited with both; bias via variable
        api.setVariable("classify_bln_numeric_mode", "0")
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
}
