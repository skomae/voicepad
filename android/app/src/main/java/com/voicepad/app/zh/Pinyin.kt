package com.voicepad.app.zh

/**
 * CC-CEDICT writes pinyin with tone numbers ("yin2 hang2"); people read tone
 * marks ("yín háng"). Pure Kotlin so it can be unit-tested.
 */
object Pinyin {
    private val MARKS = mapOf(
        'a' to "āáǎà", 'e' to "ēéěè", 'i' to "īíǐì", 'o' to "ōóǒò", 'u' to "ūúǔù", 'ü' to "ǖǘǚǜ",
        'A' to "ĀÁǍÀ", 'E' to "ĒÉĚÈ", 'I' to "ĪÍǏÌ", 'O' to "ŌÓǑÒ", 'U' to "ŪÚǓÙ", 'Ü' to "ǕǗǙǛ",
    )

    /** "yin2 hang2" → "yín háng"; "nu:3" → "nǚ"; anything that isn't a numbered syllable passes through. */
    fun marks(numbered: String): String = numbered.trim().split(Regex("\\s+")).joinToString(" ") { syllable(it) }

    /** One syllable: "hang2" → "háng". Tone 5 (neutral) gets no mark. */
    fun syllable(s: String): String {
        val tone = s.lastOrNull()?.digitToIntOrNull()
        val body = (if (tone != null) s.dropLast(1) else s)
            .replace("u:", "ü").replace("U:", "Ü").replace('v', 'ü').replace('V', 'Ü')
        if (tone == null || tone !in 1..5 || body.isEmpty() || body.none { it.lowercaseChar() in "aeiouü" }) {
            return body.ifEmpty { s }
        }
        if (tone == 5) return body
        val lower = body.lowercase()
        // Standard rule: a or e takes the mark; in "ou" the o; otherwise the last vowel.
        val at = when {
            'a' in lower -> lower.indexOf('a')
            'e' in lower -> lower.indexOf('e')
            "ou" in lower -> lower.indexOf("ou")
            else -> lower.indexOfLast { it in "aeiouü" }
        }
        val vowel = body[at]
        val marked = MARKS[vowel]?.get(tone - 1) ?: return body
        return body.substring(0, at) + marked + body.substring(at + 1)
    }

    /** The tone of a numbered syllable (1–4, 5 for neutral), or 0 when there's none. */
    fun tone(numbered: String): Int = numbered.lastOrNull()?.digitToIntOrNull()?.takeIf { it in 1..5 } ?: 0

    private val BRACKETED = Regex("\\[([^]]+)]")

    /**
     * Glosses cite other words with numbered pinyin in brackets — CC-CEDICT writes
     * "you (informal, as opposed to courteous 您[nin2])". The characters elsewhere in
     * the sheet carry tone marks, so convert the bracket too; brackets without a tone
     * digit are left exactly as they are.
     */
    fun marksInBrackets(text: String): String = BRACKETED.replace(text) { m ->
        val inside = m.groupValues[1]
        if (inside.any { it.isDigit() }) "[" + marks(inside) + "]" else m.value
    }
}
