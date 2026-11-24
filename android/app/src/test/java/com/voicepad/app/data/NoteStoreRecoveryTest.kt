package com.voicepad.app.data

import com.voicepad.app.audio.WavFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NoteStoreRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = NoteStore(tmp.root)

    /** Writes a WAV the way the recorder does before its first checkpoint: header still says 0 bytes. */
    private fun crashedWav(store: NoteStore, name: String, seconds: Double): File {
        val file = File(store.recordingsDir, name)
        val samples = (seconds * WavFile.SAMPLE_RATE).toInt()
        val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { i -> pcm.putShort(((i % 100) * 100).toShort()) }
        file.writeBytes(WavFile.header(0) + pcm.array() + byteArrayOf(7)) // plus a torn half-sample
        return file
    }

    private fun headerDataSize(file: File): Int =
        ByteBuffer.wrap(file.readBytes(), 40, 4).order(ByteOrder.LITTLE_ENDIAN).int

    @Test
    fun `recording interrupted by a crash is repaired and queued`() {
        val s = store()
        val wav = crashedWav(s, "a.wav", 3.0)
        s.add(Note(id = "a", audioFileName = "a.wav", status = TranscriptionStatus.RECORDING, partialText = "hello"))

        val reopened = store()
        reopened.recoverAfterRestart()

        val note = reopened.note("a")!!
        assertEquals(TranscriptionStatus.PENDING, note.status)
        assertEquals(3.0, note.durationSeconds, 0.001)
        assertEquals("hello", note.partialText)
        assertTrue(note.needsTranscription)
        assertEquals(3 * WavFile.SAMPLE_RATE * 2, headerDataSize(wav))
        assertEquals(3 * WavFile.SAMPLE_RATE, WavFile.read(wav).size)
    }

    @Test
    fun `crash before anything was said drops the empty recording`() {
        val s = store()
        val wav = crashedWav(s, "b.wav", 0.3)
        s.add(Note(id = "b", audioFileName = "b.wav", status = TranscriptionStatus.RECORDING))

        s.recoverAfterRestart()

        assertNull(s.note("b"))
        assertFalse(wav.exists())
    }

    @Test
    fun `crash during transcription marks it failed instead of retrying forever`() {
        val s = store()
        crashedWav(s, "c.wav", 2.0)
        crashedWav(s, "d.wav", 2.0)
        s.add(Note(id = "c", audioFileName = "c.wav", status = TranscriptionStatus.IN_PROGRESS, attempts = 1))
        s.add(
            Note(
                id = "d", audioFileName = "d.wav", status = TranscriptionStatus.IN_PROGRESS, attempts = 2,
                revisions = listOf(Revision(text = "kept", source = RevisionSource.TRANSCRIPTION)),
            )
        )

        s.recoverAfterRestart()

        val c = s.note("c")!!
        assertEquals(TranscriptionStatus.FAILED, c.status)
        assertEquals(1, c.attempts)
        assertNotNull(c.lastError)
        assertTrue(c.needsTranscription)
        // A note that already had text keeps it as current.
        assertEquals(TranscriptionStatus.DONE, s.note("d")!!.status)
        assertEquals("kept", s.note("d")!!.currentText)
    }

    @Test
    fun `notes saved before statuses existed get queued if they have audio but no text`() {
        val s = store()
        crashedWav(s, "e.wav", 2.0)
        File(tmp.root, "notes.json").writeText(
            """[{"id":"e","createdAt":1,"audioFileName":"e.wav","revisions":[]},
               {"id":"f","createdAt":2,"revisions":[{"text":"hi","source":"TRANSCRIPTION"}]}]"""
        )

        val reopened = store()
        reopened.recoverAfterRestart()

        assertEquals(TranscriptionStatus.PENDING, reopened.note("e")!!.status)
        assertEquals(TranscriptionStatus.DONE, reopened.note("f")!!.status)
    }

    @Test
    fun `corrupt index falls back to the backup instead of losing every note`() {
        val s = store()
        s.add(Note(id = "g", audioFileName = "g.wav", status = TranscriptionStatus.PENDING))
        s.add(Note(id = "h", audioFileName = "h.wav", status = TranscriptionStatus.PENDING))
        File(tmp.root, "notes.json").writeText("{ truncated")

        val reopened = store()

        assertNotNull(reopened.note("g"))
    }

    @Test
    fun `status changes survive a restart`() {
        val s = store()
        s.add(Note(id = "i", audioFileName = "i.wav", status = TranscriptionStatus.RECORDING))
        s.update("i") { it.copy(status = TranscriptionStatus.FAILED, lastError = "model crashed", attempts = 3) }

        val note = store().note("i")!!
        assertEquals(TranscriptionStatus.FAILED, note.status)
        assertEquals("model crashed", note.lastError)
        assertEquals(3, note.attempts)
    }

    @Test
    fun `delete removes the audio file`() {
        val s = store()
        val wav = crashedWav(s, "j.wav", 1.5)
        s.add(Note(id = "j", audioFileName = "j.wav"))

        s.delete("j")

        assertNull(s.note("j"))
        assertFalse(wav.exists())
    }
}
