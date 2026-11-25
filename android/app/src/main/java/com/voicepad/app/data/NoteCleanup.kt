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
        DELETE_AUDIO("Delete audio"),
        DELETE_EMPTY("Delete empty"),
        DELETE("Delete"),
    }

    const val HOUR_MS = 60L * 60 * 1000
    const val DAY_MS = 24 * HOUR_MS

    /** How old a note must be before automatic clean-up touches it. */
    enum class AutoAge(val label: String, val ageMs: Long?) {
        OFF("Off", null),
        /** As soon as the note has its text (0 ms: any age). */
        NOW("Immediately", 0L),
        HOURS_1("1 hour", HOUR_MS),
        HOURS_3("3 hours", 3 * HOUR_MS),
        HOURS_24("24 hours", DAY_MS),
        DAYS_7("7 days", 7 * DAY_MS),
        DAYS_30("30 days", 30 * DAY_MS),
    }

    /** @param olderThanDays 0 means any age. */
    fun select(notes: List<Note>, action: Action, olderThanDays: Int, now: Long, busyIds: Set<String> = emptySet()): List<Note> =
        selectOlderThan(notes, action, olderThanDays * DAY_MS, now, busyIds)

    /**
     * What automatic clean-up takes: like [select], but compression only
     * takes notes whose transcription is finished, so a recording waiting
     * for (or retrying) a model keeps its original audio.
     */
    fun autoSelect(notes: List<Note>, action: Action, age: AutoAge, now: Long, busyIds: Set<String> = emptySet()): List<Note> {
        val ageMs = age.ageMs ?: return emptyList()
        return selectOlderThan(notes, action, ageMs, now, busyIds).filter { note ->
            action != Action.COMPRESS || note.status == TranscriptionStatus.DONE
        }
    }

    /** @param ageMs 0 means any age. */
    private fun selectOlderThan(notes: List<Note>, action: Action, ageMs: Long, now: Long, busyIds: Set<String>): List<Note> {
        val cutoff = now - ageMs
        return notes.filter { note ->
            val idle = note.id !in busyIds &&
                note.status != TranscriptionStatus.RECORDING &&
                note.status != TranscriptionStatus.IN_PROGRESS
            val oldEnough = ageMs == 0L || note.createdAt < cutoff
            idle && oldEnough && when (action) {
                Action.ARCHIVE -> !note.pinned && !note.archived
                Action.DELETE -> !note.pinned
                Action.COMPRESS -> note.audioFileName?.endsWith(".wav") == true
                Action.DELETE_AUDIO -> !note.pinned && note.audioFileName != null &&
                    note.status == TranscriptionStatus.DONE && note.currentText.isNotBlank()
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
