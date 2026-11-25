package com.voicepad.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transcripts the model actually produced for silent audio on the Retroid, plus real speech. */
class NoSpeechTextTest {

    @Test
    fun `the model's answers to silence are not words`() {
        // Measured: these are what came back for audio the detector heard nothing in.
        assertFalse(NoSpeechText.isSubstantive("."))
        assertFalse(NoSpeechText.isSubstantive("。 ."))
        assertFalse(NoSpeechText.isSubstantive("。 。 。"))
        assertFalse(NoSpeechText.isSubstantive("Yeah."))
        assertFalse(NoSpeechText.isSubstantive("Thank you."))
        assertFalse(NoSpeechText.isSubstantive(""))
        assertFalse(NoSpeechText.isSubstantive("  "))
    }

    @Test
    fun `short real words still count`() {
        assertTrue(NoSpeechText.isSubstantive("你好。"))
        assertTrue(NoSpeechText.isSubstantive("这是一个测试。"))
        assertTrue(NoSpeechText.isSubstantive("This is a test."))
        assertTrue(NoSpeechText.isSubstantive("I wanna know."))
    }

    @Test
    fun `three letters is the floor`() {
        assertFalse(NoSpeechText.isSubstantive("ok"))
        assertTrue(NoSpeechText.isSubstantive("okay then"))  // a filler plus a real word
    }
}
