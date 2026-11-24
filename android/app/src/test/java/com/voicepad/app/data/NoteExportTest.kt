package com.voicepad.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone
import java.util.zip.ZipInputStream

class NoteExportTest {
    @Before fun utc() { NoteExport.timeZone = TimeZone.getTimeZone("UTC") }

    private val t0 = 1_764_000_000_000L // 2025-11-24 16:00 UTC

    private fun note(id: String, created: Long, text: String, audio: String? = null) = Note(
        id = id,
        createdAt = created,
        durationSeconds = 75.0,
        audioFileName = audio,
        revisions = listOf(Revision(id = "$id-r", text = text, timestamp = created, source = RevisionSource.TRANSCRIPTION, engine = "Parakeet v3", language = "en")),
    )

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes()
            }
        }
        return out
    }

    @Test fun zipHasJsonAndMarkdownOldestFirst() {
        val notes = listOf(note("bbbbbbbbbb", t0 + 60_000, "Second"), note("aaaaaaaaaa", t0, "First"))
        val bytes = ByteArrayOutputStream().also { NoteExport.writeZip(it, notes, includeAudio = false, now = t0) { null } }.toByteArray()
        val files = unzip(bytes)
        assertEquals(setOf("notes.json", "notes.md"), files.keys)

        val json = Json.parseToJsonElement(files.getValue("notes.json").decodeToString()).jsonObject
        assertEquals("voicepad-notes", json["format"]!!.jsonPrimitive.content)
        assertEquals(1, json["version"]!!.jsonPrimitive.int)
        val exported = json["notes"]!!.jsonArray
        assertEquals(listOf("aaaaaaaaaa", "bbbbbbbbbb"), exported.map { it.jsonObject["note"]!!.jsonObject["id"]!!.jsonPrimitive.content })

        val md = files.getValue("notes.md").decodeToString()
        assertTrue(md.startsWith("# VoicePad notes\n\nExported 2025-11-24 16:00 · 2 notes"))
        assertTrue(md.indexOf("First") < md.indexOf("Second"))
        assertTrue(md.contains("## 2025-11-24 16:00\n\n*1:15 · language: en · Parakeet v3*\n\nFirst\n"))
    }

    @Test fun recordingsGoInWhenAskedAndAreLinked() {
        val dir = File(System.getProperty("java.io.tmpdir"), "export-test").apply { mkdirs() }
        val wav = File(dir, "a.wav").apply { writeBytes(ByteArray(100) { it.toByte() }) }
        val notes = listOf(note("aaaaaaaaaa", t0, "With audio", "a.wav"), note("cccccccccc", t0 + 1, "Audio missing", "gone.wav"))
        val find = { n: Note -> n.audioFileName?.let { File(dir, it) } }

        val withAudio = unzip(ByteArrayOutputStream().also { NoteExport.writeZip(it, notes, true, t0, find) }.toByteArray())
        val path = "audio/2025-11-24_16-00-00_aaaaaaaa.wav"
        assertEquals(setOf("notes.json", "notes.md", path), withAudio.keys)
        assertTrue(withAudio.getValue(path).contentEquals(wav.readBytes()))
        assertTrue(withAudio.getValue("notes.md").decodeToString().contains("audio: [$path]($path)"))
        assertTrue(withAudio.getValue("notes.json").decodeToString().contains("\"audioPath\": \"$path\""))

        val without = unzip(ByteArrayOutputStream().also { NoteExport.writeZip(it, notes, false, t0, find) }.toByteArray())
        assertFalse(without.keys.any { it.startsWith("audio/") })
    }

    @Test fun markdownShowsTranslationAndEarlierVersions() {
        val n = Note(
            id = "x", createdAt = t0,
            revisions = listOf(
                Revision(id = "r1", text = "你好世界", timestamp = t0, source = RevisionSource.TRANSCRIPTION, language = "zh"),
                Revision(id = "r2", text = "你好，世界", timestamp = t0 + 60_000, source = RevisionSource.EDIT),
            ),
            translation = NoteTranslation("r2", TranslationStatus.DONE, "zh", english = "Hello, world", romanization = "nǐ hǎo, shì jiè"),
        )
        val md = NoteExport.markdown(listOf(NoteExport.ExportedNote(n)), t0)
        assertTrue(md.contains("你好，世界\n\n> nǐ hǎo, shì jiè\n\n**English:** Hello, world\n"))
        assertTrue(md.contains("<details><summary>Earlier versions (1)</summary>\n\n- 2025-11-24 16:00 · Transcribed: 你好世界\n"))
    }
}
