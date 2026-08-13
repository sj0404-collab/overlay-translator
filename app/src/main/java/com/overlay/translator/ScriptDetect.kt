package com.overlay.translator

enum class ScanLang { EN, RU, BOTH }

object ScriptDetect {
    fun isMostlyCyrillic(text: String): Boolean {
        var cyr = 0
        var lat = 0
        for (ch in text) {
            when (ch) {
                in 'А'..'я', 'Ё', 'ё' -> cyr++
                in 'A'..'z' -> lat++
            }
        }
        return cyr > 0 && cyr >= lat
    }

    fun preferRu(text: String): Boolean = isMostlyCyrillic(text)
}
