package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI

enum class ScanLang { EN, RU, BOTH }

class TessOcr(ctx: Context) {
    private val dataPath = AssetCopy.ensureTess(ctx).absolutePath
    private val api = TessBaseAPI()
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
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        api.setVariable("tessedit_char_blacklist", "|[]{}<>")
        when (l) {
            ScanLang.EN -> api.setVariable(
                "tessedit_char_whitelist",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,!?;:'\"()-"
            )
            ScanLang.RU -> api.setVariable(
                "tessedit_char_whitelist",
                "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя0123456789 .,!?;:'\"()-"
            )
            ScanLang.BOTH -> {}
        }
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
