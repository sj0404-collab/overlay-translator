package com.overlay.translator

import android.content.Context
import java.util.Locale

class PhraseBank(ctx: Context) {
    private val phrases = ArrayList<String>()
    private val toRu = HashMap<String, String>()

    init {
        runCatching {
            ctx.assets.open("models/phrase_labels.txt").bufferedReader().forEachLine {
                if (it.isNotBlank()) phrases.add(it)
            }
        }
        runCatching {
            ctx.assets.open("models/en_ru_dict.tsv").bufferedReader().forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 2) {
                    val a = p[0].trim()
                    val b = p[1].trim()
                    if (a.isNotEmpty()) {
                        toRu[a.lowercase(Locale.US)] = b
                        phrases.add(a)
                        phrases.add(b)
                    }
                }
            }
        }
    }

    fun correct(ocr: String): String {
        val t = ocr.trim()
        if (t.length < 2) return t
        val key = t.lowercase(Locale.US)
        toRu[key]?.let { return t }
        var best = t
        var bestD = Int.MAX_VALUE
        for (p in phrases) {
            val d = dist(key, p.lowercase(Locale.US))
            val lim = (p.length / 3).coerceAtLeast(1)
            if (d < bestD && d <= lim && d < p.length) {
                bestD = d
                best = p
            }
        }
        return best
    }

    fun ruOf(text: String): String? {
        val k = text.lowercase(Locale.US)
        toRu[k]?.let { return it }
        // try corrected
        val c = correct(text)
        return toRu[c.lowercase(Locale.US)]
    }

    private fun dist(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        if (kotlin.math.abs(n - m) > 8) return 99
        val dp = IntArray(m + 1) { it }
        for (i in 1..n) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..m) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = tmp
            }
        }
        return dp[m]
    }
}
