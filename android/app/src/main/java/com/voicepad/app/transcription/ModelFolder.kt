package com.voicepad.app.transcription

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * An optional folder the user picks (e.g. Download/VoicePad models) that
 * keeps a copy of every downloaded model outside the app's own storage.
 *
 * Android deletes the app's storage on uninstall; a reinstall (e.g. an APK
 * signed with a different key) would otherwise download every model again.
 * With the folder chosen, installed models are copied into it, and a model
 * missing from the app is copied back from it instead of downloaded: no
 * network, and no slow unpacking of the .tar.bz2 on the phone.
 *
 * Models are mirrored by their path inside the app's files directory
 * (`models/<name>/…`, `voices/<name>/…`, `llm/<file>`), and a model directory
 * only counts once its `.complete` marker is there, which is copied last.
 * Google ML Kit's translation packs live outside the files directory and are
 * mirrored too (see [MlKitTranslationModels]).
 *
 * The folder is navigated by document URI (tree URI + relative path), not by
 * listing it: some providers — ColorOS's, at least — answer child queries
 * with nothing at all, which made every listing look empty and every copy
 * land in a fresh `models (1)`, `models (2)`… directory. Since listing can't
 * be relied on, what's in the folder is also recorded in [INDEX], written
 * after each copy; restoring reads it first and only falls back to listing
 * for folders written by older versions.
 */
class ModelFolder private constructor(private val context: Context) {
    data class State(
        /** The chosen folder's name, or null when none is set. */
        val folderName: String? = null,
        /** True when the folder was chosen but access to it was lost (e.g. it was deleted). */
        val unavailable: Boolean = false,
        /** What's being copied right now, for settings. */
        val busy: String? = null,
        val error: String? = null,
    )

    /** What the folder holds: whole directories and single files, with their sizes. */
    private class Index(
        val dirs: MutableSet<String> = LinkedHashSet(),
        val files: MutableMap<String, Long> = LinkedHashMap(),
    ) {
        fun knows(relPath: String) = dirs.contains(relPath) || files.containsKey(relPath)

        /** The files of the directory at [relPath], as (path in the folder, size). */
        fun filesUnder(relPath: String): List<Pair<String, Long>> =
            files.entries.filter { it.key.startsWith("$relPath/") }.map { it.key to it.value }

        fun serialize(): String = buildString {
            dirs.forEach { append("d ").append(it).append('\n') }
            files.forEach { (path, size) -> append("f ").append(path).append(' ').append(size).append('\n') }
        }

        companion object {
            fun parse(text: String): Index = Index().also { index ->
                text.lineSequence().forEach { line ->
                    when {
                        line.startsWith("d ") -> index.dirs += line.removePrefix("d ").trim()
                        line.startsWith("f ") -> {
                            val rest = line.removePrefix("f ").trim()
                            val path = rest.substringBeforeLast(' ')
                            val size = rest.substringAfterLast(' ').toLongOrNull() ?: return@forEach
                            index.files[path] = size
                        }
                    }
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: ModelFolder? = null
        fun get(context: Context): ModelFolder =
            instance ?: synchronized(this) { instance ?: ModelFolder(context.applicationContext).also { instance = it } }

        private const val MARKER = ".complete"
        /** The folder's own record of what's in it, at its root (listing can't be trusted). */
        private const val INDEX = ".voicepad-index.txt"
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refreshState()
    }

    private val storedUri: Uri? get() = prefs.getString("modelFolder", null)?.let(Uri::parse)

    /**
     * The folder actually used. Some document providers address documents by
     * id, not by path — the Downloads (MediaStore) provider returns ids like
     * `msd:143509` — and VoicePad navigates by path inside the folder, so such
     * a tree can't be used. When the user picked one that way (the picker's
     * "Download" shortcut does), fall back to a granted external-storage tree.
     */
    private val uri: Uri? get() {
        val stored = storedUri
        if (stored != null && isPathBased(stored)) return stored
        return context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission }
            .map { it.uri }
            .firstOrNull(::isPathBased)
            ?: stored
    }

    /** The external-storage provider is the one whose document ids are real paths. */
    private fun isPathBased(tree: Uri): Boolean = tree.authority == "com.android.externalstorage.documents"

    /** The document at [relPath] in the folder, addressed directly (never by listing). */
    private fun documentUri(relPath: String): Uri? {
        val tree = uri ?: return null
        // getTreeDocumentId, not getDocumentId: the stored URI is the bare tree
        // (`…/tree/primary%3ADownload%2FVoicepad`), and getDocumentId throws on it.
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: runCatching { DocumentsContract.getDocumentId(tree) }.getOrNull()
            ?: return null
        val docId = if (relPath.isEmpty()) rootId else "$rootId/$relPath"
        return runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, docId) }.getOrNull()
    }

    private fun document(relPath: String): DocumentFile? {
        val u = documentUri(relPath) ?: return null
        return runCatching { DocumentFile.fromSingleUri(context, u) }.getOrNull()
            ?.takeIf { runCatching { it.exists() }.getOrDefault(false) }
    }

    /** The folder's root, when it's reachable. Writes to it fail (and are reported) if the grant is read-only. */
    private fun root(): DocumentFile? = document("")

    /** The folder's root as a tree, for the listing fallback of folders written by older versions. */
    private fun tree(): DocumentFile? {
        val u = uri ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, u) }.getOrNull()
    }

    private fun refreshState() {
        val u = uri
        val root = root()
        _state.update {
            it.copy(
                folderName = u?.let { root?.name ?: it.lastPathSegment?.substringAfterLast(':') ?: "chosen folder" },
                unavailable = u != null && root == null,
            )
        }
    }

    /** Uses [tree] (from the folder picker) from now on, and copies the models already installed into it. */
    fun choose(tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit { putString("modelFolder", tree.toString()) }
        refreshState()
        scope.launch { saveAll() }
    }

    /** Stops using the folder (its copies stay where they are). */
    fun forget() {
        uri?.let { u -> runCatching { context.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        prefs.edit { remove("modelFolder") }
        _state.value = State()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Copies the model at [relPath] (a directory or a single file) back from
     * the folder into [target]. Returns true if it was there, complete, and
     * copied.
     *
     * [writeMarker] adds the `.complete` marker to a restored directory;
     * callers that hand the directory to someone else's format check (ML Kit
     * hashes its model folders) pass false.
     */
    suspend fun restore(relPath: String, target: File, writeMarker: Boolean = true): Boolean = mutex.withLock {
        val index = readIndex()
        if (index == null || !index.knows(relPath)) return@withLock restoreByListing(relPath, target, writeMarker)
        try {
            _state.update { it.copy(busy = "Restoring ${target.name} from the folder…") }
            if (index.dirs.contains(relPath)) {
                val files = index.filesUnder(relPath)
                if (files.isEmpty()) return@withLock restoreByListing(relPath, target, writeMarker)
                target.deleteRecursively()
                target.mkdirs()
                for ((path, _) in files) {
                    copyDown(path, File(target, path.removePrefix("$relPath/")))
                }
                // The marker last: an interrupted copy isn't mistaken for a whole model.
                if (writeMarker) File(target, MARKER).writeText("restored")
            } else {
                copyDown(relPath, File(target.path + ".part"))
                val part = File(target.path + ".part")
                check(part.length() > 0) { "Incomplete copy." }
                check(part.renameTo(target)) { "Couldn't save ${target.name}." }
            }
            true
        } catch (e: Throwable) {
            // A damaged copy in the folder: fall back to downloading.
            if (target.isDirectory) target.deleteRecursively() else { target.delete(); File(target.path + ".part").delete() }
            _state.update { it.copy(error = "Couldn't restore ${target.name} from the folder: ${e.message}") }
            false
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /** Restores a directory the index doesn't know about, by listing the folder (works where listing does). */
    private fun restoreByListing(relPath: String, target: File, writeMarker: Boolean): Boolean {
        val root = tree() ?: return false
        val source = find(root, relPath) ?: return false
        if (!source.isDirectory) return false
        if (source.findFile(MARKER) == null) return false
        _state.update { it.copy(busy = "Restoring ${target.name} from the folder…") }
        return try {
            target.deleteRecursively()
            target.mkdirs()
            copyDown(source, target, skipMarker = true)
            if (writeMarker) File(target, MARKER).writeText("restored")
            true
        } catch (e: Throwable) {
            target.deleteRecursively()
            _state.update { it.copy(error = "Couldn't restore ${target.name} from the folder: ${e.message}") }
            false
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /** Creates a directory in [parentDir] (a DocumentFile can't: the single-document ones throw). */
    private fun createDirectory(parentDir: Uri, name: String): DocumentFile? {
        val created = runCatching {
            DocumentsContract.createDocument(context.contentResolver, parentDir, DocumentsContract.Document.MIME_TYPE_DIR, name)
        }.getOrNull() ?: return null
        return DocumentFile.fromSingleUri(context, created)
    }

    /** Creates a file in [parentDir]. */
    private fun createFile(parentDir: Uri, mime: String, name: String): DocumentFile? {
        val created = runCatching {
            DocumentsContract.createDocument(context.contentResolver, parentDir, mime, name)
        }.getOrNull() ?: return null
        return DocumentFile.fromSingleUri(context, created)
    }

    /** Copies a freshly installed model into the folder, in the background. */
    fun saveLater(relPath: String, source: File) {
        if (uri == null) return
        scope.launch { mutex.withLock { save(relPath, source) } }
    }

    /** Copies a small text file (e.g. the list of ML Kit model hashes) into the folder, in the background. */
    fun saveTextLater(relPath: String, text: String) {
        if (uri == null) return
        scope.launch {
            mutex.withLock {
                try {
                    writeText(relPath, text)
                } catch (e: Throwable) {
                    _state.update { it.copy(error = "Couldn't copy ${relPath.substringAfterLast('/')} to the folder: ${e.message}") }
                }
            }
        }
    }

    /** False when no folder is chosen (or it can't be reached). */
    fun isActive(): Boolean = root() != null

    /** Names of the directories kept under [relPath], whose copies are whole (they carry the marker). */
    suspend fun listDirs(relPath: String): List<String> = mutex.withLock {
        val index = readIndex()
        if (index != null) {
            val prefix = if (relPath.isEmpty()) "" else "$relPath/"
            return@withLock index.dirs
                .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .map { it.removePrefix(prefix) }
                .sorted()
        }
        // Folder written by an older version, or by hand: list it.
        val root = tree() ?: return@withLock emptyList()
        val at = if (relPath.isEmpty()) root else find(root, relPath) ?: return@withLock emptyList()
        at.listFiles()
            .filter { it.isDirectory && it.findFile(MARKER) != null }
            .mapNotNull { it.name }
            .sorted()
    }

    /** The text of the file at [relPath], or null when it isn't there. */
    suspend fun readText(relPath: String): String? = mutex.withLock {
        val file = document(relPath) ?: return@withLock null
        if (file.isDirectory) return@withLock null
        runCatching {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    /**
     * Copies everything installed (speech models, voices, LLM files and ML
     * Kit's translation packs) into the folder. Used when the folder is
     * chosen and when the app starts, so nothing downloaded earlier is missed.
     */
    suspend fun saveAll() {
        saveAllInstalled()
        MlKitTranslationModels.saveAll(context)
    }

    /** Every installed model: model and voice directories with their marker, and single-file models. */
    private suspend fun saveAllInstalled() = mutex.withLock {
        val files = context.filesDir
        for (top in listOf("models", "voices")) {
            File(files, top).listFiles()?.forEach { entry ->
                when {
                    entry.isDirectory && File(entry, MARKER).exists() -> save("$top/${entry.name}", entry)
                    entry.isDirectory && entry.name == "speaker" -> entry.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
                        ?.forEach { save("$top/${entry.name}/${it.name}", it) } // e.g. models/speaker/*.onnx
                }
            }
        }
        // LLM: one .gguf per choice, with a .complete file beside it.
        File(files, "llm").listFiles()
            ?.filter { it.isFile && File(it.path + ".complete").exists() }
            ?.forEach { save("llm/${it.name}", it) }
    }

    private fun save(relPath: String, source: File) {
        val root = root() ?: return
        if (!source.exists()) return
        try {
            _state.update { it.copy(busy = "Copying ${source.name} to the folder…") }
            val parentPath = relPath.substringBeforeLast('/', "")
            val index = readIndex() ?: Index()
            // Already copied in full (possibly by an older version): only the index needs filling in.
            if (source.isDirectory && document("$relPath/$MARKER") != null || !source.isDirectory && document(relPath)?.length() == source.length()) {
                if (!index.knows(relPath)) {
                    if (source.isDirectory) index.dirs += relPath else index.files[relPath] = source.length()
                    recordFiles(relPath, source, index)
                    writeIndex(index)
                }
                return
            }
            val parent = if (parentPath.isEmpty()) root else mkdirs(parentPath)
            val name = relPath.substringAfterLast('/')
            if (source.isDirectory) {
                document(relPath)?.delete()
                val dir = createDirectory(parent.uri, name) ?: error("Couldn't create $name.")
                copyUp(source, dir)
                writeFile(dir, MARKER) { it.write("complete".toByteArray()) }
                index.dirs += relPath
            } else {
                document(relPath)?.delete()
                document("$relPath.part")?.delete()
                val part = createFile(parent.uri, "application/octet-stream", "$name.part") ?: error("Couldn't create $name.")
                context.contentResolver.openOutputStream(part.uri)!!.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 16) } }
                check(part.renameTo(name)) { "Couldn't name $name." }
                index.files[relPath] = source.length()
            }
            recordFiles(relPath, source, index)
            writeIndex(index)
        } catch (e: Throwable) {
            _state.update { it.copy(error = "Couldn't copy ${source.name} to the folder: ${e.message}") }
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /** Notes the files [source] has (recursively), so a restore knows what to copy back. */
    private fun recordFiles(relPath: String, source: File, index: Index) {
        if (!source.isDirectory) {
            index.files[relPath] = source.length()
            return
        }
        source.walkTopDown().filter { it.isFile && it.name != MARKER && !it.name.endsWith(".part") }.forEach {
            index.files["$relPath/${it.relativeTo(source).path}"] = it.length()
        }
    }

    private fun readIndex(): Index? {
        val file = document(INDEX) ?: return null
        val text = runCatching {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return null
        return Index.parse(text)
    }

    private fun writeIndex(index: Index) = writeText(INDEX, index.serialize())

    /** Creates or replaces the file at [relPath] with [text]. */
    private fun writeText(relPath: String, text: String) {
        val root = root() ?: return
        val parentPath = relPath.substringBeforeLast('/', "")
        val name = relPath.substringAfterLast('/')
        val existing = document(relPath)
        if (existing != null) {
            context.contentResolver.openOutputStream(existing.uri, "wt")?.use { it.write(text.toByteArray()) }
            return
        }
        val parent = if (parentPath.isEmpty()) root else mkdirs(parentPath)
        writeFile(parent, name) { it.write(text.toByteArray()) }
    }

    /** The document at [relPath], creating the directories on the way. */
    private fun mkdirs(relPath: String): DocumentFile {
        var at = document("") ?: error("The folder is no longer reachable.")
        var path = ""
        for (part in relPath.split('/')) {
            path = if (path.isEmpty()) part else "$path/$part"
            at = document(path)?.takeIf { it.isDirectory }
                ?: at.createDirectory(part)
                ?: error("Couldn't create $part.")
        }
        return at
    }

    /** Copies one file out of the folder, by its path in it. */
    private fun copyDown(relPath: String, target: File) {
        val source = document(relPath) ?: error("$relPath is missing from the folder.")
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(source.uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
    }

    private fun find(root: DocumentFile, relPath: String): DocumentFile? {
        var at: DocumentFile = root
        for (part in relPath.split('/')) at = at.findFile(part) ?: return null
        return at
    }

    private fun copyUp(source: File, dir: DocumentFile) {
        source.listFiles()?.forEach { f ->
            if (f.name == MARKER || f.name.endsWith(".part")) return@forEach
            if (f.isDirectory) copyUp(f, createDirectory(dir.uri, f.name) ?: error("Couldn't create ${f.name}."))
            else writeFile(dir, f.name) { out -> f.inputStream().use { it.copyTo(out, 1 shl 16) } }
        }
    }

    private fun copyDown(source: DocumentFile, dir: File, skipMarker: Boolean) {
        source.listFiles().forEach { f ->
            val name = f.name ?: return@forEach
            if (skipMarker && name == MARKER) return@forEach
            if (f.isDirectory) {
                copyDown(f, File(dir, name).apply { mkdirs() }, skipMarker = false)
            } else {
                context.contentResolver.openInputStream(f.uri)!!.use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it, 1 shl 16) }
                }
            }
        }
    }

    private fun writeFile(dir: DocumentFile, name: String, write: (java.io.OutputStream) -> Unit) {
        val file = createFile(dir.uri, "application/octet-stream", name) ?: error("Couldn't create $name.")
        // Some providers add an extension for the type; ask for the exact name back.
        if (file.name != name) file.renameTo(name)
        context.contentResolver.openOutputStream(file.uri)!!.use(write)
    }
}
