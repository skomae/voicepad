package com.voicepad.app.data

import com.voicepad.app.transcription.AudioLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioLevelTest {
    private fun tone(amplitude: Float, n: Int = 16_000) = FloatArray(n) { (amplitude * sin(it * 0.05)).toFloat() }
    private fun rms(a: FloatArray) = sqrt(a.sumOf { (it * it).toDouble() } / a.size).toFloat()

    @Test
    fun `faint distant speech is brought up to a steady level`() {
        val faint = tone(0.005f) // about -46 dBFS
        val out = AudioLevel.normalize(faint)
        assertEquals(0.08f, rms(out), 0.01f)
    }

    @Test
    fun `gain is capped so near-silence is not blown up into noise`() {
        val hiss = tone(0.0001f)
        assertEquals(rms(hiss) * 30f, rms(AudioLevel.normalize(hiss)), 1e-5f)
    }

    @Test
    fun `loud audio is not clipped`() {
        val loud = tone(0.9f)
        val out = AudioLevel.normalize(loud)
        assertTrue(out.maxOf { abs(it) } <= 0.951f)
    }

    @Test
    fun `audio already at the target level is passed through untouched`() {
        val ok = tone(0.08f * sqrt(2f))
        assertSame(ok, AudioLevel.normalize(ok))
    }
}
