package com.voicepad.app.conversation

/**
 * A note's transcript built utterance by utterance, with the speakers told
 * apart by voice: each piece of text comes with its speaker embedding, joins
 * a [SpeakerClustering], and [text] starts a new "Speaker N:" paragraph
 * whenever the voice changes.
 *
 * A note with only one voice in it reads exactly like an unlabelled note: the
 * labels only appear once a second speaker does. A piece with no embedding
 * (too short to fingerprint, or the voice model isn't loaded) is taken to be
 * the speaker before it. Pure Kotlin so it can be unit-tested.
 */
class SpeakerTranscript(threshold: Float = SpeakerClustering.DEFAULT_THRESHOLD) {
    private val voices = SpeakerClustering(threshold)

    /** Speaker index per piece; null until a voice has been heard. */
    private val parts = mutableListOf<Pair<Int?, String>>()

    /** Adds [text] spoken by the voice with this [embedding] and returns the transcript so far. */
    fun add(text: String, embedding: FloatArray?, language: String? = null): String {
        if (text.isBlank()) return this.text
        val speaker = embedding?.let { voices.assign(it, language) } ?: parts.lastOrNull()?.first
        parts += speaker to text.trim()
        return this.text
    }

    /** How many different voices have been heard. */
    val speakerCount: Int get() = voices.speakerCount

    val text: String
        get() {
            // Pieces before the first fingerprint belong to the first voice heard.
            val first = parts.firstNotNullOfOrNull { it.first }
            val resolved = parts.map { (speaker, text) -> (speaker ?: first) to text }
            if (resolved.mapNotNull { it.first }.distinct().size <= 1) {
                return resolved.joinToString(" ") { it.second }
            }
            val paragraphs = mutableListOf<Pair<Int?, StringBuilder>>()
            for ((speaker, text) in resolved) {
                val last = paragraphs.lastOrNull()
                if (last != null && last.first == speaker) last.second.append(' ').append(text)
                else paragraphs += speaker to StringBuilder(text)
            }
            return paragraphs.joinToString("\n\n") { (speaker, text) -> "${label(speaker ?: 0)}: $text" }
        }

    companion object {
        fun label(speaker: Int) = "Speaker ${speaker + 1}"
    }
}
