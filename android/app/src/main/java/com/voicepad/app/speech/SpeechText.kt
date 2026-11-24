package com.voicepad.app.speech

/**
 * Text preparation for the local English voice. Pure Kotlin so it can be
 * unit-tested.
 *
 * Kitten looks every word up in a pronunciation dictionary and silently
 * skips words it doesn't find, digits included ("3:45" came out as nothing
 * in testing). So numbers are spelled out first, and any sentence that still
 * has unknown words is left to the phone's own voice, which can say anything.
 */
object SpeechText {
    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val SCALES = listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")

    /** 0 … 999 999 999 999 in words: 1984 → "one thousand nine hundred eighty four". */
    fun numberToWords(n: Long): String {
        if (n < 0) return "minus " + numberToWords(-n)
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) return TENS[(n / 10).toInt()] + if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else ""
        if (n < 1000) return ONES[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " " + numberToWords(n % 100) else ""
        for ((scale, name) in SCALES) {
            if (n >= scale) {
                val rest = n % scale
                return numberToWords(n / scale) + " " + name + if (rest != 0L) " " + numberToWords(rest) else ""
            }
        }
        return n.toString()
    }

    /** Reads a run of digits one by one (for long numbers such as phone numbers). */
    private fun digits(s: String) = s.map { ONES[it - '0'] }.joinToString(" ")

    private fun integer(s: String): String {
        val plain = s.replace(",", "")
        return if (plain.length > 12) digits(plain) else numberToWords(plain.toLong())
    }

    private val TIME = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val MONEY = Regex("""\$(\d[\d,]*)(?:\.(\d{2}))?""")
    private val PERCENT = Regex("""(\d[\d,]*(?:\.\d+)?)\s?%""")
    private val DECIMAL = Regex("""\b(\d[\d,]*)\.(\d+)\b""")
    private val ORDINAL = Regex("""\b(\d+)(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
    private val INTEGER = Regex("""\b\d{1,3}(?:,\d{3})+\b|\b\d+\b""")
    private val GLUED = Regex("""(\d)([A-Za-z])|([A-Za-z])(\d)""")

    /** English text with its numbers, times, prices and percentages written out in words. */
    fun spellNumbers(text: String): String {
        var s = text
        // "45pm" → "45 pm", "mp3" → "mp 3", so each part can be read.
        s = GLUED.replace(s) { m -> if (m.groupValues[1].isNotEmpty()) "${m.groupValues[1]} ${m.groupValues[2]}" else "${m.groupValues[3]} ${m.groupValues[4]}" }
        s = s.replace(Regex("""(\d+) (st|nd|rd|th)\b""", RegexOption.IGNORE_CASE), "$1$2")
        s = TIME.replace(s) { m ->
            val (h, min) = m.destructured
            val minutes = min.toInt()
            numberToWords(h.toLong()) + when {
                minutes == 0 -> " o'clock"
                minutes < 10 -> " oh " + numberToWords(minutes.toLong())
                else -> " " + numberToWords(minutes.toLong())
            }
        }
        s = MONEY.replace(s) { m ->
            val dollars = integer(m.groupValues[1])
            val cents = m.groupValues[2].takeIf { it.isNotEmpty() && it != "00" }?.let { " and " + numberToWords(it.toLong()) + " cents" } ?: ""
            "$dollars dollars$cents"
        }
        s = PERCENT.replace(s) { m -> spellNumbers(m.groupValues[1]) + " percent" }
        s = DECIMAL.replace(s) { m -> integer(m.groupValues[1]) + " point " + digits(m.groupValues[2]) }
        s = ORDINAL.replace(s) { m -> ordinal(m.groupValues[1].toLong()) }
        s = INTEGER.replace(s) { m -> integer(m.value) }
        return s
    }

    private fun ordinal(n: Long): String {
        val words = numberToWords(n)
        val last = words.substringAfterLast(' ')
        val irregular = mapOf(
            "one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
            "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth",
        )
        val fixed = irregular[last] ?: when {
            last.endsWith("y") -> last.dropLast(1) + "ieth"
            else -> last + "th"
        }
        return words.dropLast(last.length) + fixed
    }

    private val WORD = Regex("""[A-Za-z]+(?:'[A-Za-z]+)*""")

    /** The words of [text] the way the dictionary lists them: lower case, apostrophes kept. */
    fun words(text: String): List<String> = WORD.findAll(text).map { it.value.lowercase() }.toList()

    /** Words of [text] missing from [known] (each would be skipped, not spoken). */
    fun unknownWords(text: String, known: Set<String>): List<String> = words(text).filter { it !in known }

    /**
     * The words in a pronunciation dictionary with lines like
     * `adjusted || ɐ d ʒˈ ʌ s t ᵻ d`. Multi-word entries add each word.
     */
    fun dictionaryWords(lines: Sequence<String>): Set<String> {
        val out = HashSet<String>(200_000)
        for (line in lines) {
            if (line.startsWith("#")) continue
            val entry = line.substringBefore("||", "").trim()
            if (entry.isEmpty()) continue
            for (w in entry.split(' ')) if (w.isNotEmpty()) out += w.lowercase()
        }
        return out
    }
}
