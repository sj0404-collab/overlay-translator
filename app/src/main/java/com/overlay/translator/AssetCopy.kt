package com.overlay.translator

import android.content.Context
import java.io.File

object AssetCopy {
    fun ensureTess(ctx: Context): File {
        val dir = File(ctx.filesDir, "tesseract")
        val td = File(dir, "tessdata")
        if (!td.exists()) td.mkdirs()
        for (name in listOf("eng.traineddata", "rus.traineddata")) {
            val out = File(td, name)
            if (out.exists() && out.length() > 1000) continue
            ctx.assets.open("tessdata/$name").use { inp ->
                out.outputStream().use { inp.copyTo(it) }
            }
        }
        return dir
    }

    fun copyModel(ctx: Context, assetName: String): File {
        val out = File(ctx.filesDir, assetName)
        if (!out.exists() || out.length() < 100) {
            ctx.assets.open("models/$assetName").use { inp ->
                out.outputStream().use { inp.copyTo(it) }
            }
        }
        return out
    }
}
