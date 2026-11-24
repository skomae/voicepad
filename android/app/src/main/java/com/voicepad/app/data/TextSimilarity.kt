package com.voicepad.app.data

/**
 * Tells when a "translation" is really the same text, e.g. English speech
 * that was misdetected as another language and came back from the
 * translator almost unchanged. Pure Kotlin so it can be unit-tested.
 */
object TextSimilarity {
    /** Words, ignoring case, accents and punctuation. */
    fun words(text: String): List<String> =
        NoteSearch.fold(text).split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }

    /**
     * Share of words the two texts have in common, in order (0..1): twice the
     * longest common word sequence over the total word count.
     */
    fun ratio(a: String, b: String): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var prev = IntArray(y.size + 1)
        var cur = IntArray(y.size + 1)
        for (i in 1..x.size) {
            for (j in 1..y.size) {
                cur[j] = if (x[i - 1] == y[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev; prev = cur; cur = t
            cur.fill(0)
        }
        return 2.0 * prev[y.size] / (x.size + y.size)
    }

    /** True when showing both would just repeat the same words. */
    fun nearlySame(a: String?, b: String?, threshold: Double = 0.8): Boolean =
        a != null && b != null && ratio(a, b) >= threshold
}
