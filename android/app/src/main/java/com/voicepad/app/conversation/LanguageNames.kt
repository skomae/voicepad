package com.voicepad.app.conversation

import com.voicepad.app.transcription.LanguageTags
import java.util.Locale

/** Language names for the conversation screen. */
object LanguageNames {
    /** Offered first in the language pickers. */
    val COMMON = listOf("en", "zh", "es", "fr", "de", "ja", "ko", "it", "pt", "ru", "ar", "hi", "nl", "pl", "tr", "uk", "vi", "th", "id")

    fun english(code: String): String = LanguageTags.displayName(code)

    /** The language's name in itself ("中文", "Español"), so each person can find their side. */
    fun native(code: String): String {
        if (code == "zh") return "中文"
        if (code == "yue") return "粵語"
        val locale = Locale.forLanguageTag(code)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }.ifBlank { english(code) }
    }
}
