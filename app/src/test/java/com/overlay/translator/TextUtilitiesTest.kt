package com.overlay.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilitiesTest {
    @Test fun detectsCyrillicDominance() {
        assertTrue(ScriptDetect.isMostlyCyrillic("Привет, мир!"))
        assertTrue(ScriptDetect.isMostlyCyrillic("Привет Alex"))
        assertFalse(ScriptDetect.isMostlyCyrillic("Hello world"))
    }

    @Test fun cleansModelBoilerplateAndLatinNoise() {
        assertEquals("Привет мир", RuText.clean("Translation: Привет world мир"))
    }

    @Test fun hashDistanceUsesHammingDistance() {
        assertEquals(0, PerceptualHash.distance(0b1010, 0b1010))
        assertEquals(2, PerceptualHash.distance(0b1010, 0b0011))
        assertTrue(PerceptualHash.isSimilar(0b1010, 0b1011, 1))
        assertFalse(PerceptualHash.isSimilar(0L, 0L))
    }
}
