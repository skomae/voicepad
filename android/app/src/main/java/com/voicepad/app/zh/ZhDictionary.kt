package com.voicepad.app.zh

/**
 * A Chinese–English dictionary: CC-CEDICT, with each entry's word frequency
 * (from jieba's word list) merged in at build time. Pure Kotlin so it can be
 * unit-tested.
 *
 * File format, one entry per line (lines starting with # are comments):
 *
 *     <frequency>\t<traditional> <simplified> [pin1 yin1] /meaning/meaning/
 *
 * The frequency column is optional, so plain CC-CEDICT lines also load.
 * Only the raw bytes and an index are kept in memory; entries are parsed when
 * looked up.
 */
class ZhDictionary private constructor(
    private val data: ByteArray,
    private val starts: IntArray,
    private val ends: IntArray,
    private val frequencies: IntArray,
    private val index: HashMap<String, IntArray>,
) {
    class Entry(
        val traditional: String,
        val simplified: String,
        /** Numbered pinyin as in CC-CEDICT, e.g. "yin2 hang2". */
        val pinyin: String,
        val definitions: List<String>,
        /** How often the word occurs in jieba's corpus (0: not in it). */
        val frequency: Int,
    ) {
        /** A proper noun (CC-CEDICT capitalises their pinyin). */
        val isProperNoun: Boolean get() = pinyin.firstOrNull()?.isUpperCase() == true
        override fun toString() = "$simplified [$pinyin] /${definitions.joinToString("/")}/"
    }

    /** Number of entries. */
    val size: Int get() = starts.size

    /** The longest headword, in characters. */
    val maxWordLength: Int = index.keys.maxOfOrNull { it.length } ?: 1

    /** Sum of all word frequencies, for turning them into probabilities. */
    val totalFrequency: Long = frequencies.fold(0L) { sum, f -> sum + f }.coerceAtLeast(1)

    fun contains(word: String): Boolean = word in index

    /** Entries whose simplified or traditional form is [word], in file order. */
    fun lookup(word: String): List<Entry> = index[word]?.map(::entry).orEmpty()

    /** The highest frequency among [word]'s entries, 0 if unknown. */
    fun frequency(word: String): Int = index[word]?.maxOf { frequencies[it] } ?: 0

    /** Calls [visit] with every entry, for reverse (English → Chinese) lookups. */
    fun forEachEntry(visit: (Entry) -> Unit) {
        for (i in starts.indices) visit(entry(i))
    }

    /**
     * Like [forEachEntry], but only for entries whose line contains [needle]
     * (ASCII, case-insensitive), checked on the raw bytes before parsing:
     * reverse lookups are then quick enough to run on every tap.
     */
    fun forEachEntryContaining(needle: String, visit: (Entry) -> Unit) {
        val n = needle.lowercase().toByteArray(Charsets.US_ASCII)
        if (n.isEmpty()) return forEachEntry(visit)
        for (i in starts.indices) if (containsAscii(starts[i], ends[i], n)) visit(entry(i))
    }

    private fun containsAscii(from: Int, to: Int, needle: ByteArray): Boolean {
        val last = to - needle.size
        var p = from
        outer@ while (p <= last) {
            for (k in needle.indices) {
                var b = data[p + k].toInt()
                if (b in 'A'.code..'Z'.code) b += 32
                if (b != needle[k].toInt()) { p++; continue@outer }
            }
            return true
        }
        return false
    }

    private fun entry(i: Int): Entry {
        val line = String(data, starts[i], ends[i] - starts[i], Charsets.UTF_8)
        return parseLine(line, frequencies[i]) ?: Entry("", "", "", emptyList(), 0)
    }

    companion object {
        /** Parses a whole dictionary file (UTF-8). Malformed lines are skipped. */
        fun parse(data: ByteArray): ZhDictionary {
            val starts = ArrayList<Int>(130_000)
            val ends = ArrayList<Int>(130_000)
            val freqs = ArrayList<Int>(130_000)
            val building = HashMap<String, MutableList<Int>>(300_000)
            var pos = 0
            while (pos < data.size) {
                var end = pos
                while (end < data.size && data[end] != '\n'.code.toByte()) end++
                var lineEnd = end
                if (lineEnd > pos && data[lineEnd - 1] == '\r'.code.toByte()) lineEnd--
                if (lineEnd > pos && data[pos] != '#'.code.toByte()) {
                    // Frequency column, if any.
                    var p = pos
                    var freq = 0
                    while (p < lineEnd && data[p] in '0'.code.toByte()..'9'.code.toByte()) {
                        freq = freq * 10 + (data[p] - '0'.code.toByte()); p++
                    }
                    val bodyStart = if (p > pos && p < lineEnd && data[p] == '\t'.code.toByte()) p + 1 else pos
                    if (bodyStart == pos) freq = 0
                    // Headwords: up to the " [" that opens the pinyin.
                    var q = bodyStart
                    while (q + 1 < lineEnd && !(data[q] == ' '.code.toByte() && data[q + 1] == '['.code.toByte())) q++
                    if (q + 1 < lineEnd) {
                        val heads = String(data, bodyStart, q - bodyStart, Charsets.UTF_8).split(' ')
                        if (heads.size == 2) {
                            val i = starts.size
                            starts += bodyStart; ends += lineEnd; freqs += freq
                            building.getOrPut(heads[1]) { ArrayList(1) } += i
                            if (heads[0] != heads[1]) building.getOrPut(heads[0]) { ArrayList(1) } += i
                        }
                    }
                }
                pos = end + 1
            }
            val index = HashMap<String, IntArray>(building.size * 2)
            for ((k, v) in building) index[k] = v.toIntArray()
            return ZhDictionary(data, starts.toIntArray(), ends.toIntArray(), freqs.toIntArray(), index)
        }

        fun parse(text: String): ZhDictionary = parse(text.toByteArray(Charsets.UTF_8))

        private val LINE = Regex("""^(\S+) (\S+) \[([^\]]*)\] /(.*)/\s*$""")

        internal fun parseLine(line: String, frequency: Int): Entry? {
            val m = LINE.find(line) ?: return null
            val (trad, simp, pinyin, defs) = m.destructured
            return Entry(trad, simp, pinyin, defs.split('/').filter { it.isNotBlank() }, frequency)
        }
    }
}
