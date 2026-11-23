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
) {
    val currentText: String get() = revisions.lastOrNull()?.text ?: ""
}
