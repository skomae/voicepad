package com.voicepad.app.transcription

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * (`models/<name>/…`, `voices/<name>/…`), and a model directory only counts
 * once its `.complete` marker is there, which is copied last.
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

    companion object {
        @Volatile private var instance: ModelFolder? = null
        fun get(context: Context): ModelFolder =
            instance ?: synchronized(this) { instance ?: ModelFolder(context.applicationContext).also { instance = it } }

        private const val MARKER = ".complete"
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refreshState()
    }

    private val uri: Uri? get() = prefs.getString("modelFolder", null)?.let(Uri::parse)

    private fun root(): DocumentFile? {
        val u = uri ?: return null
        val tree = runCatching { DocumentFile.fromTreeUri(context, u) }.getOrNull()
        return tree?.takeIf { it.exists() && it.canWrite() }
    }

    private fun refreshState() {
        val u = uri
        val root = root()
        _state.update {
            it.copy(
                folderName = u?.let { root?.name ?: u.lastPathSegment?.substringAfterLast(':') ?: "chosen folder" },
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
        scope.launch { saveAllInstalled() }
    }

    /** Stops using the folder (its copies stay where they are). */
    fun forget() {
        uri?.let { u -> runCatching { context.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        prefs.edit { remove("modelFolder") }
        _state.value = State()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Copies the model at [relPath] (a directory or a single file under the
     * app's files directory) back from the folder into [target]. Returns
     * true if it was there, complete, and copied.
     */
    suspend fun restore(relPath: String, target: File): Boolean = mutex.withLock {
        val root = root() ?: return@withLock false
        val source = find(root, relPath) ?: return@withLock false
        try {
            _state.update { it.copy(busy = "Restoring ${target.name} from the folder…") }
            if (source.isDirectory) {
                if (source.findFile(MARKER) == null) return@withLock false
                target.deleteRecursively()
                target.mkdirs()
                copyDown(source, target, skipMarker = true)
                // The marker last: an interrupted copy isn't mistaken for a whole model.
                File(target, MARKER).writeText("restored")
            } else {
                if (source.length() <= 0) return@withLock false
                target.parentFile?.mkdirs()
                val part = File(target.path + ".part")
                context.contentResolver.openInputStream(source.uri)!!.use { input -> part.outputStream().use { input.copyTo(it, 1 shl 16) } }
                check(part.length() == source.length()) { "Incomplete copy." }
                check(part.renameTo(target)) { "Couldn't save ${target.name}." }
            }
            true
        } catch (e: Throwable) {
            // A damaged copy in the folder: fall back to downloading.
            if (source.isDirectory) target.deleteRecursively() else target.delete()
            _state.update { it.copy(error = "Couldn't restore ${target.name} from the folder: ${e.message}") }
            false
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /** Copies a freshly installed model into the folder, in the background. */
    fun saveLater(relPath: String, source: File) {
        if (uri == null) return
        scope.launch { mutex.withLock { save(relPath, source) } }
    }

    private fun save(relPath: String, source: File) {
        val root = root() ?: return
        if (!source.exists()) return
        try {
            _state.update { it.copy(busy = "Copying ${source.name} to the folder…") }
            val parentPath = relPath.substringBeforeLast('/', "")
            val parent = if (parentPath.isEmpty()) root else mkdirs(root, parentPath)
            val name = relPath.substringAfterLast('/')
            if (source.isDirectory) {
                val existing = parent.findFile(name)
                if (existing?.isDirectory == true && existing.findFile(MARKER) != null) return // already there
                existing?.delete()
                val dir = parent.createDirectory(name) ?: error("Couldn't create $name.")
                copyUp(source, dir)
                writeFile(dir, MARKER) { it.write("complete".toByteArray()) }
            } else {
                val existing = parent.findFile(name)
                if (existing != null && existing.length() == source.length()) return
                existing?.delete()
                parent.findFile("$name.part")?.delete()
                val part = parent.createFile("application/octet-stream", "$name.part") ?: error("Couldn't create $name.")
                context.contentResolver.openOutputStream(part.uri)!!.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 16) } }
                check(part.renameTo(name)) { "Couldn't name $name." }
            }
        } catch (e: Throwable) {
            _state.update { it.copy(error = "Couldn't copy ${source.name} to the folder: ${e.message}") }
        } finally {
            _state.update { it.copy(busy = null) }
        }
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
    }

    private fun find(root: DocumentFile, relPath: String): DocumentFile? {
        var at: DocumentFile = root
        for (part in relPath.split('/')) at = at.findFile(part) ?: return null
        return at
    }

    private fun mkdirs(root: DocumentFile, relPath: String): DocumentFile {
        var at = root
        for (part in relPath.split('/')) {
            at = at.findFile(part)?.takeIf { it.isDirectory } ?: at.createDirectory(part) ?: error("Couldn't create $part.")
        }
        return at
    }

    private fun copyUp(source: File, dir: DocumentFile) {
        source.listFiles()?.forEach { f ->
            if (f.name == MARKER || f.name.endsWith(".part")) return@forEach
            if (f.isDirectory) copyUp(f, dir.createDirectory(f.name) ?: error("Couldn't create ${f.name}."))
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
        val file = dir.createFile("application/octet-stream", name) ?: error("Couldn't create $name.")
        // Some providers add an extension for the type; ask for the exact name back.
        if (file.name != name) file.renameTo(name)
        context.contentResolver.openOutputStream(file.uri)!!.use(write)
    }
}
