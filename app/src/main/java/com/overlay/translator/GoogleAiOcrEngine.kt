package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Google AI (Gemini Vision) OCR engine, ported from Yomihon's reader build.
 * Requires a user-provided API key stored in [EnginePrefs.googleApiKey].
 */
class GoogleAiOcrEngine(private val ctx: Context) : OcrEngine {

    override fun recognizeText(image: Bitmap): String {
        require(!image.isRecycled) { "Input bitmap is recycled" }
        val apiKey = EnginePrefs.googleApiKey(ctx)
        if (apiKey.isBlank()) throw IllegalStateException("Google AI API key not set")
        val model = EnginePrefs.googleModel(ctx).ifBlank { "gemini-2.5-flash" }
        val base64 = encodePngBase64(image)

        val jsonBody = JSONObject().apply {
            put("generationConfig", JSONObject().put("temperature", 0.0))
            val contents = JSONArray()
            val item = JSONObject()
            val parts = JSONArray()
            parts.put(JSONObject().apply {
                put("text", "Perform STRICT OPTICAL CHARACTER RECOGNITION (OCR) ONLY. " +
                    "Transcribe the exact characters seen in this image verbatim. " +
                    "Do not hallucinate, do not translate, do not add any markdown formatting or commentary.")
            })
            parts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/png")
                    put("data", base64)
                })
            })
            item.put("parts", parts)
            contents.put(item)
            put("contents", contents)
        }

        val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (status !in 200..299) throw RuntimeException("Gemini Vision failed HTTP $status: ${text.take(200)}")
            val resp = JSONObject(text)
            val candidates = resp.optJSONArray("candidates")
            val extracted = if (candidates != null && candidates.length() > 0) {
                val partsArr = candidates.getJSONObject(0).optJSONObject("content")
                    ?.optJSONArray("parts")
                if (partsArr != null && partsArr.length() > 0) {
                    partsArr.getJSONObject(0).optString("text", "")
                } else ""
            } else ""
            val totalTokens = resp.optJSONObject("usageMetadata")
                ?.optLong("totalTokenCount", 120L) ?: 120L
            EnginePrefs.incrementTokens(ctx, totalTokens)
            extracted.trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePngBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }
}
