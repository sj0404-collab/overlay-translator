package com.overlay.translator

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object LlmClient {
    val ZEN_FREE = listOf(
        "mimo-v2.5-free",
        "big-pickle",
        "deepseek-v4-flash-free",
        "hy3-free",
        "nemotron-3.5-lightning-free",
        "laguna-s-2.1-free",
    )

    val OR_FREE = listOf(
        "qwen/qwen2.5-vl-7b-instruct:free",
        "google/gemini-2.0-flash-exp:free",
        "meta-llama/llama-3.2-11b-vision-instruct:free",
        "nvidia/nemotron-nano-12b-v2-vl:free",
    )

    fun tokensFor(textOrHintWords: Int): Int {
        val n = textOrHintWords.coerceAtLeast(1)
        return (n * 4 + 48).coerceIn(64, 220)
    }

    fun visionOcr(bmp: Bitmap, model: String, russianOnly: Boolean, scanMode: String): String? {
        val wordsGuess = guessWords(bmp)
        val maxTok = tokensFor(wordsGuess)
        val lang = if (russianOnly)
            "Текст ТОЛЬКО на русском (кириллица). Латиницу не пиши, кроме имён если они реально на экране."
        else
            "English or Russian as on the image."
        val modeHint = when (scanMode) {
            "bubble" -> "This is one speech bubble. Extract only the dialogue inside."
            "full" -> "Extract all readable dialogue, top to bottom."
            else -> "Extract printed comic text in the selected crop."
        }
        val prompt = "$modeHint $lang Output ONLY the raw text, keep line breaks. No comments. No transcription notes."
        val body = visionBody(model, bmp, prompt, maxTok, 512)
        return postJson("https://opencode.ai/zen/v1/chat/completions", body, null)
    }

    fun visionOpenRouter(bmp: Bitmap, key: String, model: String, russianOnly: Boolean): String? {
        if (key.isBlank()) return null
        val prompt = if (russianOnly)
            "Extract ONLY Russian (Cyrillic) text from this comic image. No Latin words. Output text only."
        else
            "Extract printed text from this comic. Output text only."
        val body = visionBody(model, bmp, prompt, tokensFor(guessWords(bmp)), 512)
        return postJson("https://openrouter.ai/api/v1/chat/completions", body, key)
    }

    fun translateZen(text: String, model: String): String? {
        val words = text.split(Regex("\\s+")).size
        val prompt = """Переведи реплику комикса на естественный русский.
Пиши ТОЛЬКО кириллицей. Не оставляй английских слов (кроме имён).
Без кавычек и пояснений.

Текст:
$text"""
        return chat("https://opencode.ai/zen/v1/chat/completions", model, prompt, null, tokensFor(words))
    }

    fun translateOpenRouter(text: String, key: String, model: String): String? {
        if (key.isBlank()) return null
        val words = text.split(Regex("\\s+")).size
        val prompt = "Переведи на русский (только кириллица, без латиницы). Только перевод:\n$text"
        return chat("https://openrouter.ai/api/v1/chat/completions", model, prompt, key, tokensFor(words))
    }

    private fun visionBody(model: String, bmp: Bitmap, prompt: String, maxTok: Int, maxSide: Int): JSONObject {
        val b64 = toJpegB64(bmp, maxSide)
        return JSONObject()
            .put("model", model)
            .put("max_tokens", maxTok)
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                )))
    }

    private fun chat(endpoint: String, model: String, prompt: String, bearer: String?, maxTok: Int): String? {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTok)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        return postJson(endpoint, body, bearer)
    }

    private fun postJson(endpoint: String, body: JSONObject, bearer: String?): String? {
        return try {
            val c = URL(endpoint).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 35000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("HTTP-Referer", "https://github.com/sj0404-collab/overlay-translator")
            c.setRequestProperty("X-Title", "OverlayTranslator")
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
            if (msg.isNullOrBlank()) null else RuText.clean(msg)
        } catch (_: Exception) {
            null
        }
    }

    private fun guessWords(bmp: Bitmap): Int {
        val area = bmp.width * bmp.height
        return (area / 14000).coerceIn(4, 40)
    }

    private fun toJpegB64(bmp: Bitmap, maxSide: Int): String {
        val m = maxOf(bmp.width, bmp.height)
        val scaled = if (m > maxSide) {
            val s = maxSide.toFloat() / m
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(32), (bmp.height * s).toInt().coerceAtLeast(32), true)
        } else bmp
        val os = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 62, os)
        return Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP)
    }
}
