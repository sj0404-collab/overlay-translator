package com.overlay.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

object LocalNmt {
    private val client by lazy {
        val opt = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()
        Translation.getClient(opt)
    }

    fun ensure(): Boolean {
        return try {
            Tasks.await(client.downloadModelIfNeeded(), 90, TimeUnit.SECONDS)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun translate(text: String): String? {
        return try {
            if (!ensure()) return null
            Tasks.await(client.translate(text), 15, TimeUnit.SECONDS)?.trim()?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
