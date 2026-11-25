package com.voicepad.app.transcription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The translation packs Google ML Kit downloads (one per language pair,
 * ~30 MB) live in `no_backup/`, not in the app's files directory, so they
 * never reached the model folder. They are mirrored there too, as
 * `mlkit/translate/<pair>` directories plus `hashes.txt`: ML Kit records each
 * downloaded pack's hash in the `com.google.mlkit.internal` preferences and
 * treats a pack without its hash as missing (downloading it again), so the
 * hash is part of what gets kept and restored.
 */
object MlKitTranslationModels {
    /** Where the packs go in the model folder. */
    private const val FOLDER = "mlkit/translate"
    private const val HASHES = "hashes.txt"
    /** ML Kit's own directory, inside the app's no-backup storage. */
    private const val MODELS_DIR = "com.google.mlkit.translate.models"
    private const val PREFS = "com.google.mlkit.internal"
    private const val HASH_KEY = "current_model_hash_COM.GOOGLE.BASE_TRANSLATE:"

    private val mutex = Mutex()
    /** Packs already copied in this process, so every translate() doesn't re-copy them. */
    private val saved = mutableSetOf<String>()
    private var savedHashes: String? = null

    /** Where ML Kit keeps the downloaded packs. */
    fun root(context: Context): File = File(context.noBackupFilesDir, MODELS_DIR)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The pairs downloaded on this phone (ML Kit's `temp/` is a partial download; ignored). */
    private fun installed(context: Context): List<String> =
        root(context).listFiles()?.filter { it.isDirectory && it.name != "temp" }?.map { it.name }.orEmpty()

    /**
     * Copies the packs kept in the model folder back into the app's storage,
     * so a reinstall (or a folder from a previous install) doesn't download
     * them again. A no-op when nothing is missing, so it's cheap to call
     * before ML Kit is used.
     */
    suspend fun restore(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val folder = ModelFolder.get(context)
            if (!folder.isActive()) return@withLock
            val pairs = folder.listDirs(FOLDER)
            if (pairs.isEmpty()) return@withLock
            val hashes = readHashes(folder)
            val root = root(context).apply { mkdirs() }
            for (pair in pairs) {
                val target = File(root, pair)
                if (target.exists()) continue
                if (!folder.restore("$FOLDER/$pair", target, writeMarker = false)) continue
                // Without its hash ML Kit would download the pack again.
                val hash = hashes[pair] ?: continue
                if (prefs(context).getString(HASH_KEY + pair, null) == null) {
                    prefs(context).edit().putString(HASH_KEY + pair, hash).apply()
                }
            }
        }
    }

    /** Copies every downloaded pack into the model folder, skipping the ones already there. */
    fun saveAll(context: Context) {
        val folder = ModelFolder.get(context)
        if (!folder.isActive()) return
        val key = folder.state.value.folderName ?: return
        val root = root(context)
        for (pair in installed(context)) {
            if (!saved.add("$key/$pair")) continue
            folder.saveLater("$FOLDER/$pair", File(root, pair))
        }
        val hashes = hashes(context).entries.joinToString("\n") { "${it.key}=${it.value}" }
        if (hashes.isEmpty() || hashes == savedHashes) return
        savedHashes = hashes
        folder.saveTextLater("$FOLDER/$HASHES", hashes)
    }

    private fun hashes(context: Context): Map<String, String> = prefs(context).all
        .mapNotNull { (key, value) ->
            if (!key.startsWith(HASH_KEY) || value !is String) return@mapNotNull null
            key.removePrefix(HASH_KEY) to value
        }
        .toMap()

    private suspend fun readHashes(folder: ModelFolder): Map<String, String> {
        val text = folder.readText("$FOLDER/$HASHES") ?: return emptyMap()
        return text.lineSequence()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
