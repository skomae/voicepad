package com.voicepad.app.data

import com.voicepad.app.transcription.ChunkPlanner
import com.voicepad.app.transcription.ChunkPlanner.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {
    private val sec = 16_000
    private fun s(from: Double, to: Double) = Span((from * sec).toInt(), (to * sec).toInt())

    private fun check(chunks: List<List<Span>>, speech: List<Span>, max: Int) {
        // Every chunk fits, chunks don't overlap and come in order.
        chunks.forEach { assertTrue("chunk too long: ${ChunkPlanner.length(it)}", ChunkPlanner.length(it) <= max) }
        val flat = chunks.flatten()
        flat.zipWithNext().forEach { (a, b) -> assertTrue("overlap/out of order $a $b", a.end <= b.start) }
        // All speech is kept (possibly across consecutive pieces of a hard cut).
        speech.forEach { sp ->
            var covered = sp.start
            for (piece in flat) if (piece.start <= covered && piece.end > covered) covered = piece.end
            assertTrue("lost speech $sp (covered to $covered)", covered >= sp.end)
        }
    }

    @Test
    fun `no speech means nothing to transcribe`() {
        assertTrue(ChunkPlanner.plan(emptyList(), 60 * sec, 25 * sec, sec / 4).isEmpty())
    }

    @Test
    fun `silence is trimmed but a little context is kept around speech`() {
        // 2 s of speech in the middle of a minute of silence.
        val chunks = ChunkPlanner.plan(listOf(s(30.0, 32.0)), 60 * sec, 25 * sec, sec / 4)
        assertEquals(listOf(listOf(s(29.75, 32.25))), chunks)
    }

    @Test
    fun `long pauses inside a short chunk are dropped`() {
        val speech = listOf(s(1.0, 4.0), s(14.0, 17.0)) // 10 s pause between
        val chunks = ChunkPlanner.plan(speech, 20 * sec, 25 * sec, sec / 4)
        assertEquals(1, chunks.size)
        assertEquals(7 * sec, ChunkPlanner.length(chunks[0])) // 6 s speech + 4 × 0.25 s padding
    }

    @Test
    fun `too much speech is split at the longest pause first`() {
        // Three 10 s utterances; the pause after the first is 0.6 s, after the second 4 s.
        val speech = listOf(s(0.0, 10.0), s(10.6, 20.6), s(24.6, 34.6))
        val chunks = ChunkPlanner.plan(speech, 40 * sec, 25 * sec, sec / 4)
        check(chunks, speech, 25 * sec)
        assertEquals(2, chunks.size)
        // The cut is in the 4 s pause, not the 0.6 s one.
        assertEquals(s(10.35, 20.85).end, chunks[0].last().end)
        assertEquals(s(24.35, 34.85).start, chunks[1].first().start)
    }

    @Test
    fun `an hour of speech with pauses comes out in chunks that all fit`() {
        val speech = mutableListOf<Span>()
        var t = 2.0
        var i = 0
        while (t < 3600) {
            val talk = 3.0 + (i * 7 % 11)       // 3–13 s utterances
            val pause = 0.4 + (i * 5 % 9) * 0.3 // 0.4–2.8 s pauses
            speech += s(t, t + talk)
            t += talk + pause
            i++
        }
        val chunks = ChunkPlanner.plan(speech, 3610 * sec, 25 * sec, sec / 4)
        check(chunks, speech, 25 * sec)
        // Trimming removes most of the silence.
        val kept = chunks.sumOf { ChunkPlanner.length(it) }
        assertTrue(kept < speech.last().end - speech.first().start)
    }

    @Test
    fun `continuous speech with no pause is cut at the quietest point`() {
        val speech = listOf(s(0.0, 60.0))
        val cuts = mutableListOf<Pair<Int, Int>>()
        val chunks = ChunkPlanner.plan(speech, 60 * sec, 25 * sec, 0) { from, to -> cuts += from to to; to - sec }
        assertEquals(chunks, ChunkPlanner.plan(speech, 60 * sec, 25 * sec, 0, ChunkPlanner.Grouping.UTTERANCES) { _, to -> to - sec })
        check(chunks, speech, 25 * sec)
        assertEquals(3, chunks.size)
        assertEquals(24 * sec, chunks[0].single().end)
        // The cut window is the last 40 % before the limit.
        assertEquals(15 * sec to 25 * sec, cuts.first())
    }

    @Test
    fun `padding never runs past the audio`() {
        val chunks = ChunkPlanner.plan(listOf(s(0.0, 1.0), s(9.9, 10.0)), 10 * sec, 25 * sec, sec / 2)
        assertEquals(0, chunks.first().first().start)
        assertEquals(10 * sec, chunks.last().last().end)
    }

    @Test
    fun `utterances a short pause apart stay separate`() {
        // English then French with only 0.3 s between them: padding must not join them.
        val speech = listOf(s(20.0, 23.8), s(24.1, 29.1))
        val chunks = ChunkPlanner.plan(speech, 40 * sec, 25 * sec, sec / 4, ChunkPlanner.Grouping.UTTERANCES)
        assertEquals(2, chunks.size)
        assertEquals(s(19.75, 23.95), chunks[0].single())
        assertEquals(s(23.95, 29.35), chunks[1].single())
    }

    @Test
    fun `utterance grouping gives one chunk per utterance and splits only very long ones`() {
        val speech = listOf(s(1.0, 3.0), s(5.0, 40.0), s(41.0, 42.0))
        val chunks = ChunkPlanner.plan(speech, 50 * sec, 25 * sec, sec / 4, ChunkPlanner.Grouping.UTTERANCES)
        check(chunks, speech, 25 * sec)
        assertEquals(4, chunks.size) // the 35 s utterance is cut once
        chunks.forEach { assertEquals(1, it.size) }
    }
}
