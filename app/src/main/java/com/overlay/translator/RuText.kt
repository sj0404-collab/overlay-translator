package com.overlay.translator

object RuText {
    fun clean(raw: String): String {
        var s = raw.replace(Regex("(?is)```.*?```"), " ")
            .replace(Regex("(?i)^(here is|extracted|translation|перевод)[:\\s]*"), "")
            .replace('ё', 'е')
        val lines = s.split('\n').map { line ->
            val kept = line.split(Regex("\\s+")).filter { w ->
                if (w.isEmpty()) false
                else {
                    val cyr = w.count { it in '\u0400'..'\u04FF' }
                    val lat = w.count { it in 'A'..'z' }
                    cyr >= lat || w.length <= 2
                }
            }
            kept.joinToString(" ")
        }.filter { it.isNotBlank() }
        return lines.joinToString("\n").replace(Regex("[ \t]+"), " ").trim()
    }

    fun preferRussianPrompt(): Boolean = true
}
