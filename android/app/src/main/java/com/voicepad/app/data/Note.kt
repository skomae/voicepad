package com.voicepad.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class RevisionSource(val label: String) {
    TRANSCRIPTION("Transcribed"),
    EDIT("Edited"),
    RESTORE("Restored"),
    RETRANSCRIPTION("Re-transcribed"),
}

/**
 * Where a note's audio is in the transcription pipeline. Saved with the note
 * so that a crash, a failed model or a killed app never loses track of a
 * recording that still needs text.
 */
@Serializable
enum class TranscriptionStatus {
    /** Microphone is (or was, if the app died) writing this note's audio. */
    RECORDING,
    /** Audio is saved and waiting for a model. */
    PENDING,
    /** A model is working on it right now. */
    IN_PROGRESS,
    /** The current text came from a successful model run (or the user). */
    DONE,
    /** The last attempt failed; the audio is kept for a retry. */
    FAILED,
}

/** One saved version of a note's text. */
@Serializable
data class Revision(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val source: RevisionSource,
    /** Model that produced the text, when it came from a model. */
    val engine: String? = null,
    /** Spoken language the model reported (e.g. "zh"), if any. */
    val language: String? = null,
)

@Serializable
enum class TranslationStatus { PENDING, DONE, FAILED, NOT_NEEDED }

/** English translation (and romanisation) of one revision of a note. */
@Serializable
data class NoteTranslation(
    /** The revision this was made from; a newer revision makes it stale. */
    val revisionId: String,
    val status: TranslationStatus,
    /** Detected language code, e.g. "zh", "fr", "es". */
    val language: String? = null,
    val english: String? = null,
    /** Pinyin for Mandarin, Latin letters for other non-Latin scripts. */
    val romanization: String? = null,
    val attempts: Int = 0,
    val error: String? = null,
)

/** A recording plus the full history of its transcript. */
@Serializable
data class Note(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val durationSeconds: Double = 0.0,
    /** File name inside the recordings directory. */
    val audioFileName: String? = null,
    /** Oldest first; the last element is the current text. */
    val revisions: List<Revision> = emptyList(),
    val status: TranscriptionStatus = TranscriptionStatus.DONE,
    /** Model attempts so far; limits automatic retries of a note that keeps failing. */
    val attempts: Int = 0,
    val lastError: String? = null,
    /** Text recovered so far while recording, kept if the final pass fails. */
    val partialText: String? = null,
    val pinned: Boolean = false,
    /** When it was pinned; pinned notes are listed most recently pinned first. */
    val pinnedAt: Long? = null,
    val translation: NoteTranslation? = null,
    /** Recorded in background-voices mode: its audio is normalised before transcription. */
    val backgroundVoices: Boolean = false,
) {
    val currentText: String get() = revisions.lastOrNull()?.text ?: ""

    /** The translation, if it matches the current text. */
    val currentTranslation: NoteTranslation?
        get() = translation?.takeIf { it.revisionId == revisions.lastOrNull()?.id }

    /**
     * True when the current text has no finished translation yet. Stops
     * retrying a failing translation after [maxAttempts].
     */
    fun needsTranslation(maxAttempts: Int): Boolean {
        val current = revisions.lastOrNull() ?: return false
        if (current.text.isBlank()) return false
        val t = translation
        return t == null || t.revisionId != current.id ||
            (t.status == TranslationStatus.PENDING) ||
            (t.status == TranslationStatus.FAILED && t.attempts < maxAttempts)
    }

    /** True when this note's audio still needs a successful model run. */
    val needsTranscription: Boolean
        get() = status == TranscriptionStatus.PENDING || status == TranscriptionStatus.FAILED
}
