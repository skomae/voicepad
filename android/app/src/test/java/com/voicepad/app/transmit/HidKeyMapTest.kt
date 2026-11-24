package com.voicepad.app.transmit

import org.junit.Assert.assertEquals
import org.junit.Test

class HidKeyMapTest {

    @Test
    fun lowercaseLetters() {
        assertEquals(listOf(HidKeyMap.Stroke(0, 0x0B)), HidKeyMap.strokes("h"))
    }

    @Test
    fun uppercaseNeedsShift() {
        assertEquals(HidKeyMap.Stroke(HidKeyMap.LEFT_SHIFT, 0x04), HidKeyMap.strokes("A").single())
    }

    @Test
    fun digits() {
        assertEquals(
            listOf(HidKeyMap.Stroke(0, 0x1E), HidKeyMap.Stroke(0, 0x27)),
            HidKeyMap.strokes("10"),
        )
    }

    @Test
    fun shiftedPunctuation() {
        assertEquals(HidKeyMap.Stroke(HidKeyMap.LEFT_SHIFT, 0x1E), HidKeyMap.strokes("!").single())
        assertEquals(HidKeyMap.Stroke(HidKeyMap.LEFT_SHIFT, 0x38), HidKeyMap.strokes("?").single())
    }

    @Test
    fun spaceAndEnter() {
        assertEquals(
            listOf(HidKeyMap.Stroke(0, HidKeyMap.KEY_SPACE), HidKeyMap.Stroke(0, HidKeyMap.KEY_ENTER)),
            HidKeyMap.strokes(" \n"),
        )
    }

    @Test
    fun accentsTransliterated() {
        assertEquals(HidKeyMap.Stroke(0, 0x08), HidKeyMap.strokes("é").single()) // é -> e
    }

    @Test
    fun smartQuotesAndDashesTypeAsAscii() {
        assertEquals(HidKeyMap.strokes("don't \"x\" - y"), HidKeyMap.strokes("don’t “x” — y"))
    }

    @Test
    fun multiCharacterFolds() {
        assertEquals(HidKeyMap.strokes("..."), HidKeyMap.strokes("…")) // …
        assertEquals(HidKeyMap.strokes("1/2"), HidKeyMap.strokes("½")) // ½
        assertEquals(HidKeyMap.strokes("strasse"), HidKeyMap.strokes("straße")) // ß
    }

    @Test
    fun crlfTypesOneEnter() {
        assertEquals(listOf(HidKeyMap.Stroke(0, HidKeyMap.KEY_ENTER)), HidKeyMap.strokes("\r\n"))
    }

    @Test
    fun unsupportedSkipped() {
        assertEquals(emptyList<HidKeyMap.Stroke>(), HidKeyMap.strokes("银行"))
    }
}
