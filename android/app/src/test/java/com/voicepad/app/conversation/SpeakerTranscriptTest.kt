package com.voicepad.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerTranscriptTest {
    private val alice = floatArrayOf(1f, 0f, 0f)
    private val aliceAgain = floatArrayOf(0.9f, 0.1f, 0f)
    private val bob = floatArrayOf(0f, 1f, 0f)

    @Test
    fun `one voice reads like a plain note`() {
        val t = SpeakerTranscript()
        t.add("Hello there.", alice)
        t.add("How are you?", aliceAgain)
        assertEquals("Hello there. How are you?", t.text)
        assertEquals(1, t.speakerCount)
    }

    @Test
    fun `a second voice starts labelled paragraphs`() {
        val t = SpeakerTranscript()
        t.add("Hello there.", alice)
        t.add("How are you?", aliceAgain)
        t.add("Fine, thanks.", bob)
        t.add("Good to hear.", alice)
        assertEquals(
            "Speaker 1: Hello there. How are you?\n\nSpeaker 2: Fine, thanks.\n\nSpeaker 1: Good to hear.",
            t.text,
        )
    }

    @Test
    fun `a piece without a fingerprint stays with the speaker before it`() {
        val t = SpeakerTranscript()
        t.add("Hi.", null) // before any voice: belongs to the first one heard
        t.add("Hello there.", alice)
        t.add("Yes.", bob)
        t.add("Right.", null)
        assertEquals("Speaker 1: Hi. Hello there.\n\nSpeaker 2: Yes. Right.", t.text)
    }

    @Test
    fun `blank text and no fingerprints change nothing`() {
        val t = SpeakerTranscript()
        t.add("  ", alice)
        t.add("Just me.", null)
        t.add("Still me.", null)
        assertEquals("Just me. Still me.", t.text)
        assertEquals(0, t.speakerCount)
    }
}
