package com.voicepad.app.zh

import com.hankcs.hanlp.HanLP

/**
 * Cuts Chinese into words with HanLP's segmenter instead of the dictionary's
 * own frequency search, which only knows words that happen to be in CC-CEDICT
 * and happily joins two common words into one unlikely long one.
 *
 * HanLP's portable jar carries its core dictionary and pinyin table, so this is
 * fully offline: no model download, no network. Words outside the dictionary
 * still get a reading from HanLP's pinyin table, which is what lets the
 * breakdown label rare hanzi instead of leaving them blank.
 */
class HanlpSegmenter : WordBreakdown.Segmenter {

    override fun segment(run: String): List<Pair<Int, Int>> {
        val words = ArrayList<Pair<Int, Int>>()
        var at = 0
        // HanLP 1.8.4 leaves Term.offset at 0, so positions are counted here by
        // walking the terms in order: they cover the run, punctuation included.
        for (term in HanLP.segment(run)) {
            val word = term.word
            val start = at
            val end = at + word.length
            at = end
            // HanLP keeps whitespace and punctuation as terms of their own.
            if (word.isNotBlank() && word.any { isHan(it) }) words += start to end
        }
        // If the terms didn't add up to the whole run, the caller falls back to
        // the dictionary rather than use spans that point at the wrong text.
        return if (at == run.length) words else emptyList()
    }

    /** Numbered pinyin ("bei3 jing1"), the form the dictionary and its tone colours use. */
    override fun numberedPinyin(word: String): String? {
        val syllables = HanLP.convertToPinyinList(word).mapNotNull { pinyin ->
            val base = pinyin.pinyinWithoutTone
            // HanLP answers "none" for hanzi outside its pinyin table; skip those
            // rather than print "none" (same rule as the pinyin-peek engine).
            if (base.isNullOrBlank() || base.equals("none", ignoreCase = true)) return@mapNotNull null
            val tone = pinyin.tone
            base + if (tone in 1..5) tone.toString() else ""
        }
        return syllables.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
}
