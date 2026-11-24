package com.voicepad.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test fun numbersInWords() {
        assertEquals("zero", SpeechText.numberToWords(0))
        assertEquals("forty two", SpeechText.numberToWords(42))
        assertEquals("one thousand nine hundred eighty four", SpeechText.numberToWords(1984))
        assertEquals("two million five", SpeechText.numberToWords(2_000_005))
    }

    @Test fun spellsTimesPricesPercentagesAndOrdinals() {
        assertEquals("It leaves at three forty five pm.", SpeechText.spellNumbers("It leaves at 3:45pm."))
        assertEquals("at nine o'clock and ten oh five", SpeechText.spellNumbers("at 9:00 and 10:05"))
        assertEquals("twelve dollars and fifty cents", SpeechText.spellNumbers("$12.50"))
        assertEquals("fifteen percent off", SpeechText.spellNumbers("15% off"))
        assertEquals("Nov twenty fourth, gate one thousand two hundred", SpeechText.spellNumbers("Nov 24th, gate 1,200"))
        assertEquals("two point five km", SpeechText.spellNumbers("2.5km"))
        assertEquals("the first and twenty second", SpeechText.spellNumbers("the 1st and 22nd"))
    }

    @Test fun longDigitRunsAreReadDigitByDigit() {
        assertEquals("one two three four five six seven eight nine zero one two three", SpeechText.spellNumbers("1234567890123"))
    }

    @Test fun findsWordsMissingFromTheDictionary() {
        val known = SpeechText.dictionaryWords(
            sequenceOf("# header", "hi || h a ɪ", "is || ɪ z", "this || ð ɪ s", "it's had || ɪ t s h æ d", "don't || d o ʊ n t")
        )
        assertTrue("it's" in known && "had" in known)
        assertEquals(emptyList<String>(), SpeechText.unknownWords("Hi! Is this... don't", known))
        assertEquals(listOf("huawei"), SpeechText.unknownWords("Is this Huawei?", known))
    }
}
