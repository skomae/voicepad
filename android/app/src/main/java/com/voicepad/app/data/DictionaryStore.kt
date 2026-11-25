package com.voicepad.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The user's dictionaries, saved as JSON next to the notes. There is always
 * at least one ("My words"), so saving words from an edit has somewhere to go.
 */
class DictionaryStore(baseDir: File) {
    @Serializable
    private data class Saved(val dictionaries: List<UserDictionary>, val lastUsedId: String? = null)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val file = File(baseDir, "dictionaries.json")

    private var saved = load()
    private val _dictionaries = MutableStateFlow(saved.dictionaries)
    val dictionaries: StateFlow<List<UserDictionary>> = _dictionaries.asStateFlow()

    /** The dictionary words were last saved into, offered first next time. */
    val lastUsedId: String? get() = saved.lastUsedId?.takeIf { id -> saved.dictionaries.any { it.id == id } }

    /** Every word in the enabled dictionaries. */
    fun activeWords(): List<String> =
        saved.dictionaries.filter { it.enabled }.flatMap { it.words }.distinct()

    /** Every word in any dictionary, enabled or not. */
    fun allWords(): Set<String> = saved.dictionaries.flatMap { it.words }.toSet()

    fun create(name: String): UserDictionary {
        val dictionary = UserDictionary(name = name.trim().ifEmpty { "New dictionary" })
        mutate { it.copy(dictionaries = it.dictionaries + dictionary) }
        return dictionary
    }

    fun rename(id: String, name: String) = edit(id) { d -> name.trim().takeIf { it.isNotEmpty() }?.let { d.copy(name = it) } ?: d }

    fun setEnabled(id: String, enabled: Boolean) = edit(id) { it.copy(enabled = enabled) }

    /** Removes the dictionary; the last one left is emptied instead, so there's always one. */
    fun delete(id: String) = mutate { s ->
        val rest = s.dictionaries.filterNot { it.id == id }
        s.copy(dictionaries = rest.ifEmpty { listOf(defaultDictionary()) })
    }

    /** Adds [words] to dictionary [id] (skipping ones it already has) and remembers it for next time. */
    fun addWords(id: String, words: Collection<String>) = mutate { s ->
        s.copy(
            dictionaries = s.dictionaries.map { d -> if (d.id == id) d.withWords(words) else d },
            lastUsedId = id,
        )
    }

    fun removeWords(id: String, words: Collection<String>) = edit(id) { d -> d.copy(words = d.words - words.toSet()) }

    /** Replaces one entry with its edited spelling, keeping its place in the list. */
    fun replaceWord(id: String, old: String, new: String) = edit(id) { d ->
        val cleaned = DictionaryWords.clean(new) ?: return@edit d.copy(words = d.words - old)
        if (cleaned != old && cleaned in d.words) d.copy(words = d.words - old)
        else d.copy(words = d.words.map { if (it == old) cleaned else it })
    }

    /** Moves [words] from dictionary [from] to dictionary [to]. */
    fun moveWords(words: Collection<String>, from: String, to: String) {
        if (from == to) return
        val set = words.toSet()
        mutate { s ->
            s.copy(dictionaries = s.dictionaries.map { d ->
                when (d.id) {
                    from -> d.copy(words = d.words - set)
                    to -> d.withWords(words)
                    else -> d
                }
            })
        }
    }

    private fun UserDictionary.withWords(new: Collection<String>): UserDictionary {
        val added = new.mapNotNull(DictionaryWords::clean).filter { it !in words }.distinct()
        return if (added.isEmpty()) this else copy(words = words + added)
    }

    private fun edit(id: String, transform: (UserDictionary) -> UserDictionary) = mutate { s ->
        s.copy(dictionaries = s.dictionaries.map { if (it.id == id) transform(it) else it })
    }

    @Synchronized
    private fun mutate(transform: (Saved) -> Saved) {
        val updated = transform(saved)
        if (updated == saved) return
        save(updated)
        saved = updated
        _dictionaries.value = updated.dictionaries
    }

    private fun load(): Saved {
        for (f in listOf(file, File(file.path + ".bak"))) {
            if (!f.exists()) continue
            try {
                val loaded = json.decodeFromString<Saved>(f.readText())
                if (loaded.dictionaries.isNotEmpty()) return loaded
            } catch (_: Exception) {
                // Corrupt: try the backup rather than losing the words.
            }
        }
        return Saved(listOf(defaultDictionary()))
    }

    private fun save(state: Saved) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { out ->
            out.write(json.encodeToString(state).toByteArray())
            out.fd.sync()
        }
        if (file.exists()) file.copyTo(File(file.path + ".bak"), overwrite = true)
        check(tmp.renameTo(file)) { "Could not save dictionaries." }
    }

    private fun defaultDictionary() = UserDictionary(name = "My words")
}
