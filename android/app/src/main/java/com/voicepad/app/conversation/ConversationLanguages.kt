package com.voicepad.app.conversation

import com.voicepad.app.transcription.LanguageTags

/**
 * Decides which of a conversation's two languages an utterance is in, so it
 * can be translated into the other. Pure Kotlin so it can be unit-tested.
 *
 * Language detection on a few spoken words is unreliable on its own, but a
 * conversation only has two candidates, which makes the choice much easier:
 * the writing system usually settles it, then the speech model's tag, then
 * the text detector's confidence for just those two.
 */
object ConversationLanguages {
    /** Languages not written in the Latin alphabet (for "Latin text must be the Latin side"). */
    private val NON_LATIN = setOf(
        "zh", "yue", "ja", "ko", "ru", "uk", "bg", "be", "mk", "el", "ar", "fa", "ur", "he",
        "hi", "mr", "bn", "gu", "kn", "ta", "te", "th", "ka",
    )

    fun usesLatinScript(code: String) = code !in NON_LATIN

    /**
     * The language of [text], one of [pair] unless nothing points to either
     * (then [fallback], else the first of the pair).
     *
     * @param model what the speech model reported, if anything ("zh", "en", "<|ja|>")
     * @param candidates the text detector's guesses with confidence, e.g. ("es", 0.8)
     */
    fun spoken(
        pair: Pair<String, String>,
        model: String?,
        candidates: List<Pair<String, Float>>,
        text: String,
        fallback: String? = null,
    ): String {
        val both = listOf(pair.first, pair.second)

        // 1. The writing system: Chinese characters, kana, Hangul, Cyrillic…
        val byScript = scriptCandidates(text).filter { it in both }
        if (byScript.size == 1) return byScript[0]

        // 2. The speech model's own tag (Cantonese counts as Chinese if only Chinese is in play).
        normalize(LanguageTags.fromModel(model), both)?.let { if (it in both) return it }

        // 3. The text detector, restricted to the two languages.
        candidates
            .mapNotNull { (code, confidence) -> normalize(LanguageTags.fromModel(code.substringBefore('-')), both)?.let { it to confidence } }
            .filter { it.first in both }
            .maxByOrNull { it.second }
            ?.let { return it.first }

        // 4. Plain Latin letters when only one side writes in Latin letters.
        if (text.any { it.isLetter() } && !LanguageTags.hasNonLatinLetters(text)) {
            val latin = both.filter(::usesLatinScript)
            if (latin.size == 1) return latin[0]
        }
        return fallback?.takeIf { it in both } ?: pair.first
    }

    /** The other language of the pair: what [spoken] text is translated into. */
    fun target(pair: Pair<String, String>, spoken: String): String =
        if (spoken == pair.first) pair.second else pair.first

    private fun normalize(code: String?, both: List<String>): String? = when {
        code == null -> null
        code == "yue" && "yue" !in both && "zh" in both -> "zh"
        code == "zh" && "zh" !in both && "yue" in both -> "yue"
        else -> code
    }

    /** Languages the letters of [text] could belong to, most likely first. */
    internal fun scriptCandidates(text: String): List<String> {
        var han = false; var kana = false; var hangul = false; var cyrillic = false
        var greek = false; var arabic = false; var hebrew = false; var thai = false; var devanagari = false
        for (ch in text) {
            if (!ch.isLetter()) continue
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN -> han = true
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana = true
                Character.UnicodeScript.HANGUL -> hangul = true
                Character.UnicodeScript.CYRILLIC -> cyrillic = true
                Character.UnicodeScript.GREEK -> greek = true
                Character.UnicodeScript.ARABIC -> arabic = true
                Character.UnicodeScript.HEBREW -> hebrew = true
                Character.UnicodeScript.THAI -> thai = true
                Character.UnicodeScript.DEVANAGARI -> devanagari = true
                else -> {}
            }
        }
        return when {
            kana -> listOf("ja")
            hangul -> listOf("ko")
            han -> listOf("zh", "yue", "ja")
            cyrillic -> listOf("ru", "uk", "bg", "be", "mk")
            greek -> listOf("el")
            arabic -> listOf("ar", "fa", "ur")
            hebrew -> listOf("he")
            thai -> listOf("th")
            devanagari -> listOf("hi", "mr")
            else -> emptyList()
        }
    }
}
