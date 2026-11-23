package com.voicepad.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File

/** Persists notes as JSON in app storage and audio as WAV files next to it. */
class NoteStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val indexFile = File(context.filesDir, "notes.json")
    val recordingsDir: File = File(context.filesDir, "recordings").apply { mkdirs() }

    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    fun audioFile(note: Note): File? = note.audioFileName?.let { File(recordingsDir, it) }

    fun note(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    fun add(note: Note) = mutate { it + note }

    /** Appends a revision unless the text is unchanged. */
    fun addRevision(id: String, text: String, source: RevisionSource, engine: String? = null) = mutate { list ->
        list.map { note ->
            if (note.id != id) return@map note
            val trimmed = text.trim()
            if (note.revisions.isNotEmpty() && note.currentText == trimmed) return@map note
            note.copy(revisions = note.revisions + Revision(text = trimmed, source = source, engine = engine))
        }
    }

    fun restore(id: String, revision: Revision) =
        addRevision(id, revision.text, RevisionSource.RESTORE, revision.engine)

    fun setDuration(id: String, seconds: Double) = mutate { list ->
        list.map { if (it.id == id) it.copy(durationSeconds = seconds) else it }
    }

    fun delete(id: String) {
        note(id)?.let { audioFile(it)?.delete() }
        mutate { list -> list.filterNot { it.id == id } }
    }

    @Synchronized
    private fun mutate(transform: (List<Note>) -> List<Note>) {
        _notes.update(transform)
        save(_notes.value)
    }

    private fun load(): List<Note> = try {
        if (indexFile.exists()) json.decodeFromString<List<Note>>(indexFile.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(notes: List<Note>) {
        val tmp = File(indexFile.path + ".tmp")
        tmp.writeText(json.encodeToString(notes))
        tmp.renameTo(indexFile)
    }
}
