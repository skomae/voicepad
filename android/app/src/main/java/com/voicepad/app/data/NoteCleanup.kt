package com.voicepad.app.data

/**
 * Which notes "Clean up" touches. Pure Kotlin so it can be unit-tested.
 *
 * Never a note that is recording or still transcribing. Pinned notes are
 * only touched by Compress, which keeps them as they are, just smaller.
 */
object NoteCleanup {
    enum class Action(val label: String) {
        ARCHIVE("Archive"),
        COMPRESS("Compress audio"),
        DELETE_EMPTY("Delete empty"),
        DELETE("Delete"),
    }

    const val DAY_MS = 24L * 60 * 60 * 1000

    /** @param olderThanDays 0 means any age. */
    fun select(notes: List<Note>, action: Action, olderThanDays: Int, now: Long, busyIds: Set<String> = emptySet()): List<Note> {
        val cutoff = now - olderThanDays * DAY_MS
        return notes.filter { note ->
            val idle = note.id !in busyIds &&
                note.status != TranscriptionStatus.RECORDING &&
                note.status != TranscriptionStatus.IN_PROGRESS
            val oldEnough = olderThanDays == 0 || note.createdAt < cutoff
            idle && oldEnough && when (action) {
                Action.ARCHIVE -> !note.pinned && !note.archived
                Action.DELETE -> !note.pinned
                Action.COMPRESS -> note.audioFileName?.endsWith(".wav") == true
                Action.DELETE_EMPTY -> !note.pinned && isEmpty(note)
            }
        }
    }

    /**
     * Transcribed and nothing was said ("No speech detected"). A note still
     * waiting for, or retrying, its transcription is not empty: its audio may
     * well hold speech.
     */
    fun isEmpty(note: Note): Boolean =
        note.status == TranscriptionStatus.DONE && note.currentText.isBlank() && note.partialText.isNullOrBlank()
}
