package com.voicepad.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message a phone without a text-to-speech engine gets. It has to make clear that the
 * *phone* has no engine, since VoicePad talks on such a device with its own voices.
 */
class MissingVoiceMessageTest {

    @Test
    fun `names the bundled voice that declined the line`() {
        val message = missingVoiceMessage("en", "Kitten (English)")
        assertEquals("This phone has no text-to-speech engine, and Kitten (English) doesn't know a word in this line.", message)
    }

    @Test
    fun `names the language when no bundled voice covers it`() {
        val message = missingVoiceMessage("ja", null)
        assertTrue(message.startsWith("This phone has no text-to-speech engine"))
        assertTrue(message.contains("only cover English and Mandarin"))
        assertTrue(message.contains("ja"))
    }

    @Test
    fun `names the words a spoken line left out`() {
        assertEquals(
            "Kitten has no pronunciation for “Portugue”, so it left that word out.",
            leftOutMessage(listOf("Portugue")),
        )
        assertEquals(
            "Kitten has no pronunciation for “Portugue”, “Watsons”, so it left those words out.",
            leftOutMessage(listOf("Portugue", "Watsons")),
        )
    }
}
