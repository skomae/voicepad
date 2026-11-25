package com.voicepad.app.zh

import com.voicepad.app.conversation.Turn

/**
 * What the word breakdown shows: a Chinese text, its translation (if any),
 * and which word was tapped. Pure Kotlin so it can be unit-tested.
 */
data class BreakdownRequest(
    val chinese: String,
    val translation: String? = null,
    /** Language of [translation]; meanings are only matched against English. */
    val translationLanguage: String? = null,
    val focus: Focus? = null,
) {
    sealed interface Focus {
        /** A character offset in [chinese]. */
        data class Chinese(val offset: Int) : Focus
        /** A character offset in [translation]. */
        data class Translation(val offset: Int) : Focus
    }

    val translationIsEnglish: Boolean get() = translationLanguage == "en"

    companion object {
        /** Mandarin, or Cantonese (written in the same characters). */
        fun isChinese(language: String?) = language == "zh" || language == "yue"

        /**
         * The breakdown for a tap on a conversation [turn]: in what was said
         * ([tappedTranslation] false) or in its translation, at [offset].
         * Null when neither side is Chinese (or both are).
         */
        fun forTurn(turn: Turn, tappedTranslation: Boolean, offset: Int?): BreakdownRequest? {
            val spokenChinese = isChinese(turn.language)
            if (spokenChinese == isChinese(turn.target)) return null
            val chinese = if (spokenChinese) turn.text else turn.translation ?: return null
            if (chinese.isBlank()) return null
            val other = if (spokenChinese) turn.translation else turn.text
            val otherLanguage = if (spokenChinese) turn.target else turn.language
            val tappedChinese = tappedTranslation != spokenChinese
            val focus = offset?.let { if (tappedChinese) Focus.Chinese(it) else Focus.Translation(it) }
            return BreakdownRequest(chinese, other?.takeIf { it.isNotBlank() }, otherLanguage, focus)
        }
    }
}
