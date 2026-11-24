package com.voicepad.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoEngineTest {
    private class FakeEngine(
        private val text: String,
        private val language: String?,
    ) : SpeechEngine {
        override val grouping = ChunkPlanner.Grouping.UTTERANCES
        var calls = 0
            private set

        override fun transcribe(samples: FloatArray, languageHint: String?, preview: Boolean): Transcript {
            calls++
            return Transcript(text, language, "fake")
        }

        override fun release() {}
    }

    private class Fakes(val sense: FakeEngine, val parakeet: FakeEngine, val auto: AutoEngine)

    private fun make(senseText: String, senseLang: String?): Fakes {
        val sense = FakeEngine(senseText, senseLang)
        val parakeet = FakeEngine("english from parakeet", "en")
        return Fakes(sense, parakeet, AutoEngine(parakeet, sense))
    }

    @Test
    fun `east asian stays on sense voice`() {
        val f = make("你好", "zh")
        val t = f.auto.transcribe(floatArrayOf(1f), null, false)
        assertEquals("你好", t.text)
        assertEquals(0, f.parakeet.calls)
    }

    @Test
    fun `pure latin english routes to parakeet`() {
        val f = make("hello world", "en")
        val t = f.auto.transcribe(floatArrayOf(1f), null, false)
        assertEquals("english from parakeet", t.text)
        assertEquals("en", t.language)
        assertEquals(1, f.parakeet.calls)
    }

    @Test
    fun `code switched mandarin tagged english stays on sense voice`() {
        val f = make("今天有一个 meeting", "en")
        val t = f.auto.transcribe(floatArrayOf(1f), null, false)
        assertEquals("今天有一个 meeting", t.text)
        assertEquals(0, f.parakeet.calls)
    }

    @Test
    fun `kana tagged english stays on sense voice`() {
        val f = make("こんにちは", "en")
        val t = f.auto.transcribe(floatArrayOf(1f), null, false)
        assertEquals("こんにちは", t.text)
        assertEquals(0, f.parakeet.calls)
    }

    @Test
    fun `preview uses sense voice only`() {
        val f = make("hello world", "en")
        val t = f.auto.transcribe(floatArrayOf(1f), null, true)
        assertEquals("hello world", t.text)
        assertEquals(0, f.parakeet.calls)
    }

    @Test
    fun `latched east asian language beats a later english tag`() {
        val f = make("hello", "en")
        val t = f.auto.transcribe(floatArrayOf(1f), "zh", false)
        assertEquals("hello", t.text)
        assertEquals(0, f.parakeet.calls)
    }

    @Test
    fun `latched english is still protected by the script check`() {
        val f = make("今天", "zh")
        val t = f.auto.transcribe(floatArrayOf(1f), "en", false)
        assertEquals("今天", t.text)
        assertEquals(0, f.parakeet.calls)
    }
}
