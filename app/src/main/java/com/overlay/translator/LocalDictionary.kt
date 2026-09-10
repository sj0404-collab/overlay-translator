package com.overlay.translator

import android.content.Context
import org.json.JSONObject

/**
 * Bundled EN→RU dictionary (from the desktop ScreenOCR_TTS corpus) used for
 * fully-offline per-word translation. Loaded from assets lazily; the file is
 * a flat JSON map of lowercase English word → UPPERCASE Russian sense.
 */
class LocalDictionary(private val ctx: Context) {

    @Volatile
    private var words: Map<String, String>? = null

    private fun load(): Map<String, String> {
        words?.let { return it }
        val loaded = runCatching {
            val raw = ctx.assets.open("dict/en_ru.json").bufferedReader()
                .use { it.readText() }
            val obj = JSONObject(raw)
            val keys = obj.keys()
            val map = HashMap<String, String>((obj.length().toDouble() / 0.75f).toInt())
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.getString(k)
            }
            map
        }.getOrNull() ?: emptyMap()
        words = loaded
        return loaded
    }

    /**
     * Looks up a single token. Returns null when the dictionary was not loaded
     * or the token is not present. Matching is case- and punctuation-insensitive.
     */
    fun lookup(raw: String): String? {
        val token = raw.trim().trim('«', '»', '"', '\'', ',', '.', '!', '?', ':', ';', '(', ')', '-', '—')
        if (token.isEmpty()) return null
        val key = token.lowercase()
        val hit = load()[key] ?: return null
        return hit
    }

    /**
     * Translates a whole fragment token-by-token, keeping unknown words and any
     * non-letter spans intact. Unknown or Cyrillic words are passed through.
     */
    fun translateFragment(text: String): String {
        if (!isUsable()) return text
        val found = StringBuilder(text.length)
        val token = StringBuilder()
        var tokenChanged = false
        fun flush() {
            if (token.isNotEmpty()) {
                val t = token.toString()
                val tr = if (tokenChanged) null else lookup(t) ?: t
                found.append(tr)
                token.setLength(0)
                tokenChanged = false
            }
        }
        for (ch in text) {
            if (ch.isLetter()) {
                token.append(ch)
            } else {
                flush()
                found.append(ch)
            }
        }
        flush()
        return found.toString()
    }

    private fun isUsable(): Boolean {
        return load().isNotEmpty()
    }
}