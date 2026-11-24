package com.voicepad.app.transcription

import java.util.Locale

/**
 * Language codes, names and script checks. Pure Kotlin so it can be
 * unit-tested.
 */
object LanguageTags {
    /** Normalises what a speech model reports ("<|zh|>", "zh", "") to a code like "zh", or null. */
    fun fromModel(raw: String?): String? {
        val code = raw.orEmpty().trim().removePrefix("<|").removeSuffix("|>").lowercase()
        return code.takeIf { it.length in 2..3 && it.all(Char::isLetter) && it != "und" && it != "nospeech" }
    }

    fun isEnglish(code: String?) = code == "en"

    /** Human name, e.g. "Mandarin Chinese", "French", "Cantonese". */
    fun displayName(code: String): String = when (code) {
        "zh" -> "Mandarin Chinese"
        "yue" -> "Cantonese"
        else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
    }

    fun containsHan(text: String) = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    fun containsKana(text: String) = text.any {
        val s = Character.UnicodeScript.of(it.code)
        s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA
    }

    /** True when the text has letters outside the Latin alphabet (so romanisation helps). */
    fun hasNonLatinLetters(text: String) = text.any {
        it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN
    }

    /**
     * Picks the spoken language. The speech model's own tag wins when it gave
     * one; otherwise the text-based guess. Chinese characters without any
     * Japanese kana are treated as Chinese, since text detectors often confuse
     * short Chinese with Japanese.
     */
    fun decide(modelLanguage: String?, textGuess: String?, text: String): String? {
        val model = fromModel(modelLanguage)
        val guess = fromModel(textGuess?.substringBefore('-'))
        val chosen = model ?: guess
        if (containsHan(text) && !containsKana(text) && chosen != "yue") return "zh"
        return chosen
    }
}
