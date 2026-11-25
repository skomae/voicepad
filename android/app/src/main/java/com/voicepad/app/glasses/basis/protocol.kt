package com.voicepad.app.glasses.basis

import java.io.ByteArrayOutputStream

// bluetooth device name ---------------------------------------------------------------------------

// Arms advertise as `G1_<channel>_<L|R>_<serial>` (some firmware: `Even G1_...`).
// Parse them with G1Name rather than comparing prefixes: the prefix has changed
// between firmware revisions, and a stale one makes the glasses invisible.

// hardware ids ------------------------------------------------------------------------------------

const val UART_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_WRITE_CHARACTERISTIC_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_READ_CHARACTERISTIC_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

/**
 * The parts of the G1 protocol VoicePad speaks, as documented by Even
 * Realities' own demo app (github.com/even-realities/EvenDemoApp: its README's
 * protocol section, `proto.dart` and `evenai_proto.dart`).
 *
 * Every request is answered on the read characteristic by a packet starting
 * with the same command byte; for most commands the second byte is a status.
 * Pure Kotlin, so it is unit-tested (`G1ProtocolTest`).
 */
object G1Protocol {
    const val EXIT: Byte = 0x18
    const val HEARTBEAT: Byte = 0x25
    const val BATTERY: Byte = 0x2C
    const val TEXT: Byte = 0x4E

    /** Status byte (index 1) of a response: done. */
    const val STATUS_OK: Byte = 0xC9.toByte()
    /** Status byte of a response: received, more expected (a middle packet of several). */
    const val STATUS_CONTINUE: Byte = 0xCB.toByte()
    /** Status byte of a response: refused. */
    const val STATUS_FAILED: Byte = 0xCA.toByte()

    /**
     * Text bytes per 0x4E packet. The demo app splits at 191 so that the 9-byte
     * header plus data stays at 200 bytes, inside what the glasses accept
     * whatever MTU was negotiated. A full 5×40 page is ~205 bytes of ASCII and
     * up to ~300 of CJK, so a page is usually two packets.
     */
    const val TEXT_CHUNK = 191

    /**
     * Screen status for 0x4E: upper nibble 0x70 is "text show" (the mode the
     * demo's text sender uses, which the glasses leave up until told otherwise),
     * lower nibble 0x01 is "display new content".
     */
    const val TEXT_SHOW_NEW: Int = 0x71

    /** Leave whatever is on screen and go back to the dashboard. */
    fun exit(): ByteArray = byteArrayOf(EXIT)

    /** Battery level request; answered by `2C 66 <percent> ...`. */
    fun battery(): ByteArray = byteArrayOf(BATTERY)

    /**
     * Keep-alive the official app sends both arms every 8 s:
     * `25 <len lo> <len hi> <seq> 04 <seq>`, answered by `25 .. .. .. 04 ..`.
     */
    fun heartbeat(seq: Int): ByteArray {
        val length = 6
        val s = (seq % 0xFF).toByte()
        return byteArrayOf(HEARTBEAT, (length and 0xFF).toByte(), ((length shr 8) and 0xFF).toByte(), s, 0x04, s)
    }

    /**
     * One page of [text] as the 0x4E packets that carry it:
     * `4E <seq> <packets> <index> <screen status> <pos hi> <pos lo> <page> <pages> <data…>`,
     * the UTF-8 text cut into pieces of at most [TEXT_CHUNK] bytes. All packets of
     * a page share [seq]; it should change from one page to the next.
     *
     * The pieces are cut on character boundaries, not byte offsets. 191 is not a
     * multiple of a Han character's three bytes, so a byte-wise cut splits the
     * character straddling the boundary and the glasses draw a replacement mark at
     * the end of one packet and the start of the next — the last character of the
     * page, every page.
     */
    fun text(
        text: String,
        seq: Int,
        page: Int = 1,
        pages: Int = 1,
        screenStatus: Int = TEXT_SHOW_NEW,
        position: Int = 0,
    ): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var chunk = ByteArrayOutputStream(TEXT_CHUNK)
        // By code point, not Char: a surrogate pair is one glyph and four bytes, and
        // cutting between its halves breaks it exactly like a split Han character.
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val encoded = String(Character.toChars(codePoint)).encodeToByteArray()
            if (chunk.size() > 0 && chunk.size() + encoded.size > TEXT_CHUNK) {
                chunks.add(chunk.toByteArray())
                chunk = ByteArrayOutputStream(TEXT_CHUNK)
            }
            chunk.write(encoded)
            index += Character.charCount(codePoint)
        }
        // An empty page is still one packet, so the glasses have something to show
        // (and something to acknowledge).
        chunks.add(chunk.toByteArray())

        val count = chunks.size
        require(count <= 255) { "Text too long for one page: ${text.length} characters" }
        return chunks.mapIndexed { chunkIndex, data ->
            byteArrayOf(
                TEXT,
                (seq and 0xFF).toByte(),
                count.toByte(),
                chunkIndex.toByte(),
                screenStatus.toByte(),
                ((position shr 8) and 0xFF).toByte(),
                (position and 0xFF).toByte(),
                page.toByte(),
                pages.toByte(),
            ) + data
        }
    }

    /** Whether [response] acknowledges a packet of a command whose status sits at index 1. */
    fun accepted(response: ByteArray?): Boolean =
        response != null && response.size > 1 && (response[1] == STATUS_OK || response[1] == STATUS_CONTINUE)

    /** Whether [response] answers a heartbeat. */
    fun heartbeatAnswered(response: ByteArray?): Boolean =
        response != null && response.size > 5 && response[0] == HEARTBEAT && response[4] == 0x04.toByte()

    /** The battery percentage in [response], if it is a battery report. */
    fun batteryLevel(response: ByteArray?): Int? =
        if (response != null && response.size > 2 && response[0] == BATTERY && response[1] == 0x66.toByte()) {
            response[2].toInt() and 0xFF
        } else {
            null
        }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { String.format("%02x", it) }
}
