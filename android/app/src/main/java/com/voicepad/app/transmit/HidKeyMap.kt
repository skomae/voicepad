package com.voicepad.app.transmit

import java.text.Normalizer

/**
 * Boot-keyboard mapping from text to USB HID keypad-page usage codes.
 *
 * The Bluetooth HID device advertises a boot keyboard whose input report is
 * 8 bytes: one modifier byte, one reserved byte, then six keycode slots.
 * [strokes] returns one (modifier, keycode) pair per printable character; the
 * sender emits a key-down report then a key-up report for each pair.
 *
 * This is deliberately US-layout only (what a boot keyboard types on every
 * host). Accented Latin is transliterated to its base letter; anything without
 * a US-layout key is skipped so a note can never wedge the sender. Non-Latin
 * scripts will be typed as romanization later, via the existing Romanizer.
 */
object HidKeyMap {
    // Modifier bits in the first report byte.
    const val LEFT_CTRL = 0x01
    const val LEFT_SHIFT = 0x02
    const val LEFT_ALT = 0x04
    const val LEFT_GUI = 0x08

    const val KEY_ENTER = 0x28
    const val KEY_BACKSPACE = 0x2A
    const val KEY_TAB = 0x2B
    const val KEY_SPACE = 0x2C

    /** One key press: the modifier byte and the keypad-page usage code. */
    data class Stroke(val modifier: Int, val key: Int)

    fun strokes(text: String): List<Stroke> {
        val out = ArrayList<Stroke>(text.length)
        for (raw in text) {
            for (ch in fold(raw)) {
                out += map(ch) ?: continue
            }
        }
        return out
    }

    /**
     * Typographic characters that speech recognisers and LLMs emit but that
     * have no Unicode decomposition to ASCII, so [fold] would drop them.
     */
    private val TYPOGRAPHY = mapOf(
        '\u2018' to "'", '\u2019' to "'", '\u201A' to "'", '\u2032' to "'", // ‘ ’ ‚ ′
        '\u201C' to "\"", '\u201D' to "\"", '\u201E' to "\"", '\u2033' to "\"", // “ ” „ ″
        '\u00AB' to "\"", '\u00BB' to "\"", // « »
        '\u2010' to "-", '\u2011' to "-", '\u2012' to "-", '\u2013' to "-", '\u2014' to "-", '\u2212' to "-",
        '\u2022' to "*", '\u00B7' to "*", // • ·
        '\u00A0' to " ", '\u202F' to " ", '\u2009' to " ", // non-breaking / thin spaces
        '\u00D7' to "x", '\u00F7' to "/", '\u2044' to "/",
        '\u00DF' to "ss", '\u00E6' to "ae", '\u00C6' to "AE", '\u0153' to "oe", '\u0152' to "OE",
        '\u00F8' to "o", '\u00D8' to "O", '\u0142' to "l", '\u0141' to "L", '\u0111' to "d", '\u0110' to "D",
    )

    /**
     * Folds [ch] to the ASCII it should type: itself, a typographic
     * substitute, its decomposition with accents removed (é → e, … → ...,
     * ﬁ → fi), or nothing. A carriage return is dropped so CRLF types one Enter.
     */
    private fun fold(ch: Char): String {
        if (ch == '\r') return ""
        if (ch.code < 128) return ch.toString()
        TYPOGRAPHY[ch]?.let { return it }
        val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFKD)
        return buildString {
            for (d in decomposed) {
                when {
                    d.code < 128 -> append(d)
                    else -> TYPOGRAPHY[d]?.let { append(it) } // e.g. ½ → 1⁄2 → 1/2
                }
            }
        }
    }

    private fun map(ch: Char): Stroke? = when {
        ch in 'a'..'z' -> Stroke(0, 0x04 + (ch - 'a'))
        ch in 'A'..'Z' -> Stroke(LEFT_SHIFT, 0x04 + (ch - 'A'))
        ch in '1'..'9' -> Stroke(0, 0x1E + (ch - '1'))
        ch == '0' -> Stroke(0, 0x27)
        ch == ' ' -> Stroke(0, KEY_SPACE)
        ch == '\n' -> Stroke(0, KEY_ENTER)
        ch == '\t' -> Stroke(0, KEY_TAB)
        ch == '-' -> Stroke(0, 0x2D)
        ch == '_' -> Stroke(LEFT_SHIFT, 0x2D)
        ch == '=' -> Stroke(0, 0x2E)
        ch == '+' -> Stroke(LEFT_SHIFT, 0x2E)
        ch == '[' -> Stroke(0, 0x2F)
        ch == '{' -> Stroke(LEFT_SHIFT, 0x2F)
        ch == ']' -> Stroke(0, 0x30)
        ch == '}' -> Stroke(LEFT_SHIFT, 0x30)
        ch == '\\' -> Stroke(0, 0x31)
        ch == '|' -> Stroke(LEFT_SHIFT, 0x31)
        ch == ';' -> Stroke(0, 0x33)
        ch == ':' -> Stroke(LEFT_SHIFT, 0x33)
        ch == '\'' -> Stroke(0, 0x34)
        ch == '"' -> Stroke(LEFT_SHIFT, 0x34)
        ch == '`' -> Stroke(0, 0x35)
        ch == '~' -> Stroke(LEFT_SHIFT, 0x35)
        ch == ',' -> Stroke(0, 0x36)
        ch == '<' -> Stroke(LEFT_SHIFT, 0x36)
        ch == '.' -> Stroke(0, 0x37)
        ch == '>' -> Stroke(LEFT_SHIFT, 0x37)
        ch == '/' -> Stroke(0, 0x38)
        ch == '?' -> Stroke(LEFT_SHIFT, 0x38)
        ch == '!' -> Stroke(LEFT_SHIFT, 0x1E) // 1
        ch == '@' -> Stroke(LEFT_SHIFT, 0x1F) // 2
        ch == '#' -> Stroke(LEFT_SHIFT, 0x20) // 3
        ch == '$' -> Stroke(LEFT_SHIFT, 0x21) // 4
        ch == '%' -> Stroke(LEFT_SHIFT, 0x22) // 5
        ch == '^' -> Stroke(LEFT_SHIFT, 0x23) // 6
        ch == '&' -> Stroke(LEFT_SHIFT, 0x24) // 7
        ch == '*' -> Stroke(LEFT_SHIFT, 0x25) // 8
        ch == '(' -> Stroke(LEFT_SHIFT, 0x26) // 9
        ch == ')' -> Stroke(LEFT_SHIFT, 0x27) // 0
        else -> null
    }
}
