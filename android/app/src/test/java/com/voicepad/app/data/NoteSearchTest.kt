package com.voicepad.app.data

import com.voicepad.app.data.NoteSearch.MatchedIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {
    private fun note(id: String, vararg texts: String, partial: String? = null, pinned: Boolean = false, createdAt: Long = 0) =
        Note(
            id = id,
            createdAt = createdAt,
            revisions = texts.map { Revision(text = it, source = RevisionSource.TRANSCRIPTION) },
            partialText = partial,
            pinned = pinned,
        )

    private fun ids(results: List<NoteSearch.Result>) = results.map { it.note.id }

    @Test
    fun `every term must match, in any order, ignoring case`() {
        val notes = listOf(note("a", "Buy milk and EGGS"), note("b", "buy bread"), note("c", "eggs only"))
        assertEquals(listOf("a"), ids(NoteSearch.search(notes, "eggs buy")))
    }

    @Test
    fun `quoted phrases must match as written`() {
        val notes = listOf(note("a", "call the dentist tomorrow"), note("b", "tomorrow call the bank"))
        assertEquals(listOf("a"), ids(NoteSearch.search(notes, "\"dentist tomorrow\"")))
        assertEquals(listOf("a", "b").toSet(), ids(NoteSearch.search(notes, "call tomorrow")).toSet())
    }

    @Test
    fun `accents are ignored`() {
        val notes = listOf(note("a", "Meet at the Café near Zürich"))
        assertEquals(listOf("a"), ids(NoteSearch.search(notes, "cafe zurich")))
    }

    @Test
    fun `earlier revisions are searched and ranked after current matches`() {
        val notes = listOf(
            note("old", "project kestrel kickoff", "project renamed", createdAt = 10),
            note("now", "kestrel launch plan", createdAt = 1),
        )
        val results = NoteSearch.search(notes, "kestrel")
        assertEquals(listOf("now", "old"), ids(results))
        assertEquals(MatchedIn.CURRENT, results[0].matchedIn)
        assertEquals(MatchedIn.EARLIER_REVISION, results[1].matchedIn)
    }

    @Test
    fun `text recovered from a failed transcription is searchable`() {
        val notes = listOf(note("a", partial = "remember the quarterly numbers"))
        assertEquals(listOf("a"), ids(NoteSearch.search(notes, "quarterly")))
    }

    @Test
    fun `pinned notes come first among equal matches`() {
        val notes = listOf(note("a", "groceries list", createdAt = 5), note("b", "groceries list", pinned = true, createdAt = 1))
        assertEquals(listOf("b", "a"), ids(NoteSearch.search(notes, "groceries")))
    }

    @Test
    fun `blank query finds nothing`() {
        assertTrue(NoteSearch.search(listOf(note("a", "x")), "   ").isEmpty())
    }

    @Test
    fun `highlight ranges line up with the original text and merge overlaps`() {
        val text = "Café cafe CAFÉS"
        val ranges = NoteSearch.matchRanges(text, NoteSearch.terms("cafe"))
        assertEquals(listOf(0..3, 5..8, 10..13), ranges)
        assertEquals(listOf("Café", "cafe", "CAFÉ"), ranges.map { text.substring(it) })
        assertEquals(listOf(0..5), NoteSearch.matchRanges("abcdef", NoteSearch.terms("abcd cdef")))
    }

    @Test
    fun `pinning is saved`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val store = NoteStore(dir)
        store.add(note("a", "x"))
        store.setPinned("a", true)
        val reopened = NoteStore(dir).note("a")!!
        assertTrue(reopened.pinned)
        assertTrue(reopened.pinnedAt != null)
        dir.deleteRecursively()
    }
}
