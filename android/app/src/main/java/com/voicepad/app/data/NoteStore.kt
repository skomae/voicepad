package com.voicepad.app.data

import com.voicepad.app.audio.WavFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists notes as JSON and audio as WAV files under [baseDir].
 *
 * Every state change is written to disk immediately (atomically), so after a
 * crash [recoverAfterRestart] can tell exactly which recordings still need
 * transcription.
 */
class NoteStore(baseDir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val indexFile = File(baseDir, "notes.json")
    val recordingsDir: File = File(baseDir, "recordings").apply { mkdirs() }

    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    fun audioFile(note: Note): File? = note.audioFileName?.let { File(recordingsDir, it) }

    fun note(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    fun add(note: Note) = mutate { it + note }

    /** Applies [transform] to one note and saves. */
    fun update(id: String, transform: (Note) -> Note) = mutate { list ->
        list.map { if (it.id == id) transform(it) else it }
    }

    /** Appends a revision unless the text is unchanged. */
    fun addRevision(id: String, text: String, source: RevisionSource, engine: String? = null) =
        update(id) { note -> note.withRevision(text, source, engine) }

    fun restore(id: String, revision: Revision) =
        addRevision(id, revision.text, RevisionSource.RESTORE, revision.engine)

    fun setPinned(id: String, pinned: Boolean) = update(id) {
        it.copy(pinned = pinned, pinnedAt = if (pinned) System.currentTimeMillis() else null)
    }

    /** Deletes the note and its audio file. */
    fun delete(id: String) {
        note(id)?.let { audioFile(it)?.delete() }
        mutate { list -> list.filterNot { it.id == id } }
    }

    /**
     * Run once at startup, before anything records or transcribes. Repairs
     * whatever an unexpected exit left behind:
     * - a note still RECORDING: the app died mid-recording, so fix the WAV
     *   header from what reached disk and queue it for transcription;
     * - a note still IN_PROGRESS: the app died during a model run (possibly
     *   the model crashed it), so mark it FAILED rather than silently
     *   retrying forever;
     * - an older note saved before statuses existed, with audio but no text:
     *   queue it.
     */
    fun recoverAfterRestart() = mutate { list ->
        list.mapNotNull { note ->
            val audio = audioFile(note)
            when {
                note.status == TranscriptionStatus.RECORDING -> {
                    val duration = audio?.let { WavFile.repair(it) } ?: 0.0
                    if (duration < MIN_SECONDS && note.partialText.isNullOrBlank()) {
                        audio?.delete()
                        null // nothing was said before the app died
                    } else {
                        note.copy(status = TranscriptionStatus.PENDING, durationSeconds = duration)
                    }
                }
                note.status == TranscriptionStatus.IN_PROGRESS -> note.copy(
                    status = if (note.revisions.isEmpty()) TranscriptionStatus.FAILED else TranscriptionStatus.DONE,
                    lastError = "The app closed while transcribing this recording.",
                )
                note.status == TranscriptionStatus.DONE && note.revisions.isEmpty() && audio?.exists() == true ->
                    note.copy(status = TranscriptionStatus.PENDING)
                else -> note
            }
        }
    }

    @Synchronized
    private fun mutate(transform: (List<Note>) -> List<Note>) {
        val updated = transform(_notes.value)
        save(updated)
        _notes.value = updated
    }

    private fun load(): List<Note> {
        for (file in listOf(indexFile, File(indexFile.path + ".bak"))) {
            if (!file.exists()) continue
            try {
                return json.decodeFromString<List<Note>>(file.readText())
            } catch (_: Exception) {
                // Corrupt: fall through to the backup rather than losing every note.
            }
        }
        return emptyList()
    }

    private fun save(notes: List<Note>) {
        val tmp = File(indexFile.path + ".tmp")
        tmp.outputStream().use { out ->
            out.write(json.encodeToString(notes).toByteArray())
            out.fd.sync()
        }
        if (indexFile.exists()) indexFile.copyTo(File(indexFile.path + ".bak"), overwrite = true)
        check(tmp.renameTo(indexFile)) { "Could not save notes." }
    }

    companion object {
        const val MIN_SECONDS = 1.0
    }
}

fun Note.withRevision(text: String, source: RevisionSource, engine: String?): Note {
    val trimmed = text.trim()
    if (revisions.isNotEmpty() && currentText == trimmed) return this
    return copy(revisions = revisions + Revision(text = trimmed, source = source, engine = engine))
}
