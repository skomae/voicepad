package com.voicepad.app.data

import com.voicepad.app.transcription.LanguageTags

/**
 * Joins per-utterance translations of a live recording into one. English
 * utterances count as their own translation, so a recording that mixes
 * Mandarin and English reads as one continuous English text. Pure Kotlin so
 * it can be unit-tested.
 */
object TranslationJoin {
    data class Part(
        val text: String,
        /** Language the speech model or translator reported, if any. */
        val language: String?,
        /** English for this part (its own text when it was English), once translated. */
        val english: String?,
        val romanization: String?,
        /** Translation finished (or was attempted) for this part. */
        val translated: Boolean,
    )

    fun combine(parts: List<Part>, revisionId: String): NoteTranslation? {
        if (parts.isEmpty()) return null
        val foreign = parts.mapNotNull { it.language }.filterNot(LanguageTags::isEnglish)
        val language = foreign.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: return NoteTranslation(revisionId, TranslationStatus.NOT_NEEDED, parts.firstNotNullOfOrNull { it.language })
        val english = parts.mapNotNull { it.english }.joinToString(" ")
        val romanization = parts.mapNotNull { it.romanization }.joinToString(" ").ifBlank { null }
        val waiting = parts.any { !it.translated }
        return NoteTranslation(
            revisionId = revisionId,
            status = if (english.isBlank() && waiting) TranslationStatus.PENDING else TranslationStatus.DONE,
            language = language,
            english = english.ifBlank { null },
            romanization = romanization,
        )
    }
}
