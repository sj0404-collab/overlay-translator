package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI

class TessOcr(private val ctx: Context) {
    private val dataPath = AssetCopy.ensureTess(ctx).absolutePath
    private var api: TessBaseAPI? = null
    private var openedLang: ScanLang? = null

    init { reopen(ScanLang.RU) }

    @Synchronized
    fun reopen(l: ScanLang) {
        if (api != null && openedLang == l) return
        try { api?.recycle() } catch (_: Exception) {}
        val next = TessBaseAPI()
        val code = when (l) {
            ScanLang.EN -> "eng"
            ScanLang.RU -> "rus"
            ScanLang.BOTH -> "eng+rus"
        }
        if (!next.init(dataPath, code)) {
            next.recycle()
            api = null
            openedLang = null
            return
        }
        next.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        api = next
        openedLang = l
    }

    @Synchronized
    fun read(bmp: Bitmap): String = try {
        api?.setImage(bmp)
        (api?.getUTF8Text() ?: "").trim()
    } catch (_: Exception) { "" }

    @Synchronized
    fun close() {
        try { api?.recycle() } catch (_: Exception) {}
        api = null
        openedLang = null
    }
}
