package com.voicepad.app.conversation

/**
 * Splits a note's text into the paragraphs the caption view shows, reading the
 * speaker labels [SpeakerTranscript] writes ("Speaker 2: …"). Pure Kotlin.
 */
object CaptionLines {
    /** One paragraph: who said it (0-based speaker index, null when unlabelled) and what. */
    data class Line(val speaker: Int?, val text: String) {
        val label: String? get() = speaker?.let { SpeakerTranscript.label(it) }
    }

    private val LABEL = Regex("""^Speaker (\d+):\s*""")

    fun parse(text: String): List<Line> =
        text.split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { paragraph ->
                val match = LABEL.find(paragraph)
                val number = match?.groupValues?.get(1)?.toIntOrNull()
                if (match != null && number != null && number >= 1) {
                    Line(number - 1, paragraph.substring(match.range.last + 1).trim())
                } else {
                    Line(null, paragraph)
                }
            }
            .filter { it.text.isNotEmpty() }

    /**
     * Where the words still arriving begin in [text]: the last [words] words, or for
     * Chinese and Japanese (no spaces) the last [chars] characters. Everything before
     * it has settled and is drawn dimmer.
     */
    fun settledLength(text: String, dense: Boolean, words: Int = 3, chars: Int = 5): Int {
        if (dense) return (text.length - chars).coerceAtLeast(0)
        var cut = text.length
        repeat(words) { cut = text.lastIndexOf(' ', (cut - 1).coerceAtLeast(0)).coerceAtLeast(0) }
        return cut
    }
}
