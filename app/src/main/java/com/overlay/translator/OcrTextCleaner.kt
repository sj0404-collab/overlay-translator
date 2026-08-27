package com.overlay.translator

/**
 * Постобработка текста после CTC-декодирования и онлайн-движков.
 *
 * 1. [fixLookalikesPerWord] — пословная правка: в словах С кириллицей
 *    латинские омоглифы и похожие цифры заменяются на кириллицу (N→Н, I→И,
 *    5→Б …), чтобы текст читался и TTS не диктовал буквы. Чистая латынь не
 *    транслитерируется автоматически: локальный CTC-модель часто выдаёт её
 *    как мусор на декоративных шрифтах, и «исправление» превращало ошибку в
 *    убедительную, но выдуманную русскую фразу;
 * 2. [joinLineHyphens] — переносы слов соединяются («пере-\nносится» →
 *    «переносится»);
 * 3. [looksLikeDictionaryRamp] — фильтр мусора «словарной лесенкой».
 */
object OcrTextCleaner {

    private val HYPHEN_LINE_BREAK = Regex("([\\p{L}])-[ \\t]*\\n[ \\t]*([\\p{L}])")
    private val CYRILLIC_RUN = Regex("[А-Яа-яЁё]{2,}")

    private val LATIN_WHITELIST = setOf(
        "sos", "bmw", "wi-fi", "ok", "tv", "dvd", "3d", "hp", "pc", "usb", "sim", "sd",
    )

    /**
     * Расширенная таблица омоглифов и визуальных замен для слов, в которых
     * уже есть кириллица. Собрана по реальным промахам модели на манге:
     * N→Н, I→И, L→Л, D→Д, G→Г, W/V→В, Z→З, J→Й, S→С, 5→Б, 0→О, 4→Ч…
     */
    private val EXT_LOOKALIKE_MAP = mapOf(
        'A' to 'А', 'a' to 'а',
        'B' to 'В',
        'C' to 'С', 'c' to 'с',
        'E' to 'Е', 'e' to 'е',
        'H' to 'Н',
        'K' to 'К', 'k' to 'к',
        'M' to 'М', 'm' to 'м',
        'O' to 'О', 'o' to 'о',
        'P' to 'Р', 'p' to 'р',
        'T' to 'Т', 't' to 'т',
        'X' to 'Х', 'x' to 'х',
        'y' to 'у',
        '3' to 'З', '6' to 'б',
        'I' to 'И', 'L' to 'Л', 'N' to 'Н', 'D' to 'Д', 'G' to 'Г',
        'W' to 'В', 'V' to 'В', 'Z' to 'З', 'J' to 'Й', 'S' to 'С',
        's' to 'с',
        '5' to 'Б', '0' to 'О', '4' to 'Ч',
    )

    fun joinLineHyphens(text: String): String {
        if (!text.contains('-')) return text
        return HYPHEN_LINE_BREAK.replace(text) { m ->
            m.groupValues[1] + m.groupValues[2]
        }
    }

    /**
     * Нормализует локально распознанную русскую подпись в правильном порядке.
     *
     * Сначала склеиваем переносы «ХО-\nРОШО», пока граница строки ещё
     * сохранена. Лишь затем восстанавливаем безопасные границы известных
     * слов. Раньше [restoreKnownCaptionWords] сворачивал `\n` в пробел до
     * [joinLineHyphens], и пользователь видел ложное «ХО- РОШО».
     */
    fun normalizeLocalCyrillicCaption(text: String): String {
        return restoreKnownCaptionWords(
            fixLookalikesPerWord(joinLineHyphens(text)),
        )
    }

    fun fixLookalikesPerWord(text: String): String {
        if (text.isEmpty()) return text
        return text.split(' ').joinToString(" ") { word ->
            val hasCyrillic = word.any { it.code in CYRILLIC_RANGE }
            val hasLatin = word.any { it.isLetter() && it.code < 0x80 }
            when {
                hasCyrillic -> mapChars(word, EXT_LOOKALIKE_MAP)
                hasLatin -> {
                    val bare = word.trimEnd('.', '!', ',', '?', '…').lowercase()
                    if (bare in LATIN_WHITELIST) word else word
                }
                else -> word
            }
        }
    }

    /**
     * Принимает только осмысленный результат русского локального движка.
     * Пунктуация, корейские SFX и CTC-мусор с остаточной латиницей не должны
     * попадать в переводчик как «русский» текст. Допускаются короткие
     * общеупотребимые латинские токены из белого списка (`SOS`, `Wi-Fi`, `3D`).
     */
    fun isAcceptableCyrillicOcrText(text: String): Boolean {
        val words = text.split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return false

        return words.all { word ->
            val lexical = word.filter(Char::isLetterOrDigit)
            if (lexical.isEmpty()) return@all false

            val cyrillicCount = lexical.count { it.code in CYRILLIC_RANGE }
            val latinCount = lexical.count { it.isLetter() && it.code < 0x80 }
            if (cyrillicCount > 0) {
                latinCount == 0
            } else {
                word.trimEnd('.', '!', ',', '?', '…').lowercase() in LATIN_WHITELIST
            }
        }
    }

    /**
     * Доля букв, которые уже можно безопасно читать как кириллицу. Нужна не
     * для словарной подмены, а для выбора между двумя визуальными моделями.
     */
    fun cyrillicFitness(text: String): Float {
        val repaired = fixLookalikesPerWord(text)
        val letters = repaired.filter(Char::isLetter)
        if (letters.isEmpty()) return 0f
        return letters.count { it.code in CYRILLIC_RANGE }.toFloat() / letters.length
    }

    /**
     * PP-OCR recognizers correctly read most glyphs in compact manga captions,
     * but their CTC vocabulary has no explicit space token. Restore a boundary
     * only when **every** resulting fragment is in a small offline caption
     * lexicon. This is deliberately not spell correction and never substitutes
     * a different word; an unknown run is left unchanged.
     */
    fun restoreKnownCaptionWords(text: String): String {
        if (text.isBlank()) return text
        val restored = CYRILLIC_RUN.replace(text) { match ->
            restoreRun(match.value)
        }
        return restored
            .replace(Regex("([»”])(?=[А-ЯЁ])"), "${'$'}1 ")
            .replace(Regex("(?<=[А-ЯЁа-яё])([«„])"), " ${'$'}1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun restoreRun(source: String): String {
        val upper = source.uppercase()
        val segments = splitKnownWords(upper)
        val normalized = (segments ?: listOf(upper)).map(::normalizeCaptionWord)
        val joined = normalized.joinToString(" ")
        val letters = source.filter(Char::isLetter)
        return when {
            letters.all(Char::isUpperCase) -> joined
            source.firstOrNull()?.isUpperCase() == true -> joined.lowercase().replaceFirstChar(Char::uppercaseChar)
            else -> joined.lowercase()
        }
    }

    private fun splitKnownWords(run: String): List<String>? {
        if (run.length < 4) return null
        val best = arrayOfNulls<List<String>>(run.length + 1)
        best[0] = emptyList()
        for (start in run.indices) {
            val prefix = best[start] ?: continue
            for (end in start + 1..run.length) {
                val word = run.substring(start, end)
                if (word !in CAPTION_WORDS) continue
                val candidate = prefix + word
                val current = best[end]
                if (current == null || candidate.size > current.size) best[end] = candidate
            }
        }
        return best[run.length]?.takeIf { it.size > 1 }
    }

    private fun normalizeCaptionWord(word: String): String = CAPTION_NORMALIZATIONS[word] ?: word

    private fun mapChars(word: String, map: Map<Char, Char>): String {
        val sb = StringBuilder(word.length)
        for (char in word) sb.append(map[char] ?: char)
        return sb.toString()
    }

    /**
     * Правда, если в тексте есть «лесенка» из 6+ символов подряд по порядку
     * кодов (цифры/латиница/кириллица) — верный признак того, что декодер
     * дрейфовал по словарю, а не читал надпись.
     */
    fun looksLikeDictionaryRamp(text: String): Boolean {
        var run = 1
        var previous = -2
        for (char in text) {
            val code = char.code
            run = if (code == previous + 1) run + 1 else 1
            if (run >= 6) return true
            previous = code
        }
        return false
    }

    private val CYRILLIC_RANGE = 0x0400..0x052F

    /**
     * Common closed-class words plus the proper nouns and caption vocabulary
     * demonstrated in verified device regressions. The set only authorizes
     * boundary insertion; it is not used to replace unknown OCR output.
     */
    private val CAPTION_WORDS = setOf(
        "А", "В", "ВО", "И", "К", "НА", "НЕ", "НО", "О", "ОБ", "ОН", "ОТ", "ПО", "С", "СО", "У",
        "БЫЛ", "БЫЛА", "БЫЛИ", "БЫТЬ", "ВСЕ", "ГДЕ", "ДЕМОНОМ", "ДОМА", "ЕГО", "ЕЙ", "ЕСТЬ",
        "ИЗ", "КАК", "КОТОРЫЙ", "ЛОЖНО", "ЛЮДИ", "МЫ", "НЕТ", "ОБВИНЕН", "ОБВИНЁН",
        "ОТЦУ", "ОХОТНИЧИЙ", "ОХОТНИЧЬЕГО", "ПАЛ", "ПЕС", "ПЁС", "ПОД", "ПОСВЯТИЛ", "ПРИНЯЛ",
        "РЕШЕНИЕ", "СГОВОРЕ", "СЕБЯ", "СЕМЬЕ", "СЛОВАМ", "ТЕЛЕ", "ТЯЖЕСТЬ", "ШУМНО",
        "ЛЕЗВИЕМ", "ГИЛЬОТИНЫ", "ПСА", "ВИКИР", "ВАН", "БАСКЕРВИЛЕЙ", "БАСКЕРВИЛЬ",
    )

    private val CAPTION_NORMALIZATIONS = mapOf(
        "ОБВИНЕН" to "ОБВИНЁН",
        "ПЕС" to "ПЁС",
    )
}
