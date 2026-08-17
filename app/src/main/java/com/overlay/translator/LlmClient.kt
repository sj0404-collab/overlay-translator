package com.overlay.translator

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal LLM-vision client. Three endpoints: Zen Vision (free, no key),
 * OpenRouter (user key), Google AI / Gemini Vision (user key). Also has
 * non-vision helpers: Zen translation, OpenRouter translation, Gemini
 * translation, and [detectSpeaker] for automatic voice selection.
 */
object LlmClient {
    val ZEN_FREE = listOf(
        "mimo-v2.5-free",
        "big-pickle",
        "deepseek-v4-flash-free",
        "hy3-free",
        "nemotron-3.5-lightning-free",
        "laguna-s-2.1-free",
    )

    val GEMINI_FREE = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash")

    val OR_FREE = listOf(
        "google/gemini-2.0-flash-exp:free",
        "meta-llama/llama-3.2-11b-vision-instruct:free",
        "qwen/qwen2.5-vl-7b-instruct:free",
        "nvidia/nemotron-nano-12b-v2-vl:free",
        "google/gemini-2.5-flash",
    )

    fun tokensFor(textOrHintWords: Int): Int {
        val n = textOrHintWords.coerceAtLeast(1)
        return (n * 4 + 48).coerceIn(64, 220)
    }

    fun visionOcr(bmp: Bitmap, model: String, russianOnly: Boolean, scanMode: String): String? =
        postJson(
            "https://opencode.ai/zen/v1/chat/completions", null,
            visionBody(model, bmp, ocrPrompt(russianOnly, scanMode), tokensFor(guessWords(bmp)), 512),
        )

    fun visionOpenRouter(bmp: Bitmap, key: String, model: String, russianOnly: Boolean): String? {
        if (key.isBlank()) return null
        return postJson(
            "https://openrouter.ai/api/v1/chat/completions", "Bearer $key",
            visionBody(model, bmp, ocrPrompt(russianOnly, "rect"), tokensFor(guessWords(bmp)), 512),
        )
    }

    fun visionGemini(ctx: android.content.Context, bmp: Bitmap, russianOnly: Boolean, scanMode: String): String? {
        val key = EnginePrefs.googleApiKey(ctx)
        if (key.isBlank()) return null
        val model = EnginePrefs.googleModel(ctx).ifBlank { GEMINI_FREE.first() }
        val base64 = toJpegB64(bmp, 1024)
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", ocrPrompt(russianOnly, scanMode)))
                    put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "image/png")
                        put("data", base64)
                    }))
                })
            }))
            put("generationConfig", JSONObject().put("temperature", 0.0))
        }
        return postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key", null,
            body,
        )
    }

    fun translateZen(text: String, model: String): String? {
        val words = text.split(Regex("\\s+")).size
        val prompt = "Переведи реплику комикса на естественный русский.\n" +
            "Пиши ТОЛЬКО кириллицей. Не оставляй английских слов (кроме имён).\n" +
            "Без кавычек и пояснений.\n\nТекст:\n$text"
        return chat("https://opencode.ai/zen/v1/chat/completions", null, model, prompt, tokensFor(words))
    }

    fun translateOpenRouter(text: String, key: String, model: String): String? {
        if (key.isBlank()) return null
        val words = text.split(Regex("\\s+")).size
        val prompt = "Переведи на русский (только кириллица, без латиницы). Только перевод:\n$text"
        return chat("https://openrouter.ai/api/v1/chat/completions", "Bearer $key", model, prompt, tokensFor(words))
    }

    fun translateGemini(ctx: android.content.Context, text: String): String? {
        val key = EnginePrefs.googleApiKey(ctx)
        if (key.isBlank()) return null
        val model = EnginePrefs.googleModel(ctx).ifBlank { GEMINI_FREE.first() }
        val prompt = "Переведи на естественный русский язык (только кириллица, без латиницы). " +
            "Никаких пояснений или кавычек. Только перевод:\n$text"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().put("temperature", 0.0))
        }
        return postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key", null,
            body,
        )
    }

    /** Ask the LLM who speaks this dialogue (MALE/FEMALE/NARRATOR/TEEN). */
    fun detectSpeaker(ctx: android.content.Context, text: String): VoiceKind? {
        val key = EnginePrefs.googleApiKey(ctx)
        if (key.isBlank()) return null
        val model = EnginePrefs.googleModel(ctx).ifBlank { GEMINI_FREE.first() }
        val prompt = "Look at this dialogue / caption text and return the speaker's gender as a single word: " +
            "MALE, FEMALE, TEEN, or NARRATOR. Just return the word, nothing else.\n\nText:\n$text"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().put("temperature", 0.0))
        }
        return runCatching {
            val c = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 8000; readTimeout = 12000
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            c.disconnect()
            val word = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "")
                ?.trim()?.split(" ")?.firstOrNull()?.uppercase() ?: return@runCatching null
            when (word) { "MALE" -> VoiceKind.MALE; "FEMALE" -> VoiceKind.FEMALE;
                "TEEN" -> VoiceKind.TEEN; else -> VoiceKind.OTHER }
        }.getOrNull()
    }

    fun postJson(endpoint: String, bearer: String?, body: JSONObject): String? {
        return runCatching {
            val c = URL(endpoint).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; doOutput = true
            c.connectTimeout = 15000; c.readTimeout = 35000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("HTTP-Referer", "https://github.com/sj0404-collab/overlay-translator")
            c.setRequestProperty("X-Title", "OverlayTranslator")
            if (!bearer.isNullOrBlank()) c.setRequestProperty("Authorization", bearer)
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
        }.getOrNull()
    }

    /* ───────────────── helpers ───────────────── */

    private fun chat(endpoint: String, bearer: String?, model: String, prompt: String, maxTok: Int): String? {
        val body = JSONObject().put("model", model).put("max_tokens", maxTok)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        return postJson(endpoint, bearer, body)
    }

    private fun visionBody(model: String, bmp: Bitmap, prompt: String, maxTok: Int, maxSide: Int): JSONObject {
        val b64 = toJpegB64(bmp, maxSide)
        return JSONObject().put("model", model).put("max_tokens", maxTok)
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))))
    }

    private fun ocrPrompt(russianOnly: Boolean, scanMode: String): String {
        val lang = if (russianOnly)
            "Текст ТОЛЬКО на русском (кириллица). Латиницу не пиши, кроме имён если они реально на экране."
        else "Any text visible in this image. English, Russian, Japanese, or any other language. Transcribe exactly as written."
        val modeHint = when (scanMode) {
            "bubble" -> "This is one speech bubble. Extract only the dialogue inside."
            "full" -> "Extract all readable dialogue, top to bottom."
            else -> "Extract all printed text in the selected area."
        }
        return "$modeHint $lang Output ONLY the raw text, keep line breaks and spaces between words. No comments."
    }

    private fun guessWords(bmp: Bitmap): Int {
        val area = bmp.width * bmp.height
        return (area / 14000).coerceIn(4, 40)
    }

    private fun toJpegB64(bmp: Bitmap, maxSide: Int): String {
        val m = maxOf(bmp.width, bmp.height)
        val scaled = if (m > maxSide) {
            val s = maxSide.toFloat() / m
            Bitmap.createScaledBitmap(bmp,
                (bmp.width * s).toInt().coerceAtLeast(32),
                (bmp.height * s).toInt().coerceAtLeast(32), true)
        } else bmp
        val os = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 62, os)
        return Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP)
    }
}
