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
            val ch = normalize(raw)
            val stroke = map(ch) ?: continue
            out += stroke
        }
        return out
    }

    /** Folds an accented character to its ASCII base, or NUL if none exists. */
    private fun normalize(ch: Char): Char {
        if (ch.code < 128) return ch
        val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFKD)
        return decomposed.firstOrNull { it.code < 128 } ?: '\u0000'
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
