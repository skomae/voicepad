package com.voicepad.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnfinishedTest {
    @Test fun finishedSentencesAreFinished() {
        assertFalse(Turns.looksUnfinished("Maybe meet near the station around six?"))
        assertFalse(Turns.looksUnfinished("Seven works."))
        assertFalse(Turns.looksUnfinished("七点可以吗？"))
        assertFalse(Turns.looksUnfinished("He said \"no.\""))
    }

    @Test fun aRunOfWordsWithoutAnEndingIsUnfinished() {
        assertTrue(Turns.looksUnfinished("So I was thinking we could"))
        assertTrue(Turns.looksUnfinished("Six is a bit early, I have to first,"))
        assertTrue(Turns.looksUnfinished("六点有点早，我得先"))
    }

    @Test fun trailingOffIsUnfinished() {
        assertTrue(Turns.looksUnfinished("Do you know that noodle place..."))
        assertTrue(Turns.looksUnfinished("I went there and."))
        assertTrue(Turns.looksUnfinished("我想去那家面馆，然后。"))
    }

    @Test fun shortRepliesAreCompleteEvenWithoutPunctuation() {
        assertFalse(Turns.looksUnfinished("ok"))
        assertFalse(Turns.looksUnfinished("yes please"))
        assertFalse(Turns.looksUnfinished("好的"))
        assertFalse(Turns.looksUnfinished("So."))
        assertFalse(Turns.looksUnfinished(""))
    }
}

class HeldTurnTest {
    private val sr = 16_000
    private fun turn(end: Int, held: Boolean) = Turn(
        id = 1, session = 1, speaker = null, language = "en", text = "So I was thinking we could",
        target = "zh", start = 0, end = end, held = held,
    )

    @Test fun aHeldTurnCanBePickedUpAfterALongPause() {
        val previous = turn(end = sr, held = true)
        // 3.4 s later: too long for an ordinary turn, fine within a 5 s thinking pause.
        val start = sr + (3.4 * sr).toInt()
        assertFalse(Turns.continues(previous, 1, null, "en", start, sr))
        assertTrue(Turns.continues(previous, 1, null, "en", start, sr, maxGapSeconds = 5.0))
        assertFalse(Turns.continues(previous, 1, null, "en", sr + 6 * sr, sr, maxGapSeconds = 5.0))
    }
}

class StageTest {
    private fun turn(id: Long, language: String, text: String, translation: String? = null, pending: Boolean = false, held: Boolean = false) =
        Turn(
            id = id, session = 1, speaker = null, language = language, text = text,
            target = if (language == "en") "zh" else "en", translation = translation, pending = pending, held = held, start = 0, end = 0,
        )

    @Test fun nothingSaidIsIdle() {
        val stage = Stage.of(emptyList(), null, working = false)
        assertNull(stage.turn)
        assertEquals(Stage.Phase.IDLE, stage.phase)
    }

    @Test fun liveSpeechTakesTheStageAndEarlierTurnsRecede() {
        val done = turn(1, "zh", "你好。", "Hello.")
        val live = turn(-1, "en", "Hi, how")
        val stage = Stage.of(listOf(done), live, working = false)
        assertEquals(Stage.Phase.SPEAKING, stage.phase)
        assertEquals("Hi, how", stage.turn?.text)
        assertEquals(listOf(done), stage.history)
    }

    @Test fun speechResumingInsideAHeldTurnShowsTheWholeThought() {
        val held = turn(2, "en", "So I was thinking we could", "我在想我们可以", pending = true, held = true)
        val live = turn(-1, "en", "maybe meet near", "也许在附近见面")
        val stage = Stage.of(listOf(held), live, working = false)
        assertEquals(Stage.Phase.SPEAKING, stage.phase)
        assertEquals(2L, stage.turn?.id)
        assertEquals("So I was thinking we could maybe meet near", stage.turn?.text)
        assertEquals("我在想我们可以也许在附近见面", stage.turn?.translation)
        assertTrue(stage.history.isEmpty())
    }

    @Test fun aHeldTurnInAnotherLanguageIsNotJoinedToNewSpeech() {
        val held = turn(2, "en", "So I was thinking we could", pending = true, held = true)
        val stage = Stage.of(listOf(held), turn(-1, "zh", "好的"), working = false)
        assertEquals("好的", stage.turn?.text)
        assertEquals(listOf(held), stage.history)
    }

    @Test fun phasesFollowTheNewestTurn() {
        val earlier = turn(1, "zh", "你好。", "Hello.")
        fun phase(last: Turn, working: Boolean = false) = Stage.of(listOf(earlier, last), null, working).phase
        assertEquals(Stage.Phase.HOLDING, phase(turn(2, "en", "So I was thinking we could", pending = true, held = true)))
        assertEquals(Stage.Phase.WORKING, phase(turn(2, "en", "Seven works.", pending = true)))
        assertEquals(Stage.Phase.WORKING, phase(turn(2, "en", "Seven works.", "七点可以。"), working = true))
        assertEquals(Stage.Phase.LANDED, phase(turn(2, "en", "Seven works.", "七点可以。")))
    }

    @Test fun whileAnUtteranceIsBeingTranscribedTheStageIsWorking() {
        val stage = Stage.of(emptyList(), turn(-1, "en", "Seven works"), working = true)
        assertEquals(Stage.Phase.WORKING, stage.phase)
    }
}

class GlyphsTest {
    @Test fun standInsUseTheReadersScript() {
        assertTrue(Glyphs.pool("zh").all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
        assertTrue(Glyphs.pool("es").all { it in 'a'..'z' })
        assertTrue(Glyphs.pool("ru").all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC })
    }

    @Test fun lengthEstimateFollowsHowDenseTheScriptsAre() {
        val english = "So I was thinking we could maybe meet near the station around six?"
        assertTrue(Glyphs.estimateLength(english, "en", "zh") < english.length / 2)
        assertTrue(Glyphs.estimateLength("六点左右在车站附近见面？", "zh", "en") > 24)
        assertEquals(english.length, Glyphs.estimateLength(english, "en", "es"))
    }
}
