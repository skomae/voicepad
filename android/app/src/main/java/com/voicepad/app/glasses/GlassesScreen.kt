package com.voicepad.app.glasses

import android.icu.text.Transliterator
import android.os.Build
import com.voicepad.app.conversation.Stage
import com.voicepad.app.conversation.Turn
import com.voicepad.app.transcription.LanguageTags

/** What the glasses show of the conversation. */
enum class GlassesScope(val label: String, val description: String) {
    /** Only the other person, translated: the glasses as a private subtitle track. */
    THEM("Them", "Only the other person's speech, translated into your language."),
    /** Both sides, in your language: the whole conversation as it happens. */
    BOTH("Both", "Everything said, in your language, marked with who said it."),
    /**
     * The other person's speech twice: what it means, then their own words with
     * each character's pinyin beside it — for learning the language you're hearing.
     */
    LEARNING(
        "Learning",
        "Everything said, twice: what it means, then it again with the pinyin of every character " +
            "(晚上好 → 晚wǎn 上shàng 好hǎo). For learning, and for checking the translation caught it.",
    ),
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
 *
 * When the reader's language is Chinese, every Chinese turn gets a pinyin
 * line below it, so language learners can read the pronunciation as well
 * as the characters.
 */
object GlassesScreen {
    const val LINES = 5
    const val COLUMNS = 40

    /** Marks a turn said by the other person, when both sides are shown. */
    private const val THEM = "> "
    /** Marks a turn of your own. */
    private const val ME = "- "

    /**
     * ICU transliterator: converts Chinese characters to pinyin with tone marks.
     * Null below API 29, where the class doesn't exist — the glasses then show the
     * characters without pinyin rather than the app crashing on launch.
     */
    private val hanToPinyin: Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { Transliterator.getInstance("Han-Latin") }.getOrNull()
        } else {
            null
        }
    }

    /** True if [language] is a Chinese variety. */
    private fun isChinese(language: String): Boolean =
        language.startsWith("zh", ignoreCase = true) || language.startsWith("yue", ignoreCase = true)

    /**
     * Converts Chinese characters in [text] to pinyin with tone marks.
     * Non-Chinese text (English, numbers, punctuation) passes through unchanged.
     */
    private fun toPinyin(text: String): String {
        // Inline SDK check: the transliterator only exists from API 29.
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val transliterator = hanToPinyin ?: return text
            transliterator.transliterate(text)
        } else {
            return text
        }
        // ICU's Han-Latin output adds spaces between syllables but also between
        // words that were separated by punctuation. Normalise: trim the leading
        // space ICU puts before the first syllable, collapse multiple spaces.
        return raw.trimStart().replace(Regex(" +"), " ")
    }

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
        /** Pinyin for one character, when the turn has no romanisation of its own. */
        syllable: (Char) -> String? = ::icuSyllable,
    ): List<String> {
        val turns = stage.history + listOfNotNull(stage.turn)
        val shown = when (scope) {
            // Learning has both sides in both languages, so it has everything.
            GlassesScope.THEM -> turns.filter { it.language != myLanguage }
            GlassesScope.BOTH, GlassesScope.LEARNING -> turns
        }
        if (shown.isEmpty()) return idle(myLanguage, theirLanguage, listening)
        val lines = shown.flatMap { block(it, myLanguage, scope, syllable) }
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
    private fun block(
        turn: Turn,
        myLanguage: String,
        scope: GlassesScope,
        syllable: (Char) -> String?,
    ): List<String> {
        if (scope == GlassesScope.LEARNING) return learning(turn, myLanguage, syllable)

        val spoken = turn.language == myLanguage
        val gutter = when {
            scope == GlassesScope.THEM -> ""
            spoken -> ME
            else -> THEM
        }
        val text = turn.textIn(myLanguage)
        if (text == null) {
            return if (turn.translationFailed) mark(gutter, wrap(turn.text, COLUMNS - gutter.length)) else listOf(gutter + "…")
        }
        val lines = mark(gutter, wrap(text, COLUMNS - gutter.length))

        // When the reader sees Chinese on the glasses, add a pinyin line below.
        val showPinyin = isChinese(myLanguage) && text.any { isWide(it) }
        if (!showPinyin) return lines

        val pinyin = toPinyin(text)
        val pinyinLines = mark(gutter, wrap(pinyin, COLUMNS - gutter.length))
        return lines + pinyinLines
    }

    /**
     * Learning: what the turn means, then the same words again with each
     * character's pinyin beside it. "晚上好" translated as "Good evening" reads
     *
     *     > Good evening
     *       晚wǎn 上shàng 好hǎo
     *
     * The pinyin comes from the turn's own romanisation when it has one, which
     * was produced for this sentence (so 银行 is háng here, not xíng); otherwise
     * each character is looked up on its own.
     */
    /**
     * Learning: each turn twice, both languages — what it means, then the same
     * words in the language they were said in with every Chinese character's
     * pinyin beside it. "晚上好", said to an English reader, reads
     *
     *     > Good evening
     *       晚wǎn 上shàng 好hǎo
     *
     * Your own turns get the same treatment, so you can see what the translation
     * made of them. The pinyin of a turn is the romanisation made for it (银行 is
     * háng here, not xíng); a turn without one is looked up character by character.
     */
    private fun learning(turn: Turn, myLanguage: String, syllable: (Char) -> String?): List<String> {
        val spoken = turn.language == myLanguage
        val gutter = if (spoken) ME else THEM
        val indent = " ".repeat(gutter.length)

        // The two renderings of the turn: the reader's language, then the one it
        // was said in. Either can be the Chinese one — the pinyin goes there.
        val understood = turn.textIn(myLanguage)
        val understoodRomanization = if (spoken) turn.romanization else turn.translationRomanization
        val original = if (spoken) turn.translation else turn.text
        val originalRomanization = if (spoken) turn.translationRomanization else turn.romanization

        val lines = ArrayList<String>(4)
        if (understood != null) {
            lines += mark(gutter, wrap(annotate(understood, understoodRomanization, syllable), COLUMNS - gutter.length))
        } else if (turn.translationFailed) {
            lines += mark(gutter, wrap(annotate(turn.text, turn.romanization, syllable), COLUMNS - gutter.length))
        } else {
            lines += gutter + "…"
        }
        if (original != null && original != understood) {
            lines += mark(indent, wrap(annotate(original, originalRomanization, syllable), COLUMNS - indent.length))
        }
        return lines
    }

    /**
     * [text] with every Chinese character followed by its pinyin:
     * "晚上好！" → "晚wǎn 上shàng 好hǎo！". Latin words, numbers and punctuation
     * are left alone, and punctuation keeps hugging the pair before it.
     */
    private fun annotate(text: String, romanization: String?, syllable: (Char) -> String?): String {
        val han = text.count { isHan(it) }
        val tokens = romanization.orEmpty().split(' ').filter { it.isNotBlank() }
        // One syllable per character is the only alignment we can trust.
        val aligned = tokens.takeIf { han > 0 && it.size == han }

        val out = StringBuilder(text.length * 3)
        var seen = 0
        var space = false
        var paired = false
        for (c in text) {
            if (c.isWhitespace()) {
                space = true
                continue
            }
            val closes = c in ANNOTATION_CLOSERS
            val wide = isHan(c)
            if (out.isNotEmpty() && !closes && (space || paired || wide)) out.append(' ')
            space = false
            if (wide) {
                out.append(c)
                val pinyin = aligned?.getOrNull(seen) ?: syllable(c)
                if (!pinyin.isNullOrEmpty()) out.append(pinyin)
                seen++
                paired = true
            } else {
                out.append(c)
                paired = false
            }
        }
        return out.toString()
    }

    private fun isHan(c: Char): Boolean = LanguageTags.containsHan(c.toString())

    /** Pinyin for one character from ICU, cached: a page annotates dozens of them. */
    private val syllables = HashMap<Char, String>()

    private fun icuSyllable(c: Char): String? = synchronized(syllables) {
        syllables.getOrPut(c) { toPinyin(c.toString()) }.takeIf { it.isNotEmpty() && it != c.toString() }
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

    /** Same, for the annotated line: it also keeps hugging ASCII punctuation. */
    private const val ANNOTATION_CLOSERS = CLOSERS + ".,!?;:)]}\"'"

    private val WHITESPACE = Regex("\\s+")

    /** Centres [text] within the display, by display width. */
    fun centre(text: String): String {
        val pad = ((COLUMNS - width(text)) / 2).coerceAtLeast(0)
        return " ".repeat(pad) + text
    }

    // endregion
}