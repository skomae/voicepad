package com.voicepad.app.data

import com.voicepad.app.data.TranslationJoin.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSimilarityTest {
    // From a real note: English speech labelled "Arabic" and "translated" back.
    private val spoken = "I'm trying to Trying to go with the flow and process the two together. Hey baby. Yeah. No, " +
        "I understand. None of that bullshit like oh can we stop talking about Yeah, yeah. Okay. He was really " +
        "happy that. Hey if it makes you feel any better if I got No. It doesn't make me feel any better. Yeah."
    private val returned = "I'm trying to Trying to go with the flow and process the two together. Hey baby. Yeah. No, " +
        "I understand. None of that bullshit like oh can we stop talking about Yeah, yeah. Okay. He was really " +
        "happy that. Hey if it makes you feel any better if I got It doesn't make me feel any better. Yeah."

    @Test
    fun `a translation that repeats the original is recognised`() {
        assertTrue(TextSimilarity.nearlySame(spoken, returned))
        assertTrue(TextSimilarity.nearlySame("Okay.", "okay"))
    }

    @Test
    fun `a real translation is not`() {
        assertFalse(TextSimilarity.nearlySame("我们明天去银行", "We are going to the bank tomorrow"))
        assertFalse(TextSimilarity.nearlySame("Ne vous demandez pas ce que votre pays peut faire pour vous", "Ask not what your country can do for you"))
        assertFalse(TextSimilarity.nearlySame("No preguntes qué puede hacer tu país por ti", "Don't ask what your country can do for you"))
    }

    @Test
    fun `English with one misdetected short utterance needs no translation`() {
        val t = TranslationJoin.combine(
            listOf(
                Part("I'm trying to go with the flow", null, "I'm trying to go with the flow", null, true),
                Part("Yeah.", "ar", "Yeah.", null, true),           // misdetected, came back unchanged
                Part("It doesn't make me feel any better", null, "It doesn't make me feel any better", null, true),
            ),
            "r1",
        )!!
        assertEquals(TranslationStatus.NOT_NEEDED, t.status)
    }

    @Test
    fun `mostly English with real Mandarin still shows the translation, labelled Mandarin`() {
        val t = TranslationJoin.combine(
            listOf(
                Part("so tomorrow I'm going to", null, "so tomorrow I'm going to", null, true),
                Part("银行开会", "zh", "a meeting at the bank", "yín háng kāi huì", true),
                Part("Yeah.", "ar", "Yeah.", null, true),
            ),
            "r1",
        )!!
        assertEquals(TranslationStatus.DONE, t.status)
        assertEquals("zh", t.language)
    }
}
