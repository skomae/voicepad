package com.voicepad.app.conversation

import com.voicepad.app.audio.InputDeviceNaming
import com.voicepad.app.transcription.EngineChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class ConversationLanguagesTest {
    private val enZh = "en" to "zh"
    private val enEs = "en" to "es"

    @Test fun chineseCharactersAreChineseWhateverTheModelSays() {
        assertEquals("zh", ConversationLanguages.spoken(enZh, "en", emptyList(), "你好，我很好"))
    }

    @Test fun latinTextIsTheLatinSideWhenTheOtherIsNot() {
        assertEquals("en", ConversationLanguages.spoken(enZh, null, emptyList(), "ok"))
        assertEquals("fr", ConversationLanguages.spoken("ja" to "fr", null, emptyList(), "oui"))
    }

    @Test fun modelTagWinsWhenTheScriptCantTell() {
        assertEquals("es", ConversationLanguages.spoken(enEs, "es", listOf("en" to 0.9f), "no"))
    }

    @Test fun textDetectorIsRestrictedToThePair() {
        // "Italian" is likelier, but only English and Spanish are in this conversation.
        val guesses = listOf("it" to 0.6f, "es" to 0.3f, "en" to 0.1f)
        assertEquals("es", ConversationLanguages.spoken(enEs, null, guesses, "gracias amigo"))
    }

    @Test fun modelTagOutsideThePairIsIgnored() {
        assertEquals("en", ConversationLanguages.spoken(enEs, "fr", listOf("en" to 0.7f, "es" to 0.2f), "thank you"))
    }

    @Test fun cantoneseCountsAsChineseWhenOnlyChineseIsInPlay() {
        assertEquals("zh", ConversationLanguages.spoken("en" to "zh", "yue", emptyList(), "ok la"))
    }

    @Test fun kanjiOnlyGoesToJapaneseWhenChineseIsntInPlay() {
        assertEquals("ja", ConversationLanguages.spoken("en" to "ja", null, emptyList(), "東京"))
    }

    @Test fun fallsBackToTheGivenGuessThenTheFirstLanguage() {
        assertEquals("es", ConversationLanguages.spoken(enEs, null, emptyList(), "ok", fallback = "es"))
        assertEquals("en", ConversationLanguages.spoken(enEs, null, emptyList(), "ok", fallback = "de"))
    }

    @Test fun targetIsTheOtherLanguage() {
        assertEquals("zh", ConversationLanguages.target(enZh, "en"))
        assertEquals("en", ConversationLanguages.target(enZh, "zh"))
    }
}

class SpeakerClusteringTest {
    private val random = Random(7)

    /** A voice: a fixed direction plus per-utterance noise, like real embeddings. */
    private fun voice(): FloatArray = FloatArray(192) { random.nextFloat() * 2 - 1 }
    private fun utterance(voice: FloatArray, noise: Float = 0.6f) =
        FloatArray(voice.size) { voice[it] + noise * (random.nextFloat() * 2 - 1) }

    @Test fun separatesTwoVoicesTakingTurns() {
        val clustering = SpeakerClustering()
        val a = voice()
        val b = voice()
        val ids = (0 until 20).map { i -> clustering.assign(utterance(if (i % 2 == 0) a else b)) }
        assertEquals(2, clustering.speakerCount)
        assertTrue(ids.filterIndexed { i, _ -> i % 2 == 0 }.all { it == ids[0] })
        assertTrue(ids.filterIndexed { i, _ -> i % 2 == 1 }.all { it == ids[1] })
        assertNotEquals(ids[0], ids[1])
    }

    @Test fun neverMoreThanTheMaximum() {
        val clustering = SpeakerClustering(maxSpeakers = 3)
        repeat(10) { clustering.assign(voice()) }
        assertEquals(3, clustering.speakerCount)
    }

    @Test fun languageNudgesABorderlineUtterance() {
        val a = FloatArray(4).also { it[0] = 1f }
        val b = FloatArray(4).also { it[1] = 1f }
        val clustering = SpeakerClustering(threshold = 0.45f, languageBonus = 0.06f)
        assertEquals(0, clustering.assign(a, "en"))
        assertEquals(1, clustering.assign(b, "zh"))
        // Equally close to both; the language decides.
        val between = floatArrayOf(1f, 1f, 0f, 0f)
        assertEquals(1, clustering.assign(between, "zh"))
    }
}

class ChannelAttributionTest {
    private fun tone(seconds: Double, amplitude: Float) =
        FloatArray((seconds * 16_000).toInt()) { (amplitude * sin(2 * PI * 220 * it / 16_000)).toFloat() }

    @Test fun keepsSpeechOnlyOnTheMicWhereItIsLoudest() {
        // Person on mic 1 speaks; it leaks into mic 2 about 15 dB quieter.
        val levels = listOf(tone(1.0, 0.3f), tone(1.0, 0.05f)).map { ChannelAttribution.frameLevels(it) }
        assertTrue(ChannelAttribution.owns(levels, 0))
        assertFalse(ChannelAttribution.owns(levels, 1))
    }

    @Test fun identicalChannelsKeepEachUtteranceOnce() {
        val same = ChannelAttribution.frameLevels(tone(1.0, 0.2f))
        val levels = listOf(same, same.copyOf())
        assertTrue(ChannelAttribution.owns(levels, 0))
        assertFalse(ChannelAttribution.owns(levels, 1))
        assertEquals(0f, ChannelAttribution.separationDb(levels)!!, 0.01f)
    }

    @Test fun separationMeasuresHowFarApartTheMicsAre() {
        val levels = listOf(tone(1.0, 0.3f), tone(1.0, 0.03f)).map { ChannelAttribution.frameLevels(it) }
        assertEquals(20f, ChannelAttribution.separationDb(levels)!!, 0.5f)
    }

    @Test fun silenceIsNobody() {
        val levels = listOf(FloatArray(20) { -90f }, FloatArray(20) { -90f })
        assertEquals(null, ChannelAttribution.separationDb(levels))
        assertTrue(ChannelAttribution.owns(levels, 0))
    }
}

class TurnsTest {
    private fun turn(speaker: SpeakerRef?, language: String, end: Int, text: String = "hello", session: Int = 1) =
        Turn(1, session, speaker, language, text, target = "zh", start = 0, end = end)

    private val one = SpeakerRef(SpeakerSource.VOICE, 0)
    private val two = SpeakerRef(SpeakerSource.VOICE, 1)

    @Test fun sameSpeakerAfterAShortPauseContinuesTheTurn() {
        val previous = turn(one, "en", end = 16_000)
        assertTrue(Turns.continues(previous, 1, one, "en", start = 32_000, sampleRate = 16_000))
        assertFalse(Turns.continues(previous, 1, two, "en", start = 32_000, sampleRate = 16_000))
        assertFalse(Turns.continues(previous, 1, one, "zh", start = 32_000, sampleRate = 16_000))
        assertFalse(Turns.continues(previous, 1, one, "en", start = 16_000 * 4, sampleRate = 16_000))
        assertFalse(Turns.continues(previous, 2, one, "en", start = 32_000, sampleRate = 16_000))
        assertFalse(Turns.continues(turn(one, "en", 16_000, "x".repeat(300)), 1, one, "en", 20_000, 16_000))
    }

    @Test fun joinsChineseWithoutSpaces() {
        assertEquals("你好。我很好", Turns.join("你好。", "我很好"))
        assertEquals("Hello there", Turns.join("Hello", "there"))
        assertEquals("there", Turns.join("", "there"))
    }

    @Test fun transcriptListsTurnsWithTranslations() {
        val turns = listOf(
            turn(one, "en", 10, "Hello").copy(translation = "你好"),
            turn(two, "zh", 20, "谢谢").copy(target = "en", translation = null),
        )
        val text = Turns.transcript(turns, ConversationController::speakerLabel)
        assertEquals("Speaker 1: Hello\n→ 你好\n\nSpeaker 2: 谢谢", text)
    }

    @Test fun eachPaneShowsItsOwnLanguage() {
        val t = turn(one, "en", 10, "Hello").copy(translation = "你好")
        assertEquals("Hello", t.textIn("en"))
        assertEquals("你好", t.textIn("zh"))
    }
}

class ConversationEngineTest {
    @Test fun recommendsAModelThatUnderstandsBothLanguages() {
        assertEquals(EngineChoice.PARAKEET_V3, EngineChoice.recommendedFor("en", "es"))
        assertEquals(EngineChoice.AUTO_ASIAN, EngineChoice.recommendedFor("en", "zh"))
        assertEquals(EngineChoice.SENSE_VOICE, EngineChoice.recommendedFor("zh", "ja"))
        assertEquals(EngineChoice.WHISPER_TURBO, EngineChoice.recommendedFor("en", "ar"))
        assertEquals(EngineChoice.WHISPER_TURBO, EngineChoice.recommendedFor("zh", "es"))
    }

    @Test fun prefersAModelAlreadyOnThePhone() {
        assertEquals(EngineChoice.WHISPER_SMALL, EngineChoice.recommendedFor("en", "ar", setOf(EngineChoice.WHISPER_SMALL)))
        assertEquals(EngineChoice.SENSE_VOICE, EngineChoice.recommendedFor("en", "zh", setOf(EngineChoice.SENSE_VOICE)))
    }

    @Test fun micChannelsFromWhatAndroidReports() {
        val builtIn = 15 // AudioDeviceInfo.TYPE_BUILTIN_MIC
        val bluetooth = 7 // AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        assertEquals(2, InputDeviceNaming.maxChannels(builtIn, intArrayOf(1, 2), intArrayOf(), intArrayOf()))
        assertEquals(4, InputDeviceNaming.maxChannels(11, intArrayOf(), intArrayOf(0b1111), intArrayOf()))
        assertEquals(2, InputDeviceNaming.maxChannels(builtIn, intArrayOf(), intArrayOf(), intArrayOf()))
        assertEquals(1, InputDeviceNaming.maxChannels(bluetooth, intArrayOf(2), intArrayOf(), intArrayOf()))
    }
}

class LayoutCycleTest {
    @Test fun cyclesThroughEveryLayoutAndWrapsAround() {
        var layout = ConversationLayout.FACE_TO_FACE
        val seen = mutableListOf(layout)
        repeat(ConversationLayout.entries.size - 1) { layout = layout.next(outerScreenSupported = true); seen += layout }
        assertEquals(ConversationLayout.entries.toList(), seen)
        assertEquals(ConversationLayout.FACE_TO_FACE, layout.next(outerScreenSupported = true))
    }

    @Test fun skipsTheOuterScreenWhereThereIsNone() {
        assertEquals(ConversationLayout.FACE_TO_FACE, ConversationLayout.TRANSCRIPT.next(outerScreenSupported = false))
        // Stuck on the outer screen without one (e.g. a model change): moves on to the first layout.
        assertEquals(ConversationLayout.FACE_TO_FACE, ConversationLayout.OUTER_SCREEN.next(outerScreenSupported = false))
    }
}
