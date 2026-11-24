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
        // A part whose "translation" is its own words was English all along
        // (short utterances like "Yeah." are easily misdetected).
        fun effectiveLanguage(p: Part) =
            if (p.english != null && TextSimilarity.nearlySame(p.english, p.text)) "en" else p.language
        // The label is the foreign language with the most text, not the most utterances.
        val foreign = parts.mapNotNull { p -> effectiveLanguage(p)?.let { it to p.text.length } }
            .filterNot { LanguageTags.isEnglish(it.first) }
        val language = foreign.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.maxByOrNull { it.value }?.key
            ?: return NoteTranslation(revisionId, TranslationStatus.NOT_NEEDED, "en")
        val english = parts.mapNotNull { it.english }.joinToString(" ")
        val romanization = parts.mapNotNull { it.romanization }.joinToString(" ").ifBlank { null }
        val waiting = parts.any { !it.translated }
        // Nothing to show if the English reads the same as what was said.
        if (!waiting && TextSimilarity.nearlySame(english, parts.joinToString(" ") { it.text })) {
            return NoteTranslation(revisionId, TranslationStatus.NOT_NEEDED, "en")
        }
        return NoteTranslation(
            revisionId = revisionId,
            status = if (english.isBlank() && waiting) TranslationStatus.PENDING else TranslationStatus.DONE,
            language = language,
            english = english.ifBlank { null },
            romanization = romanization,
        )
    }
}
