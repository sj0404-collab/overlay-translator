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
 * Vision OCR routed through OpenRouter. Uses a user-supplied API key from
 * [EnginePrefs.openrouterKey] and a model picked in [EnginePrefs.orModel].
 * Defaults align with Yomihon's reader build (`google/gemini-2.5-flash`).
 */
class OpenRouterOcrEngine(private val ctx: Context) : OcrEngine {

    override fun recognizeText(image: Bitmap): String {
        require(!image.isRecycled) { "Input bitmap is recycled" }
        val key = EnginePrefs.openrouterKey(ctx)
        if (key.isBlank()) throw IllegalStateException("OpenRouter API key is empty")
        val model = EnginePrefs.orModel(ctx).ifBlank { "google/gemini-2.5-flash" }
        val base64 = encodePngBase64(image)

        val json = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            val msgs = JSONArray()
            val userMsg = JSONObject()
            userMsg.put("role", "user")
            val content = JSONArray()
            content.put(JSONObject().apply {
                put("type", "text")
                put("text", "Perform STRICT OPTICAL CHARACTER RECOGNITION (OCR) ONLY. " +
                    "Transcribe the exact text from the image verbatim without translating, " +
                    "explaining, summarizing, or adding any commentary. If no text is visible, " +
                    "return empty string.")
            })
            content.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/png;base64,$base64")
                })
            })
            userMsg.put("content", content)
            msgs.put(userMsg)
            put("messages", msgs)
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("HTTP-Referer", "https://github.com/sj0404-collab/overlay-translator")
            setRequestProperty("X-Title", "OverlayTranslator")
        }
        return try {
            connection.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (status !in 200..299) throw RuntimeException("OpenRouter failed HTTP $status: ${body.take(200)}")
            val resp = JSONObject(body)
            val choices = resp.optJSONArray("choices")
            val extracted = if (choices != null && choices.length() > 0) {
                choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: ""
            } else ""
            val tokens = resp.optJSONObject("usage")?.optLong("total_tokens", 100L) ?: 100L
            EnginePrefs.incrementTokens(ctx, tokens)
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

    companion object {
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    }
}
