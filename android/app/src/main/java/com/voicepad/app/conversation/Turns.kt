package com.voicepad.app.conversation

import com.voicepad.app.transcription.LanguageTags

/** How the conversation screen is laid out. */
enum class ConversationLayout(val label: String) {
    /** Two halves, the far one upside down: for a phone lying between two people. */
    FACE_TO_FACE("Across, top / bottom"),
    /**
     * The same, split left and right: for a landscape or unfolded screen
     * between two people sitting at its short ends.
     */
    FACE_TO_FACE_SIDEWAYS("Across, left / right"),
    /** One column per language: for an unfolded or landscape screen both people look at. */
    SIDE_BY_SIDE("Side by side"),
    /** One list with original and translation: for following along yourself. */
    TRANSCRIPT("Transcript"),
    ;

    /** The layout after this one, for the cycle button. */
    fun next(): ConversationLayout = entries[(ordinal + 1) % entries.size]
}

/**
 * How speakers are told apart: one microphone channel each, by the sound of
 * their voice, or (with neither available) simply by the language they speak.
 */
enum class SpeakerSource { CHANNEL, VOICE, LANGUAGE }

/** Who said a turn. [index] counts from 0 within its [source]. */
data class SpeakerRef(val source: SpeakerSource, val index: Int)

/** One person's stretch of speech, with its translation into the other language. */
data class Turn(
    val id: Long,
    /** Which listening session (one saved note each) it belongs to. */
    val session: Int,
    val speaker: SpeakerRef?,
    /** Spoken language, one of the pair. */
    val language: String,
    val text: String,
    /** Language [translation] is in (the other one of the pair). */
    val target: String,
    val translation: String? = null,
    val translationFailed: Boolean = false,
    /** [translation] is missing or out of date: the turn is held open or being translated. */
    val pending: Boolean = false,
    /** Held open, untranslated, because it sounds unfinished: the speaker may be thinking. */
    val held: Boolean = false,
    /** Pinyin or other Latin letters for [text], when it isn't written in Latin letters. */
    val romanization: String? = null,
    /** Same for [translation]. */
    val translationRomanization: String? = null,
    /** Position in its recording (16 kHz samples). */
    val start: Int,
    val end: Int,
) {
    /** What this turn says in [language]: the original, or its translation. */
    fun textIn(language: String): String? = if (language == this.language) text else translation
}

object Turns {
    /** Speech after a pause shorter than this continues the same turn. */
    const val MAX_GAP_SECONDS = 2.0
    /** Beyond this, a new turn starts anyway, so the newest words stay near the bottom. */
    const val MAX_TURN_CHARS = 240

    /**
     * Whether new speech from [speaker] in [language] starting at [start] continues [previous].
     * [maxGapSeconds] is longer for a turn held open for a thinking pause.
     */
    fun continues(
        previous: Turn?, session: Int, speaker: SpeakerRef?, language: String, start: Int, sampleRate: Int,
        maxGapSeconds: Double = MAX_GAP_SECONDS,
    ): Boolean =
        previous != null &&
            previous.session == session &&
            previous.speaker == speaker &&
            previous.language == language &&
            start - previous.end <= maxGapSeconds * sampleRate &&
            previous.text.length < MAX_TURN_CHARS

    private const val SENTENCE_END = ".?!。？！"
    private const val CLOSERS = "\"'”’」』）)"

    /** Words a sentence rarely ends on: the speaker is probably looking for what comes next. */
    private val TRAILING_OFF = setOf(
        "and", "but", "or", "so", "because", "um", "uh", "er", "like", "the", "a", "an", "to", "of", "with", "that", "if", "when",
        "y", "o", "pero", "porque", "que", "de", "et", "ou", "mais", "parce", "und", "oder", "aber", "weil",
    )
    private val TRAILING_OFF_CJK = listOf("然后", "就是", "那个", "这个", "因为", "所以", "但是", "而且", "还有", "嗯", "呃", "えっと", "あの", "그리고", "그래서")

    /**
     * Whether [text] sounds like its speaker isn't done: no sentence-ending
     * punctuation, an ellipsis, or a last word that leads somewhere ("and",
     * "because", 然后). Such a turn is held open for a thinking pause
     * instead of being translated as a fragment.
     */
    fun looksUnfinished(text: String): Boolean {
        val t = text.trim().trimEnd { it in CLOSERS }
        if (t.isEmpty()) return false
        if (t.endsWith("...") || t.endsWith("…") || t.endsWith("。。")) return true
        if (t.last() !in SENTENCE_END) {
            // A short reply without punctuation ("ok", "好的") is complete; a longer run of words isn't.
            val han = t.count { isCjk(it) }
            return if (han > 0) han >= 5 else t.split(' ').count { it.isNotBlank() } >= 3
        }
        val body = t.trimEnd { it in SENTENCE_END || it.isWhitespace() }
        if (TRAILING_OFF_CJK.any { body.endsWith(it) }) return true
        // A lone "So." or "And." is a complete (if short) answer only when it's all there is.
        val words = body.split(' ', ',', '，', '、').filter { it.isNotBlank() }
        return words.size > 1 && words.last().lowercase() in TRAILING_OFF
    }

    /** Joins two pieces of text: with a space, except between Chinese or Japanese characters. */
    fun join(a: String, b: String): String {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val cjk = isCjk(a.last()) || isCjk(b.first())
        return if (cjk) a + b else "$a $b"
    }

    private fun isCjk(c: Char) =
        LanguageTags.containsHan(c.toString()) || LanguageTags.containsKana(c.toString()) ||
            c in "，。？！：；、「」"

    /** Plain-text transcript of [turns] for the saved note: each turn, then its translation. */
    fun transcript(turns: List<Turn>, label: (Turn) -> String): String =
        turns.joinToString("\n\n") { turn ->
            buildString {
                append(label(turn)).append(": ").append(turn.text)
                turn.translation?.let { append("\n→ ").append(it) }
            }
        }
}
