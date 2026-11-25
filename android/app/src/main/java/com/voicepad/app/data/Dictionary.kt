package com.voicepad.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A named list of the user's own words (names, jargon, product names) that
 * the speech models tend to get wrong. Only enabled dictionaries are used.
 */
@Serializable
data class UserDictionary(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    /** In the user's spelling; a phrase ("Claude Code") counts as one entry. */
    val words: List<String> = emptyList(),
)

/** Pure word handling for dictionaries, kept free of Android so it can be unit-tested. */
object DictionaryWords {
    private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’\\-]*")

    /** The words of [text] as written, without surrounding punctuation. */
    fun words(text: String): List<String> =
        WORD.findAll(text).map { it.value.trimEnd('\'', '’', '-') }.filter { it.isNotEmpty() }.toList()

    /** An entry cleaned up for saving: trimmed, inner spaces collapsed; null if it has no letters or digits. */
    fun clean(word: String): String? =
        word.trim().replace(Regex("\\s+"), " ").takeIf { w -> w.any { it.isLetterOrDigit() } }

    /**
     * Words in [after] that weren't in [before], in the order they first
     * appear, each once. Case matters ("iphone" → "iPhone" is a new word),
     * plain numbers don't count, and a possessive "'s" is dropped so
     * "Kubernetes's" offers "Kubernetes". Words already in [known] (any
     * dictionary, any case-exact match) are left out.
     */
    fun newWords(before: String, after: String, known: Collection<String> = emptyList()): List<String> {
        val old = words(before).map(::stripPossessive).toSet()
        val skip = known.toSet()
        return words(after)
            .map(::stripPossessive)
            .filter { w -> w !in old && w !in skip && w.any { it.isLetter() } }
            .distinct()
    }

    private fun stripPossessive(word: String): String {
        val stripped = word.removeSuffix("'s").removeSuffix("’s")
        return if (stripped.isNotEmpty() && stripped != word) stripped else word
    }

    /** Letters and digits only, lower case: how an entry and heard text are compared. */
    internal fun key(text: String): String = buildString {
        text.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) }
    }

    internal fun editDistance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}

/**
 * Fixes how the speech model spelled the user's dictionary words, after
 * recognition. None of the on-device models takes a vocabulary list (Whisper
 * here has no prompt, and the Parakeet/SenseVoice models ship no BPE
 * vocabulary for sherpa-onnx hotwords), so this is where the dictionary
 * improves their output, and the corrected text is what gets translated.
 *
 * A run of heard words is replaced by an entry when:
 * - their letters and digits match ignoring case, spaces and hyphens
 *   ("chat gpt", "Chat-GPT" → "ChatGPT", "voice pad" → "VoicePad"); or
 * - the same number of words is one letter off (two for 10+ letters), for
 *   entries of 7+ letters that start with the same letter ("Kubernetis" →
 *   "Kubernetes"). Shorter words are too easily confused with ordinary ones.
 * A possessive "'s" on the last word is kept. An all-lower-case entry only
 * fixes spelling, never the case the model chose.
 */
class VocabularyCorrector(entries: Collection<String>) {
    private class Entry(val text: String, val key: String, val wordCount: Int) {
        val fixesCase = text.any { it.isUpperCase() }
    }

    private val entries: List<Entry> = entries
        .mapNotNull { DictionaryWords.clean(it) }
        .distinct()
        .map { Entry(it, DictionaryWords.key(it), DictionaryWords.words(it).size.coerceAtLeast(1)) }
        .filter { it.key.isNotEmpty() }
        // Longer phrases first, so "Claude Code" wins over "Claude".
        .sortedWith(compareByDescending<Entry> { it.wordCount }.thenByDescending { it.key.length })

    private val maxWindow = (this.entries.maxOfOrNull { it.wordCount } ?: 0) + 2

    val isEmpty: Boolean get() = entries.isEmpty()

    private data class Token(val start: Int, val end: Int)

    fun correct(text: String): String {
        if (entries.isEmpty() || text.isBlank()) return text
        val tokens = TOKEN.findAll(text).map { Token(it.range.first, it.range.last + 1) }.toList()
        if (tokens.isEmpty()) return text
        val out = StringBuilder()
        var copiedUpTo = 0
        var i = 0
        while (i < tokens.size) {
            val match = matchAt(text, tokens, i)
            if (match == null) { i++; continue }
            val (count, replacement) = match
            out.append(text, copiedUpTo, tokens[i].start).append(replacement)
            copiedUpTo = tokens[i + count - 1].end
            i += count
        }
        out.append(text, copiedUpTo, text.length)
        return out.toString()
    }

    /** The number of tokens from [i] to replace and their replacement, or null. */
    private fun matchAt(text: String, tokens: List<Token>, i: Int): Pair<Int, String>? {
        // Joining stops at anything but spaces or hyphens between words.
        var windows = 1
        while (windows < maxWindow && i + windows < tokens.size) {
            val gap = text.substring(tokens[i + windows - 1].end, tokens[i + windows].start)
            if (gap.isEmpty() || !gap.all { it == ' ' || it == '-' }) break
            windows++
        }
        for (entry in entries) {
            for (n in windows downTo 1) {
                val raw = text.substring(tokens[i].start, tokens[i + n - 1].end)
                val (heard, possessive) = splitPossessive(raw)
                val heardKey = DictionaryWords.key(heard)
                val replacement = when {
                    heardKey == entry.key -> if (entry.fixesCase || n > 1) entry.text else heard
                    n == entry.wordCount && fuzzy(entry, heardKey) ->
                        if (entry.fixesCase) entry.text else entry.text.lowercase()
                    else -> continue
                }
                return n to replacement + possessive
            }
        }
        return null
    }

    private fun fuzzy(entry: Entry, heard: String): Boolean {
        if (entry.key.length < 7 || heard.isEmpty() || heard[0] != entry.key[0]) return false
        val limit = if (entry.key.length >= 10) 2 else 1
        return DictionaryWords.editDistance(entry.key, heard, limit) <= limit
    }

    private fun splitPossessive(word: String): Pair<String, String> {
        for (suffix in listOf("'s", "’s")) {
            if (word.length > suffix.length && word.endsWith(suffix)) {
                return word.dropLast(suffix.length) to word.takeLast(suffix.length)
            }
        }
        return word to ""
    }

    private companion object {
        val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’]*")
    }
}
