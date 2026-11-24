package com.voicepad.app.glasses

import com.voicepad.app.glasses.basis.G1Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class G1ProtocolTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun shortPageIsOnePacketWithTheDemoAppsHeader() {
        val packets = G1Protocol.text("hi", seq = 7)
        assertEquals(1, packets.size)
        assertArrayEquals(
            bytes(0x4E, 7, 1, 0, 0x71, 0, 0, 1, 1, 'h'.code, 'i'.code),
            packets[0],
        )
    }

    @Test
    fun fullPageIsSplitIntoPacketsOf191TextBytes() {
        // Five full 40-column lines: 200 characters plus four newlines.
        val page = List(GlassesScreen.LINES) { "x".repeat(GlassesScreen.COLUMNS) }.joinToString("\n")
        val packets = G1Protocol.text(page, seq = 3)
        assertEquals(2, packets.size)
        packets.forEachIndexed { index, packet ->
            assertEquals(0x4E.toByte(), packet[0])
            assertEquals(3.toByte(), packet[1])
            assertEquals(2.toByte(), packet[2])
            assertEquals(index.toByte(), packet[3])
            assertTrue("packet $index is ${packet.size} bytes", packet.size <= 9 + G1Protocol.TEXT_CHUNK)
        }
        val rejoined = packets.flatMap { it.drop(9) }.toByteArray().decodeToString()
        assertEquals(page, rejoined)
    }

    @Test
    fun widePageInUtf8StillFitsEachPacket() {
        // 20 Han characters fill a line: 60 UTF-8 bytes each, 300 for a page.
        val page = List(GlassesScreen.LINES) { "你".repeat(20) }.joinToString("\n")
        val packets = G1Protocol.text(page, seq = 0)
        assertEquals(2, packets.size)
        assertTrue(packets.all { it.size <= 9 + G1Protocol.TEXT_CHUNK })
        assertEquals(page, packets.flatMap { it.drop(9) }.toByteArray().decodeToString())
    }

    @Test
    fun sequenceWrapsAtOneByte() {
        assertEquals(0x01.toByte(), G1Protocol.text("a", seq = 257)[0][1])
    }

    @Test
    fun emptyPageIsStillOnePacket() {
        assertEquals(1, G1Protocol.text("", seq = 0).size)
    }

    @Test
    fun heartbeatMatchesTheOfficialApp() {
        assertArrayEquals(bytes(0x25, 6, 0, 5, 0x04, 5), G1Protocol.heartbeat(5))
        assertTrue(G1Protocol.heartbeatAnswered(bytes(0x25, 6, 0, 5, 0x04, 5)))
        assertFalse(G1Protocol.heartbeatAnswered(bytes(0x25, 6)))
    }

    @Test
    fun acknowledgementsReadTheStatusByte() {
        assertTrue(G1Protocol.accepted(bytes(0x4E, 0xC9)))
        assertTrue(G1Protocol.accepted(bytes(0x4E, 0xCB)))
        assertFalse(G1Protocol.accepted(bytes(0x4E, 0xCA)))
        assertFalse(G1Protocol.accepted(bytes(0x4E)))
        assertFalse(G1Protocol.accepted(null))
    }

    @Test
    fun batteryLevelIsReadFromTheReport() {
        assertEquals(94, G1Protocol.batteryLevel(bytes(0x2C, 0x66, 0x5E, 0x00)))
        assertNull(G1Protocol.batteryLevel(bytes(0x2C, 0x01, 0x5E)))
        assertNull(G1Protocol.batteryLevel(bytes(0x4E, 0x66, 0x5E)))
    }
}
