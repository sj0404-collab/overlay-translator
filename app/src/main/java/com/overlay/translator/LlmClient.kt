package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object LlmClient {
    val ZEN_FREE = listOf(
        "big-pickle",
        "deepseek-v4-flash-free",
        "mimo-v2.5-free",
        "hy3-free",
        "nemotron-3-ultra-free",
        "nemotron-3.5-lightning-free",
        "laguna-s-2.1-free",
    )

    fun translateZen(text: String, model: String): String? {
        val prompt = "Translate English comic/manga dialogue to natural Russian. Output ONLY the Russian text, no quotes.\n\n$text"
        return chat("https://opencode.ai/zen/v1/chat/completions", model, prompt, null)
    }

    fun translateOpenRouter(text: String, key: String, model: String = "meta-llama/llama-3.2-3b-instruct:free"): String? {
        if (key.isBlank()) return null
        val prompt = "Translate to Russian. Output only the translation:\n$text"
        return chat("https://openrouter.ai/api/v1/chat/completions", model, prompt, key)
    }

    fun visionOcr(bmp: Bitmap, model: String): String? {
        val b64 = toJpegB64(bmp)
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text",
                        "Extract ALL printed text from this comic/manga image. Keep line breaks. English or Russian only. No commentary."))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                )))
        return postJson("https://opencode.ai/zen/v1/chat/completions", body, null)
    }

    private fun chat(endpoint: String, model: String, prompt: String, bearer: String?): String? {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_tokens", 400)
        return postJson(endpoint, body, bearer)
    }

    private fun postJson(endpoint: String, body: JSONObject, bearer: String?): String? {
        return try {
            val c = URL(endpoint).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20000
            c.readTimeout = 40000
            c.setRequestProperty("Content-Type", "application/json")
            if (!bearer.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $bearer")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                .bufferedReader().readText()
            c.disconnect()
            val msg = JSONObject(raw).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
            if (msg.isNullOrBlank()) null else msg
        } catch (_: Exception) {
            null
        }
    }

    private fun toJpegB64(bmp: Bitmap): String {
        val scaled = if (bmp.width > 768 || bmp.height > 768) {
            val s = 768f / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        } else bmp
        val os = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, os)
        return Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP)
    }
}
