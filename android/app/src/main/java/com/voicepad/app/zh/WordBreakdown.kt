package com.voicepad.app.zh

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Splits Chinese text into dictionary words and, for each, ranks its possible
 * meanings and readings, marking the ones its English translation used.
 * Pure Kotlin so it can be unit-tested.
 *
 * The scores are estimates from the dictionary, not a translation model's
 * probabilities: each entry is weighted by how well-attested it is (proper
 * nouns, surnames and "variant of" cross-references count for little), each
 * meaning by its place in the entry, and meanings that match a word of the
 * translation are boosted. They sum to 1 per word.
 */
class WordBreakdown(
    private val dictionary: ZhDictionary,
    /**
     * The usual reading of a single character, with tone marks ("le" for
     * 了), if known: on the phone, ICU's Han-Latin, which picks each
     * character's most common reading. Entries with that reading are weighted
     * up, so particles read as particles (了 le, not liǎo).
     */
    private val usualReading: ((String) -> String?)? = null,
) {

    data class Sense(
        val gloss: String,
        /** Pinyin with tone marks. */
        val pinyin: String,
        /** Numbered pinyin, for tone colours. */
        val numbered: String,
        val score: Double,
        /** A word of the translation matches this meaning. */
        val matched: Boolean,
    )

    data class Reading(val pinyin: String, val numbered: String, val score: Double)

    /** One character of a longer word, with its own most likely reading and meaning. */
    data class CharInfo(val char: String, val pinyin: String?, val numbered: String?, val gloss: String?)

    /** One Chinese word, at [start] until [end] in the text. */
    data class Card(
        val text: String,
        val start: Int,
        val end: Int,
        /** Best first; empty when the dictionary doesn't know the word. */
        val senses: List<Sense>,
        val readings: List<Reading>,
        /** The characters of a multi-character word; empty for a single character. */
        val characters: List<CharInfo>,
    ) {
        val best: Sense? get() = senses.firstOrNull { it.matched } ?: senses.firstOrNull()
        /** The reading of the best sense: the meaning in context decides the reading (行 háng vs xíng). */
        val pinyin: String? get() = best?.pinyin ?: readings.firstOrNull()?.pinyin
        val numbered: String? get() = best?.numbered ?: readings.firstOrNull()?.numbered
    }

    /** A word of the translation and the card it was matched to, if any. */
    data class TranslationWord(val text: String, val start: Int, val end: Int, val card: Int?)

    data class Result(val chinese: String, val cards: List<Card>, val translationWords: List<TranslationWord>) {
        /** The card covering [offset] in the Chinese text (or the nearest one before it). */
        fun cardAt(offset: Int): Int? =
            cards.indexOfFirst { offset in it.start until it.end }.takeIf { it >= 0 }
                ?: cards.indexOfLast { it.start <= offset }.takeIf { it >= 0 }
                ?: cards.indices.firstOrNull()

        /** The translation word covering [offset] in the translation (or the nearest one before it). */
        fun translationWordAt(offset: Int): Int? =
            translationWords.indexOfFirst { offset in it.start until it.end }.takeIf { it >= 0 }
                ?: translationWords.indexOfLast { it.start <= offset }.takeIf { it >= 0 }
    }

    /** A Chinese word for an English one, from the reverse lookup. */
    data class Candidate(val word: String, val pinyin: String, val numbered: String, val gloss: String, val score: Double)

    /**
     * Breaks [chinese] into word cards. With an English [translation], links
     * its words to the cards whose meanings they match.
     */
    fun analyse(chinese: String, translation: String? = null): Result {
        val words = translation?.let { englishWords(it) }.orEmpty()
        val stems = words.mapTo(HashSet()) { stem(it.text) }
        val cards = segment(chinese).map { (start, end) -> card(chinese.substring(start, end), start, end, stems) }
        // Each English word goes to the card whose matching meaning scores best.
        val linked = words.map { w ->
            val s = stem(w.text)
            val card = cards.indices
                .mapNotNull { i -> cards[i].senses.filter { it.matched && s in contentStems(it.gloss) }.maxOfOrNull { it.score }?.let { i to it } }
                .maxByOrNull { it.second }?.first
            w.copy(card = card)
        }
        return Result(chinese, cards, linked)
    }

    /**
     * Splits [text] into words: runs of Chinese characters are cut where the
     * product of the words' frequencies is highest (as jieba does); anything
     * else (Latin letters, numbers, punctuation) is left out. Returns
     * [start, end) ranges of the Chinese words.
     */
    fun segment(text: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < text.length) {
            if (!isHan(text[i])) { i++; continue }
            var j = i
            while (j < text.length && isHan(text[j])) j++
            out += segmentRun(text, i, j)
            i = j
        }
        return out
    }

    private fun segmentRun(text: String, from: Int, to: Int): List<Pair<Int, Int>> {
        val n = to - from
        val logTotal = ln(dictionary.totalFrequency.toDouble())
        // Unknown single characters still have to be words of their own.
        val unknown = ln(0.5) - logTotal
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val next = IntArray(n + 1)
        best[n] = 0.0
        val maxLen = minOf(dictionary.maxWordLength, MAX_WORD)
        for (i in n - 1 downTo 0) {
            for (len in 1..minOf(maxLen, n - i)) {
                val word = text.substring(from + i, from + i + len)
                val score = when {
                    dictionary.contains(word) -> ln(dictionary.frequency(word) + 1.0) - logTotal
                    len == 1 -> unknown
                    else -> continue
                } + best[i + len]
                if (score > best[i]) { best[i] = score; next[i] = i + len }
            }
        }
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < n) { out += (from + i) to (from + next[i]); i = next[i] }
        return out
    }

    private fun card(word: String, start: Int, end: Int, stems: Set<String>): Card {
        val senses = senses(word, stems)
        val readings = senses.groupBy { it.numbered.lowercase() }
            .map { (_, list) -> Reading(list.first().pinyin, list.first().numbered, list.sumOf { it.score }) }
            .sortedByDescending { it.score }
        val chars = if (word.length > 1) word.map { c ->
            val top = senses(c.toString(), emptySet()).firstOrNull()
            CharInfo(c.toString(), top?.pinyin, top?.numbered, top?.gloss)
        } else emptyList()
        return Card(word, start, end, senses, readings, chars)
    }

    /** [word]'s meanings, best first, with scores that sum to 1. */
    fun senses(word: String, translationStems: Set<String> = emptySet()): List<Sense> {
        val raw = ArrayList<Sense>()
        val usual = if (word.length == 1) usualReading?.invoke(word)?.trim()?.lowercase() else null
        for (entry in dictionary.lookup(word)) {
            val defs = entry.definitions.filterNot { it.startsWith("CL:") }
            if (defs.isEmpty()) continue
            // Entries with more meanings tend to be the common reading (行 xíng over háng);
            // proper nouns rarely are what's meant.
            val entryWeight = sqrt(defs.size.toDouble()) * (if (entry.isProperNoun) 0.15 else 1.0) *
                (if (usual != null && Pinyin.marks(entry.pinyin).lowercase() == usual) USUAL_READING_BOOST else 1.0)
            val positional = defs.indices.sumOf { 1.0 / (it + 1) }
            defs.forEachIndexed { i, def ->
                val matched = translationStems.isNotEmpty() && contentStems(def).any { it in translationStems }
                val score = entryWeight * (1.0 / (i + 1)) / positional * definitionWeight(def) * (if (matched) MATCH_BOOST else 1.0)
                raw += Sense(def, Pinyin.marks(entry.pinyin), entry.pinyin, score, matched)
            }
        }
        val total = raw.sumOf { it.score }.takeIf { it > 0 } ?: return emptyList()
        return raw.map { it.copy(score = it.score / total) }.sortedByDescending { it.score }
    }

    /**
     * Chinese words meaning [english], most likely first (scores sum to 1).
     * Ranked by word frequency, how early the meaning comes in its entry, and
     * whether the meaning is exactly that word.
     */
    fun englishCandidates(english: String, limit: Int = 8): List<Candidate> {
        val target = stem(english.lowercase().trim())
        if (target.isEmpty() || target in STOP_STEMS) return emptyList()
        val found = ArrayList<Candidate>()
        // Lines without the word's first letters can't match; skip them before parsing.
        val needle = if (english.trim().lowercase().startsWith(target.take(3))) target.take(3) else ""
        dictionary.forEachEntryContaining(needle) { entry ->
            if (entry.isProperNoun) return@forEachEntryContaining
            val defs = entry.definitions.filterNot { it.startsWith("CL:") }
            val i = defs.indexOfFirst { target in contentStems(it) }
            if (i < 0) return@forEachEntryContaining
            val def = defs[i]
            val parts = def.split(';').map { stem(cleanGloss(it).trim().lowercase()) }
            // "money" says it outright; "silver; money" a little less; "money order" least.
            val exactness = when {
                parts.size == 1 && parts[0] == target -> 4.0
                target in parts -> 2.5
                else -> 1.0
            }
            val score = ln(entry.frequency + 2.0) / sqrt(i + 1.0) * exactness * definitionWeight(def)
            found += Candidate(entry.simplified, Pinyin.marks(entry.pinyin), entry.pinyin, def, score)
        }
        val top = found.sortedByDescending { it.score }.distinctBy { it.word }.take(limit)
        val total = top.sumOf { it.score }.takeIf { it > 0 } ?: return emptyList()
        return top.map { it.copy(score = it.score / total) }
    }

    companion object {
        private const val MAX_WORD = 8
        private const val MATCH_BOOST = 4.0
        private const val USUAL_READING_BOOST = 4.0

        fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

        /** Cross-references and surnames are rarely the meaning a reader wants. */
        private fun definitionWeight(def: String): Double = when {
            def.startsWith("surname ") -> 0.15
            def.startsWith("variant of") || def.startsWith("old variant of") || def.startsWith("used in") ||
                def.startsWith("see ") || def.startsWith("Japanese variant") -> 0.1
            def.startsWith("unofficial variant") -> 0.1
            def.startsWith("(onom.)") -> 0.3
            def.startsWith("abbr. for") || def.startsWith("(coll.)") || def.startsWith("(old)") ||
                def.startsWith("(archaic)") || def.startsWith("(dialect)") -> 0.5
            else -> 1.0
        }

        private val WORD = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")

        /** The words of an English sentence, with where they are. */
        fun englishWords(text: String): List<TranslationWord> =
            WORD.findAll(text).map { TranslationWord(it.value, it.range.first, it.range.last + 1, null) }.toList()

        /** Drops parenthesised notes ("(bound form)") and pinyin cross-references ("[hang2]"). */
        private fun cleanGloss(gloss: String) = gloss
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("[^\\x00-\\x7F]+"), " ")

        /** The meaningful words of a gloss, stemmed. */
        fun contentStems(gloss: String): Set<String> =
            WORD.findAll(cleanGloss(gloss)).map { stem(it.value) }.filter { it.isNotEmpty() && it !in STOP_STEMS }.toSet()

        /** A crude English stemmer: enough to match "banks" with "bank" and "went" with "go". */
        fun stem(word: String): String {
            var w = word.lowercase().removeSuffix("'s")
            w = IRREGULAR[w] ?: w
            w = when {
                w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
                w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes")) -> w.dropLast(2)
                w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
                w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
                w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
                else -> w
            }
            // "running" → "run", "stopped" → "stop".
            if (w.length > 3 && w[w.length - 1] == w[w.length - 2] && w.last() !in "lsz") w = w.dropLast(1)
            return w.removeSuffix("e")
        }

        private val IRREGULAR = mapOf(
            "went" to "go", "gone" to "go", "goes" to "go", "was" to "be", "were" to "be", "is" to "be", "are" to "be", "am" to "be",
            "did" to "do", "does" to "do", "done" to "do", "had" to "have", "has" to "have", "ate" to "eat", "eaten" to "eat",
            "saw" to "see", "seen" to "see", "said" to "say", "says" to "say", "made" to "make", "took" to "take", "taken" to "take",
            "came" to "come", "got" to "get", "gave" to "give", "given" to "give", "knew" to "know", "known" to "know",
            "thought" to "think", "told" to "tell", "bought" to "buy", "brought" to "bring", "left" to "leave", "felt" to "feel",
            "found" to "find", "heard" to "hear", "met" to "meet", "paid" to "pay", "sat" to "sit", "slept" to "sleep",
            "spoke" to "speak", "spoken" to "speak", "wrote" to "write", "written" to "write", "drank" to "drink", "ran" to "run",
            "children" to "child", "men" to "man", "women" to "woman", "people" to "person", "me" to "i", "my" to "i", "mine" to "i",
            "us" to "we", "our" to "we", "him" to "he", "his" to "he", "her" to "she", "them" to "they", "their" to "they",
            "better" to "good", "best" to "good", "worse" to "bad", "worst" to "bad",
        )

        val STOPWORDS = setOf(
            "a", "an", "the", "to", "of", "and", "or", "in", "on", "at", "for", "with", "by", "from", "as", "be",
            "it", "that", "this", "one", "one'", "sb", "sth", "etc", "so", "not", "no", "than", "into", "up", "out", "about",
            "also", "used", "form", "bound", "literary", "fig", "lit", "abbr", "pr", "tw", "cl", "esp", "classifier", "particle",
            "do", "have", "very", "some", "any", "kind", "sort", "way",
        )

        /** [STOPWORDS] as [stem] leaves them, since glosses and translations are compared stemmed. */
        private val STOP_STEMS: Set<String> by lazy { STOPWORDS.mapTo(HashSet()) { stem(it) } }
    }
}
