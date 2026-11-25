package com.voicepad.app.glasses.basis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Firmware revisions advertise the G1's arms under different prefixes; both
 * have to pair, or the glasses never appear.
 */
class G1NameTest {

    @Test
    fun `the name the official app documents`() {
        val name = G1Name.parse("G1_45_L_92333")
        assertEquals("45", name?.channel)
        assertEquals(G1Name.Side.LEFT, name?.side)
        assertEquals("92333", name?.serial)
    }

    @Test
    fun `the prefix some firmware uses`() {
        val name = G1Name.parse("Even G1_07_R_S110LAAL103842")
        assertEquals("07", name?.channel)
        assertEquals(G1Name.Side.RIGHT, name?.side)
        assertEquals("S110LAAL103842", name?.serial)
    }

    @Test
    fun `case does not matter`() {
        assertEquals(G1Name.Side.LEFT, G1Name.parse("g1_45_l")?.side)
        assertEquals(G1Name.Side.RIGHT, G1Name.parse("EVEN G1_45_R")?.side)
    }

    @Test
    fun `both arms of a pair share a key`() {
        val left = G1Name.parse("G1_45_L_92333")
        val right = G1Name.parse("Even G1_45_R_92334")
        assertEquals(left?.pairKey, right?.pairKey)
    }

    @Test
    fun `other bluetooth devices are ignored`() {
        assertNull(G1Name.parse("Jabra Elite 85t"))
        assertNull(G1Name.parse(""))
        assertNull(G1Name.parse(null))
        assertNull(G1Name.parse("G735 Headset")) // starts with a G, not with G1
    }

    @Test
    fun `a name without a side is reported but does not pair`() {
        val name = G1Name.parse("G1")
        assertEquals(null, name?.side)
        assertEquals("G1", name?.pairKey)
    }
}
