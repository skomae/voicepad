package com.voicepad.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports notes to a ZIP of open formats, readable without VoicePad:
 *
 * - `notes.json`: every note with everything VoicePad knows about it —
 *   transcript revisions, translations, language, model, timestamps, pinned
 *   and archived state (the app's own data model, documented by its field
 *   names), plus the audio file's path inside the ZIP.
 * - `notes.md`: the same notes as readable Markdown, oldest first.
 * - `audio/…`: each recording as WAV (or AAC .m4a once archived), if asked for.
 *
 * Pure Kotlin (java.util.zip) so it can be unit-tested.
 */
object NoteExport {
    const val FORMAT_VERSION = 1

    @Serializable
    data class Export(
        val format: String = "voicepad-notes",
        val version: Int = FORMAT_VERSION,
        val exportedAt: Long,
        val notes: List<ExportedNote>,
    )

    @Serializable
    data class ExportedNote(
        val note: Note,
        /** Path of the recording inside the ZIP, when audio was included and found. */
        val audioPath: String? = null,
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** Notes in the order they're exported: oldest first. */
    fun ordered(notes: List<Note>) = notes.sortedBy { it.createdAt }

    /**
     * Writes the ZIP to [out]. [audioFile] finds a note's recording;
     * [includeAudio] decides whether recordings go in. Returns how many
     * notes were written.
     */
    fun writeZip(
        out: OutputStream,
        notes: List<Note>,
        includeAudio: Boolean,
        now: Long = System.currentTimeMillis(),
        audioFile: (Note) -> File?,
    ): Int {
        val list = ordered(notes)
        ZipOutputStream(out.buffered()).use { zip ->
            val exported = list.map { note ->
                val file = if (includeAudio) audioFile(note)?.takeIf { it.isFile } else null
                val path = file?.let { "audio/${baseName(note)}.${it.extension.ifEmpty { "wav" }}" }
                if (file != null && path != null) {
                    zip.putNextEntry(ZipEntry(path).apply { time = note.createdAt })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                ExportedNote(note, path)
            }
            zip.putNextEntry(ZipEntry("notes.json"))
            zip.write(json.encodeToString(Export.serializer(), Export(exportedAt = now, notes = exported)).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("notes.md"))
            zip.write(markdown(exported, now).toByteArray())
            zip.closeEntry()
        }
        return list.size
    }

    /** A file name for [note]'s recording: its date and time, plus a short id so names never clash. */
    fun baseName(note: Note): String = stamp(note.createdAt, "yyyy-MM-dd_HH-mm-ss") + "_" + note.id.take(8)

    fun markdown(notes: List<ExportedNote>, now: Long = System.currentTimeMillis()): String = buildString {
        append("# VoicePad notes\n\n")
        append("Exported ${stamp(now, "yyyy-MM-dd HH:mm")} · ${notes.size} note${if (notes.size == 1) "" else "s"}\n")
        for ((note, audio) in notes) {
            append("\n---\n\n")
            append("## ").append(stamp(note.createdAt, "yyyy-MM-dd HH:mm"))
            if (note.pinned) append(" · pinned")
            if (note.archived) append(" · archived")
            append("\n\n")
            val details = listOfNotNull(
                note.durationSeconds.takeIf { it > 0 }?.let { duration(it) },
                note.revisions.lastOrNull()?.language?.let { "language: $it" },
                note.revisions.lastOrNull()?.engine,
                audio?.let { "audio: [$it]($it)" },
            )
            if (details.isNotEmpty()) append("*").append(details.joinToString(" · ")).append("*\n\n")
            val text = note.currentText.ifBlank { note.partialText.orEmpty() }
            append(text.ifBlank { "(no text)" }.trim()).append("\n")
            note.currentTranslation?.takeIf { it.status == TranslationStatus.DONE }?.let { t ->
                t.romanization?.let { append("\n> ").append(it.replace("\n", "\n> ")).append("\n") }
                t.english?.let { append("\n**English:** ").append(it.trim()).append("\n") }
            }
            note.currentRewrite?.let { rw ->
                append("\n**").append(rw.prompt ?: "Rewritten").append(":** ").append(rw.text.trim()).append("\n")
            }
            if (note.revisions.size > 1) {
                append("\n<details><summary>Earlier versions (${note.revisions.size - 1})</summary>\n\n")
                note.revisions.dropLast(1).asReversed().forEach { r ->
                    append("- ").append(stamp(r.timestamp, "yyyy-MM-dd HH:mm")).append(" · ").append(r.source.label)
                        .append(": ").append(r.text.replace("\n", " ").trim()).append("\n")
                }
                append("\n</details>\n")
            }
        }
    }

    private fun duration(seconds: Double): String {
        val s = seconds.toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Dates in the phone's time zone (UTC in tests). */
    var timeZone: TimeZone = TimeZone.getDefault()

    private fun stamp(time: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = this@NoteExport.timeZone }.format(Date(time))

    /** Suggested file name for the export. */
    fun fileName(now: Long = System.currentTimeMillis()) = "voicepad-notes-${stamp(now, "yyyy-MM-dd")}.zip"
}
