package com.voicepad.app.data

/**
 * Which notes "Clean up" touches. Pure Kotlin so it can be unit-tested.
 *
 * Never: pinned notes, or a note that is recording or still transcribing.
 * Archive skips notes already archived; delete takes archived ones too.
 */
object NoteCleanup {
    enum class Action { ARCHIVE, DELETE }

    const val DAY_MS = 24L * 60 * 60 * 1000

    fun select(notes: List<Note>, action: Action, olderThanDays: Int, now: Long, busyIds: Set<String> = emptySet()): List<Note> {
        val cutoff = now - olderThanDays * DAY_MS
        return notes.filter { note ->
            !note.pinned &&
                note.createdAt < cutoff &&
                note.id !in busyIds &&
                note.status != TranscriptionStatus.RECORDING &&
                note.status != TranscriptionStatus.IN_PROGRESS &&
                (action == Action.DELETE || !note.archived)
        }
    }
}
