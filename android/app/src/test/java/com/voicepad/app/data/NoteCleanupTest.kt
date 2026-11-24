package com.voicepad.app.data

import com.voicepad.app.data.NoteCleanup.Action
import com.voicepad.app.data.NoteCleanup.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteCleanupTest {
    private val now = 100 * DAY_MS
    private fun note(id: String, ageDays: Double, pinned: Boolean = false, archived: Boolean = false,
                     status: TranscriptionStatus = TranscriptionStatus.DONE) =
        Note(id = id, createdAt = now - (ageDays * DAY_MS).toLong(), pinned = pinned, archived = archived, status = status)

    private val notes = listOf(
        note("fresh", 0.5),
        note("twoDays", 2.0),
        note("tenDays", 10.0),
        note("fortyDays", 40.0),
        note("pinnedOld", 40.0, pinned = true),
        note("archivedOld", 40.0, archived = true),
        note("stillTranscribing", 40.0, status = TranscriptionStatus.IN_PROGRESS),
    )

    private fun ids(action: Action, days: Int, busy: Set<String> = emptySet()) =
        NoteCleanup.select(notes, action, days, now, busy).map { it.id }.toSet()

    @Test
    fun `older than 1, 7 and 30 days`() {
        assertEquals(setOf("twoDays", "tenDays", "fortyDays"), ids(Action.ARCHIVE, 1))
        assertEquals(setOf("tenDays", "fortyDays"), ids(Action.ARCHIVE, 7))
        assertEquals(setOf("fortyDays"), ids(Action.ARCHIVE, 30))
    }

    @Test
    fun `pinned and busy notes are never touched`() {
        assertEquals(setOf("fortyDays", "archivedOld"), ids(Action.DELETE, 30))
        assertEquals(setOf("archivedOld"), ids(Action.DELETE, 30, busy = setOf("fortyDays")))
    }

    @Test
    fun `archiving skips notes already archived, deleting includes them`() {
        assertEquals(false, "archivedOld" in ids(Action.ARCHIVE, 30))
        assertEquals(true, "archivedOld" in ids(Action.DELETE, 30))
    }
}
