package com.voicepad.app.glasses

import com.voicepad.app.conversation.Stage
import com.voicepad.app.conversation.Turn
import com.voicepad.app.transcription.LanguageTags

/** What the glasses show of the conversation. */
enum class GlassesScope(val label: String, val description: String) {
    /** Only the other person, translated: the glasses as a private subtitle track. */
    THEM("Them", "Only the other person's speech, translated into your language."),
    /** Both sides, in your language: the whole conversation as it happens. */
    BOTH("Both", "Everything said, in your language, marked with who said it."),
}

/**
 * The conversation as it fits on a pair of Even Realities G1 glasses.
 *
 * The waveguide is a 40-column, 5-line green display (640×200: a 16-pixel
 * character cell, five rows), so a page holds 200 characters — roughly two
 * short turns. There's nothing to scroll with and no room for chrome, so
 * this is a subtitle track rather than a transcript: the newest lines fill
 * the page from the bottom up, and older turns scroll off the top.
 *
 * Wide characters (Chinese, Japanese, Korean) count as two of the 40
 * columns, which is why a Chinese line fits around 20 characters.
 */
object GlassesScreen {
    const val LINES = 5
    const val COLUMNS = 40

    /** Marks a turn said by the other person, when both sides are shown. */
    private const val THEM = "> "
    /** Marks a turn of your own. */
    private const val ME = "- "

    /**
     * The page for [stage]: [LINES] lines of at most [COLUMNS] columns, always
     * exactly five, empty ones padded at the top so text grows upwards from
     * the bottom of the display.
     */
    fun of(
        stage: Stage,
        myLanguage: String,
        theirLanguage: String,
        scope: GlassesScope,
        listening: Boolean,
    ): List<String> {
        val turns = stage.history + listOfNotNull(stage.turn)
        val shown = when (scope) {
            GlassesScope.THEM -> turns.filter { it.language != myLanguage }
            GlassesScope.BOTH -> turns
        }
        if (shown.isEmpty()) return idle(myLanguage, theirLanguage, listening)
        val lines = shown.flatMap { block(it, myLanguage, scope) }
        val page = lines.takeLast(LINES)
        return List(LINES - page.size) { "" } + page
    }

    /** Nothing said yet: which two languages, and whether anyone is listening. */
    private fun idle(myLanguage: String, theirLanguage: String, listening: Boolean): List<String> {
        val pair = "${short(theirLanguage)} → ${short(myLanguage)}"
        return listOf("", "", centre(pair), centre(if (listening) "listening…" else "paused"), "")
    }

    private fun short(language: String) = language.uppercase()

    /** One turn as [myLanguage]'s reader sees it, wrapped and marked. */
    private fun block(turn: Turn, myLanguage: String, scope: GlassesScope): List<String> {
        val spoken = turn.language == myLanguage
        val gutter = when {
            scope == GlassesScope.THEM -> ""
            spoken -> ME
            else -> THEM
        }
        val text = turn.textIn(myLanguage)
        if (text == null) {
            // Their words, not translated yet: the page keeps the last translation
            // up and this says something is coming, rather than flashing a script
            // the reader can't read. (A failed translation shows what was said.)
            return if (turn.translationFailed) mark(gutter, wrap(turn.text, COLUMNS - gutter.length)) else listOf(gutter + "…")
        }
        return mark(gutter, wrap(text, COLUMNS - gutter.length))
    }

    /** Puts [gutter] at the start of a turn's first line; later lines line up under it. */
    private fun mark(gutter: String, lines: List<String>): List<String> {
        if (gutter.isEmpty()) return lines
        return lines.mapIndexed { i, line -> if (i == 0) gutter + line else " ".repeat(gutter.length) + line }
    }

    // region Wrapping

    /** A piece of text that mustn't be split unless it is wider than a whole line. */
    private data class Token(val text: String, val spaceBefore: Boolean)

    /** Splits [text] into lines of at most [columns] display columns. */
    fun wrap(text: String, columns: Int = COLUMNS): List<String> {
        val clean = text.replace('\n', ' ').replace('\r', ' ').replace(WHITESPACE, " ").trim()
        if (clean.isEmpty()) return listOf("")
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var filled = 0
        fun breakLine() {
            if (line.isNotEmpty()) {
                lines += line.toString().trimEnd()
                line.setLength(0)
                filled = 0
            }
        }
        for (token in tokens(clean)) {
            val width = width(token.text)
            val gap = if (token.spaceBefore && filled > 0) 1 else 0
            if (filled + gap + width <= columns) {
                if (gap == 1) line.append(' ')
                line.append(token.text)
                filled += gap + width
                continue
            }
            breakLine()
            if (width <= columns) {
                line.append(token.text)
                filled = width
                continue
            }
            // One token wider than the whole line: cut it by width, not by word.
            val pieces = cut(token.text, columns)
            pieces.dropLast(1).forEach { lines += it }
            line.append(pieces.last())
            filled = width(pieces.last())
        }
        breakLine()
        return lines.ifEmpty { listOf("") }
    }

    /** Splits text into words, single wide characters, and punctuation that belongs to the word before it. */
    private fun tokens(text: String): List<Token> {
        val out = ArrayList<Token>()
        val word = StringBuilder()
        var pendingSpace = false

        fun emit(piece: String, wide: Boolean) {
            if (piece.isEmpty()) return
            val closes = wide && piece.first() in CLOSERS
            if (closes && out.isNotEmpty()) {
                out[out.lastIndex] = out.last().let { it.copy(text = it.text + piece) }
            } else {
                out += Token(piece, pendingSpace && out.isNotEmpty())
            }
            pendingSpace = false
        }
        fun flushWord() {
            if (word.isNotEmpty()) {
                emit(word.toString(), wide = false)
                word.setLength(0)
            }
        }
        for (c in text) {
            when {
                c == ' ' -> {
                    flushWord()
                    pendingSpace = true
                }
                isWide(c) -> {
                    flushWord()
                    emit(c.toString(), wide = true)
                }
                else -> word.append(c)
            }
        }
        flushWord()
        return out
    }

    /** Cuts [text] into pieces of at most [columns] columns each. */
    private fun cut(text: String, columns: Int): List<String> {
        val out = ArrayList<String>()
        val piece = StringBuilder()
        var filled = 0
        for (c in text) {
            val w = width(c)
            if (filled + w > columns && piece.isNotEmpty()) {
                out += piece.toString()
                piece.setLength(0)
                filled = 0
            }
            piece.append(c)
            filled += w
        }
        if (piece.isNotEmpty()) out += piece.toString()
        return out.ifEmpty { listOf("") }
    }

    /** How many of the display's columns a character takes: 2 for wide scripts. */
    fun width(c: Char): Int = if (isWide(c)) 2 else 1

    fun width(text: String): Int = text.sumOf { width(it) }

    private fun isWide(c: Char): Boolean = Character.isIdeographic(c.code) ||
        LanguageTags.containsHan(c.toString()) || LanguageTags.containsKana(c.toString()) ||
        c.code in 0xAC00..0xD7A3 || // Hangul syllables
        c.code in 0xFF00..0xFF60 || // full-width forms
        c.code in 0x3000..0x303F || // CJK punctuation
        c.code in 0x20000..0x2FA1F // rare ideographs

    /** Punctuation that may not start a line. */
    private const val CLOSERS = "，。！？、；：）」』】》〉’””%"

    private val WHITESPACE = Regex("\\s+")

    /** Centres [text] within the display, by display width. */
    fun centre(text: String): String {
        val pad = ((COLUMNS - width(text)) / 2).coerceAtLeast(0)
        return " ".repeat(pad) + text
    }

    // endregion
}
