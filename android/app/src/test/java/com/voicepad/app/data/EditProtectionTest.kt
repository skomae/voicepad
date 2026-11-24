package com.voicepad.app.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A user's edit must never be replaced by a later or empty transcription. */
class EditProtectionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun rev(text: String, source: RevisionSource) = Revision(text = text, source = source)

    @Test
    fun `editing a pending note settles it so the queue won't transcribe over it`() {
        val store = NoteStore(tmp.root)
        store.add(Note(id = "a", status = TranscriptionStatus.FAILED, lastError = "model failed", partialText = "half"))
        store.addRevision("a", "What I actually said", RevisionSource.EDIT)
        val note = store.note("a")!!
        assertEquals(TranscriptionStatus.DONE, note.status)
        assertEquals(false, note.needsTranscription)
        assertEquals(null, note.lastError)
        assertEquals("What I actually said", note.currentText)
    }

    @Test
    fun `an automatic transcription that finishes after an edit goes behind it`() {
        val note = Note(revisions = listOf(rev("my edit", RevisionSource.EDIT)))
        val after = note.withTranscription("machine text", RevisionSource.TRANSCRIPTION, "Parakeet v3", null)
        assertEquals("my edit", after.currentText)
        assertEquals(listOf("machine text", "my edit"), after.revisions.map { it.text })
    }

    @Test
    fun `an empty transcription never replaces text`() {
        val edited = Note(revisions = listOf(rev("hello there", RevisionSource.EDIT)))
        assertEquals(edited, edited.withTranscription("", RevisionSource.RETRANSCRIPTION, "Parakeet v3", null, userRequested = true))
        val transcribed = Note(revisions = listOf(rev("hello there", RevisionSource.TRANSCRIPTION)))
        assertEquals(transcribed, transcribed.withTranscription("  ", RevisionSource.RETRANSCRIPTION, "x", null))
    }

    @Test
    fun `a re-transcription the user asked for becomes current`() {
        val note = Note(revisions = listOf(rev("old", RevisionSource.TRANSCRIPTION), rev("my edit", RevisionSource.EDIT)))
        val after = note.withTranscription("fresh text", RevisionSource.RETRANSCRIPTION, "Whisper turbo", null, userRequested = true)
        assertEquals("fresh text", after.currentText)
    }

    @Test
    fun `a first transcription with no edits is simply added`() {
        val after = Note().withTranscription("first", RevisionSource.TRANSCRIPTION, "Parakeet v3", "en")
        assertEquals("first", after.currentText)
        assertEquals("en", after.revisions.single().language)
    }
}
