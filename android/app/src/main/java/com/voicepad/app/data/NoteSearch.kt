package com.voicepad.app.data

import java.text.Normalizer

/**
 * Full-text search over notes: current text, its English translation and
 * pinyin, the LLM rewrite, text recovered from unfinished transcriptions,
 * and every earlier revision.
 *
 * The query is split into terms; "quoted phrases" stay together. A note
 * matches when every term appears somewhere in it. Matching ignores case and
 * accents ("cafe" finds "Café"). Pure Kotlin so it can be unit-tested.
 */
object NoteSearch {
    enum class MatchedIn { CURRENT, EARLIER_REVISION }

    data class Result(val note: Note, val matchedIn: MatchedIn, val score: Int)

    fun terms(query: String): List<String> {
        val out = mutableListOf<String>()
        Regex("\"([^\"]+)\"|(\\S+)").findAll(query).forEach { m ->
            val term = (m.groups[1]?.value ?: m.groups[2]?.value).orEmpty().trim()
            if (term.isNotEmpty()) out += fold(term)
        }
        return out
    }

    fun search(notes: List<Note>, query: String): List<Result> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        return notes.mapNotNull { note ->
            val translation = note.currentTranslation
            val current = fold(
                listOfNotNull(note.currentText, note.partialText, translation?.english, translation?.romanization, note.currentRewrite?.text)
                    .joinToString("\n")
            )
            val everything = fold((note.revisions.map { it.text } + listOfNotNull(note.partialText)).joinToString("\n"))
            when {
                terms.all { it in current } -> Result(note, MatchedIn.CURRENT, score(current, terms))
                terms.all { it in everything } -> Result(note, MatchedIn.EARLIER_REVISION, score(everything, terms) / 2)
                else -> null
            }
        }.sortedWith(
            compareByDescending<Result> { it.matchedIn == MatchedIn.CURRENT }
                .thenByDescending { it.note.pinned }
                .thenByDescending { it.score }
                .thenByDescending { it.note.createdAt }
        )
    }

    /**
     * Character ranges in [text] matching any term, for highlighting. Folding is
     * done one character at a time so indices line up with the original text.
     */
    fun matchRanges(text: String, terms: List<String>): List<IntRange> {
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val folded = fold(text)
        val ranges = terms.flatMap { term ->
            generateSequence(folded.indexOf(term)) { i -> folded.indexOf(term, i + 1).takeIf { it >= 0 } }
                .takeWhile { it >= 0 }
                .map { it until it + term.length }
                .toList()
        }.sortedBy { it.first }
        // Merge overlaps.
        val merged = mutableListOf<IntRange>()
        for (r in ranges) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            else merged += r
        }
        return merged
    }

    /** Lowercase, accent-free, and the same length as the input. */
    fun fold(text: String): String = buildString(text.length) {
        for (c in text) {
            val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
            append(base.lowercaseChar())
        }
    }

    private fun score(haystack: String, terms: List<String>): Int =
        terms.sumOf { term -> Regex(Regex.escape(term)).findAll(haystack).count() * term.length }
}
