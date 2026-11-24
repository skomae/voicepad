package com.voicepad.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewriteTest {
    private fun noteWith(text: String, rewrite: NoteRewrite? = null): Note {
        val rev = Revision(id = "r1", text = text, source = RevisionSource.TRANSCRIPTION)
        return Note(id = "n", revisions = listOf(rev), rewrite = rewrite)
    }

    @Test
    fun `the rewrite is current while its revision is`() {
        val rewrite = NoteRewrite("r1", "fixed up", "Clean up")
        assertEquals(rewrite, noteWith("raw", rewrite).currentRewrite)
    }

    @Test
    fun `a newer revision makes the rewrite stale`() {
        val rewrite = NoteRewrite("r1", "fixed up", "Clean up")
        val edited = noteWith("raw", rewrite).withRevision("raw, edited", RevisionSource.EDIT, null)
        assertNull(edited.currentRewrite)
    }

    @Test
    fun `search finds notes by their rewrite`() {
        val note = Note(
            id = "n",
            revisions = listOf(Revision(id = "r1", text = "raw", source = RevisionSource.TRANSCRIPTION)),
            rewrite = NoteRewrite("r1", "cleaned up", "Clean up"),
        )
        assertEquals(1, NoteSearch.search(listOf(note), "cleaned").size)
    }
}
