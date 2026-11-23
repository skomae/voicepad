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
) {
    val currentText: String get() = revisions.lastOrNull()?.text ?: ""

    /** True when this note's audio still needs a successful model run. */
    val needsTranscription: Boolean
        get() = status == TranscriptionStatus.PENDING || status == TranscriptionStatus.FAILED
}
