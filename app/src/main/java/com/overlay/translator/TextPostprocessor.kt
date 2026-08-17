package com.overlay.translator

/**
 * Post-processing for OCR output lifted from Yomihon's reader build.
 * Mainly useful for Japanese text:
 *   - half-width → full-width digit / ASCII conversion
 *   - ellipsis collapsing
 *   - repeated `!` / `?` collapsing into single glyphs
 */
class TextPostprocessor {

    private val sb = StringBuilder(512)

    fun postprocess(text: String): String {
        if (text.isEmpty()) return text
        val normalized = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map(::postprocessSingleLine)
            .dropWhile(String::isEmpty)
            .dropLastWhile(String::isEmpty)
        return normalized.joinToString(separator = "\n")
    }

    private fun postprocessSingleLine(text: String): String {
        if (text.isEmpty()) return text
        val hasJapanese = text.any { it.isJapaneseScript() }
        sb.setLength(0)
        sb.ensureCapacity(text.length)

        var i = 0
        val len = text.length
        var prev: Char? = null
        while (i < len) {
            val c = text[i]
            if (c.isWhitespace()) {
                var nx = i + 1
                while (nx < len && text[nx].isWhitespace()) nx++
                val keep = prev != null && text.getOrNull(nx) != null &&
                    !prev!!.isJapaneseScript() && !(text[nx].isJapaneseScript())
                if (keep && (sb.isEmpty() || sb.last() != ' ')) sb.append(' ')
                i = nx
                continue
            }
            if (c == '…') { sb.append("..."); prev = c; i++; continue }
            if (c.isDotLike()) {
                var cnt = 1
                var nx = i + 1
                while (nx < len && text[nx].isDotLike()) { cnt++; nx++ }
                if (cnt >= 2) {
                    repeat(cnt) { sb.append('.') }
                    prev = text[nx - 1]; i = nx; continue
                }
            }
            if (c.isExclLike() || c.isQuesLike()) {
                var cnt = 1
                var nx = i + 1
                while (nx < len && (text[nx].isExclLike() || text[nx].isQuesLike())) { cnt++; nx++ }
                if (cnt >= 2) {
                    for (k in i until nx) sb.append(if (text[k].isExclLike()) '!' else '?')
                    prev = text[nx - 1]; i = nx; continue
                }
            }
            if (hasJapanese) {
                val code = c.code
                if (code < HALF_TO_FULL_TABLE.size) {
                    sb.append(HALF_TO_FULL_TABLE[code])
                } else sb.append(c)
            } else sb.append(c)
            prev = c
            i++
        }
        return sb.toString()
    }

    private fun Char.isJapaneseScript(): Boolean {
        val cp = code
        return cp in 0x3040..0x30FF ||
            cp in 0x4E00..0x9FFF ||
            cp in 0x3400..0x4DBF ||
            cp in 0xF900..0xFAFF
    }

    private fun Char.isDotLike() = this == '.' || this == '．' || this == '・' || this == '･'
    private fun Char.isExclLike() = this == '!' || this == '！'
    private fun Char.isQuesLike() = this == '?' || this == '？'

    companion object {
        /** Half-width ASCII → full-width glyph mapping used by Yomihon. */
        private val HALF_TO_FULL_TABLE = CharArray(127) { it.toChar() }.apply {
            this['!'.code] = '！'
            this['"'.code] = '＂'
            this['#'.code] = '＃'
            this['$'.code] = '＄'
            this['%'.code] = '％'
            this['&'.code] = '＆'
            this['\''.code] = '＇'
            this['('.code] = '（'
            this[')'.code] = '）'
            this['*'.code] = '＊'
            this['+'.code] = '＋'
            this[','.code] = '，'
            this['-'.code] = '－'
            this['.'.code] = '．'
            this['/'.code] = '／'
            for (d in '0'..'9') this[d.code] = ('０'.code + (d.code - '0'.code)).toChar()
            this[':'.code] = '：'
            this[';'.code] = '；'
            this['<'.code] = '＜'
            this['='.code] = '＝'
            this['>'.code] = '＞'
            this['?'.code] = '？'
            this['@'.code] = '＠'
            for (d in 'A'..'Z') this[d.code] = ('Ａ'.code + (d.code - 'A'.code)).toChar()
            this['['.code] = '［'
            this['\\'.code] = '＼'
            this[']'.code] = '］'
            this['^'.code] = '＾'
            this['_'.code] = '＿'
            this['`'.code] = '＇'
            for (d in 'a'..'z') this[d.code] = ('ａ'.code + (d.code - 'a'.code)).toChar()
            this['{'.code] = '｛'
            this['|'.code] = '｜'
            this['}'.code] = '｝'
            this['~'.code] = '～'
        }
    }
}
