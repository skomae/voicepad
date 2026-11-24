package com.voicepad.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSplitTest {
    private val r = 100 // samples per second, to keep numbers readable
    private fun s(seconds: Double) = (seconds * r).toInt()

    @Test
    fun `off never splits`() {
        assertFalse(AutoSplit.OFF.shouldSplit(0, s(3600.0), false, s(1.0), r))
    }

    @Test
    fun `silence mode splits once the pause is long enough`() {
        val m = AutoSplit.SILENCE_10S
        assertFalse(m.shouldSplit(0, s(19.0), false, s(10.0), r))   // 9 s quiet
        assertTrue(m.shouldSplit(0, s(20.0), false, s(10.0), r))    // 10 s quiet
        assertFalse(m.shouldSplit(0, s(20.0), true, s(10.0), r))    // talking again
    }

    @Test
    fun `nothing said yet means no split, so silence alone never creates empty notes`() {
        assertFalse(AutoSplit.SILENCE_10S.shouldSplit(0, s(300.0), false, null, r))
        assertFalse(AutoSplit.EVERY_5_MIN.shouldSplit(0, s(400.0), false, null, r))
    }

    @Test
    fun `interval mode waits for a pause after the interval`() {
        val m = AutoSplit.EVERY_5_MIN
        assertFalse(m.shouldSplit(0, s(299.0), false, s(290.0), r))  // before 5 min
        assertFalse(m.shouldSplit(0, s(301.0), true, s(301.0), r))   // still talking
        assertFalse(m.shouldSplit(0, s(301.0), false, s(300.5), r))  // pause too short
        assertTrue(m.shouldSplit(0, s(301.0), false, s(300.0), r))   // 1 s pause
    }

    @Test
    fun `interval mode splits anyway a minute past the interval`() {
        val m = AutoSplit.EVERY_5_MIN
        assertFalse(m.shouldSplit(0, s(359.0), true, s(359.0), r))
        assertTrue(m.shouldSplit(0, s(360.0), true, s(360.0), r))
    }

    @Test
    fun `interval counts from the start of the current note`() {
        val m = AutoSplit.EVERY_15_MIN
        val noteStart = s(1000.0)
        assertFalse(m.shouldSplit(noteStart, s(1000.0 + 899), false, s(1000.0 + 890), r))
        assertTrue(m.shouldSplit(noteStart, s(1000.0 + 901), false, s(1000.0 + 900), r))
    }
}
