package com.overlay.translator

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Materializes the bundled PP-OCR assets to app-private files, because LiteRT
 * opens models through file paths. No model is downloaded at runtime.
 */
object BundledCyrillicModels {
    private const val TAG = "BundledCyrillicModels"
    private const val ASSET_ROOT = "cyrillic_ocr"

    fun resolve(context: Context, assetPath: String): String? {
        val destination = File(context.filesDir, "ocr_models/$assetPath")
        if (destination.isFile && destination.length() > 0L) return destination.absolutePath

        return runCatching {
            destination.parentFile?.mkdirs()
            context.assets.open("$ASSET_ROOT/${File(assetPath).name}").use { input ->
                destination.outputStream().use(input::copyTo)
            }
            destination.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        }.onFailure { error ->
            destination.delete()
            Log.e(TAG, "Unable to prepare bundled model $assetPath", error)
        }.getOrNull()
    }
}
