package com.overlay.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OcrTextCleanerTest {
    @Test
    fun joinsOnlyActualLineWrapHyphens() {
        assertEquals(
            "МНЕ ХОРОШО ЗНАКОМО ЭТО ИМЯ.",
            OcrTextCleaner.normalizeLocalCyrillicCaption("МНЕ ХО-\nРОШО ЗНАКОМО ЭТО ИМЯ."),
        )
        assertEquals("из-за дома", OcrTextCleaner.normalizeLocalCyrillicCaption("из-за дома"))
    }

    @Test
    fun restoresVerifiedWordBoundariesWithoutUnknownWordReplacement() {
        assertEquals(
            "ПО СЛОВАМ «ОХОТНИЧЬЕГО ПСА», КОТОРЫЙ ПОСВЯТИЛ СЕБЯ ОТЦУ И СЕМЬЕ,",
            OcrTextCleaner.normalizeLocalCyrillicCaption(
                "ПОСЛОВАМ «ОХОТНИЧЬЕГОПСА», КОТОРЫЙПОСВЯТИЛ СЕБЯОТЦУИСЕМЬЕ,",
            ),
        )
        assertEquals("НЕИЗВЕСТНОЕСЛОВО", OcrTextCleaner.restoreKnownCaptionWords("НЕИЗВЕСТНОЕСЛОВО"))
    }

    @Test
    fun rejectsPunctuationOnlyNoise() {
        assertFalse(OcrTextCleaner.isAcceptableCyrillicOcrText("?!"))
    }
}
