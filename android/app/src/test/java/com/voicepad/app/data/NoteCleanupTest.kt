package com.voicepad.app.data

import com.voicepad.app.data.NoteCleanup.Action
import com.voicepad.app.data.NoteCleanup.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteCleanupTest {
    private val now = 100 * DAY_MS
    private fun note(
        id: String, ageDays: Double, pinned: Boolean = false, archived: Boolean = false,
        status: TranscriptionStatus = TranscriptionStatus.DONE, text: String? = "something said",
        audio: String = "$id.wav", partial: String? = null,
    ) = Note(
        id = id, createdAt = now - (ageDays * DAY_MS).toLong(), pinned = pinned, archived = archived, status = status,
        audioFileName = audio, partialText = partial,
        revisions = listOfNotNull(text?.let { Revision(text = it, source = RevisionSource.TRANSCRIPTION) }),
    )

    private val notes = listOf(
        note("fresh", 0.5),
        note("twoDays", 2.0),
        note("tenDays", 10.0),
        note("fortyDays", 40.0),
        note("pinnedOld", 40.0, pinned = true),
        note("archivedOld", 40.0, archived = true, audio = "archivedOld.m4a"),
        note("stillTranscribing", 40.0, status = TranscriptionStatus.IN_PROGRESS),
        note("emptyOld", 40.0, text = ""),
        note("emptyFresh", 0.1, text = ""),
        note("waitingForModel", 40.0, status = TranscriptionStatus.PENDING, text = null),
        note("failedWithPartial", 40.0, status = TranscriptionStatus.FAILED, text = null, partial = "half a sentence"),
    )

    private fun ids(action: Action, days: Int, busy: Set<String> = emptySet()) =
        NoteCleanup.select(notes, action, days, now, busy).map { it.id }.toSet()

    @Test
    fun `older than 1, 7 and 30 days`() {
        assertEquals(setOf("twoDays", "tenDays", "fortyDays", "emptyOld", "waitingForModel", "failedWithPartial"), ids(Action.ARCHIVE, 1))
        assertEquals(setOf("tenDays", "fortyDays", "emptyOld", "waitingForModel", "failedWithPartial"), ids(Action.ARCHIVE, 7))
    }

    @Test
    fun `pinned and busy notes are never deleted or archived`() {
        val deleted = ids(Action.DELETE, 30, busy = setOf("fortyDays"))
        assertEquals(false, "pinnedOld" in deleted)
        assertEquals(false, "fortyDays" in deleted)
        assertEquals(false, "stillTranscribing" in deleted)
        assertEquals(true, "archivedOld" in deleted)
    }

    @Test
    fun `archiving skips notes already archived`() {
        assertEquals(false, "archivedOld" in ids(Action.ARCHIVE, 30))
    }

    @Test
    fun `compress takes every uncompressed recording, pinned ones too, but not busy ones`() {
        val c = ids(Action.COMPRESS, 0)
        assertEquals(true, "pinnedOld" in c)
        assertEquals(true, "fresh" in c)
        assertEquals(false, "archivedOld" in c)      // already compressed
        assertEquals(false, "stillTranscribing" in c)
    }

    @Test
    fun `delete empty only takes finished notes with no text`() {
        assertEquals(setOf("emptyOld", "emptyFresh"), ids(Action.DELETE_EMPTY, 0))
        assertEquals(setOf("emptyOld"), ids(Action.DELETE_EMPTY, 7))
        // Waiting for a model, or a failure with recovered text, may hold speech: never "empty".
        assertEquals(false, NoteCleanup.isEmpty(notes.first { it.id == "waitingForModel" }))
        assertEquals(false, NoteCleanup.isEmpty(notes.first { it.id == "failedWithPartial" }))
    }

    @Test
    fun `any age means zero days`() {
        assertEquals(true, "fresh" in ids(Action.DELETE, 0))
    }

    @Test
    fun `automatic clean-up ages`() {
        val hour = NoteCleanup.HOUR_MS
        val list = listOf(
            note("empty2h", 2.0 * hour / DAY_MS, text = ""),
            note("empty4h", 4.0 * hour / DAY_MS, text = ""),
            note("empty2d", 2.0, text = ""),
            note("emptyPinned", 40.0, text = "", pinned = true),
            note("spoken4h", 4.0 * hour / DAY_MS),
        )
        fun auto(action: Action, age: NoteCleanup.AutoAge) =
            NoteCleanup.autoSelect(list, action, age, now).map { it.id }.toSet()
        assertEquals(emptySet<String>(), auto(Action.DELETE_EMPTY, NoteCleanup.AutoAge.OFF))
        assertEquals(setOf("empty4h", "empty2d"), auto(Action.DELETE_EMPTY, NoteCleanup.AutoAge.HOURS_3))
        assertEquals(setOf("empty2d"), auto(Action.DELETE_EMPTY, NoteCleanup.AutoAge.HOURS_24))
        assertEquals(emptySet<String>(), auto(Action.DELETE_EMPTY, NoteCleanup.AutoAge.DAYS_7))
        assertEquals(setOf("empty4h", "empty2d", "emptyPinned", "spoken4h"), auto(Action.COMPRESS, NoteCleanup.AutoAge.HOURS_3))
    }

    @Test
    fun `automatic compress keeps the original audio of notes still needing a model`() {
        val c = NoteCleanup.autoSelect(notes, Action.COMPRESS, NoteCleanup.AutoAge.DAYS_30, now).map { it.id }.toSet()
        assertEquals(setOf("fortyDays", "pinnedOld", "emptyOld"), c)
    }

    @Test
    fun `delete audio takes notes with text and audio, never pinned or unfinished ones`() {
        val list = listOf(
            note("fresh", 0.01),
            note("old", 5.0),
            note("pinned", 1.0, pinned = true),
            note("waiting", 1.0, status = TranscriptionStatus.PENDING),
            note("noAudio", 1.0).copy(audioFileName = null),
        )
        fun auto(age: NoteCleanup.AutoAge) =
            NoteCleanup.autoSelect(list, Action.DELETE_AUDIO, age, now).map { it.id }.toSet()
        assertEquals(setOf("old"), auto(NoteCleanup.AutoAge.HOURS_1))
        assertEquals(setOf("old"), auto(NoteCleanup.AutoAge.HOURS_24))
        assertEquals(emptySet<String>(), auto(NoteCleanup.AutoAge.OFF))
    }
}
