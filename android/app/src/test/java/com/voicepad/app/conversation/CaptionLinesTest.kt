package com.voicepad.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionLinesTest {

    @Test
    fun `plain text is one unlabelled line`() {
        assertEquals(listOf(CaptionLines.Line(null, "Hello there. How are you?")), CaptionLines.parse("Hello there. How are you?"))
    }

    @Test
    fun `speaker paragraphs keep who said them`() {
        val lines = CaptionLines.parse("Speaker 1: Hello there.\n\nSpeaker 2: Fine, thanks.\n\nSpeaker 1: Good.")
        assertEquals(
            listOf(
                CaptionLines.Line(0, "Hello there."),
                CaptionLines.Line(1, "Fine, thanks."),
                CaptionLines.Line(0, "Good."),
            ),
            lines,
        )
        assertEquals("Speaker 2", lines[1].label)
    }

    @Test
    fun `blank and empty paragraphs are dropped`() {
        assertEquals(emptyList<CaptionLines.Line>(), CaptionLines.parse("  \n\n Speaker 1:  \n\n"))
    }

    @Test
    fun `the last three words are still arriving`() {
        val text = "one two three four five"
        assertEquals("one two", text.take(CaptionLines.settledLength(text, dense = false)))
        assertEquals(0, CaptionLines.settledLength("just two", dense = false))
    }

    @Test
    fun `dense scripts count characters`() {
        val text = "你今天几点吃完饭"
        assertEquals("你今天", text.take(CaptionLines.settledLength(text, dense = true)))
    }
}
