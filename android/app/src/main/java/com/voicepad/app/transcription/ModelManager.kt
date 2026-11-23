package com.voicepad.app.transcription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads and unpacks sherpa-onnx model archives into app storage. */
class ModelManager(context: Context) {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    fun directory(choice: EngineChoice) = File(modelsDir, choice.archive)

    fun isInstalled(choice: EngineChoice) = choice.components.all { File(directory(it), ".complete").exists() }

    /** Deletes [choice]'s model folders (e.g. after they failed to load) so they download fresh. */
    fun discard(choice: EngineChoice) = choice.components.forEach { directory(it).deleteRecursively() }

    /** Downloads every archive [choice] needs; [progress] reports 0..1 across all of them. */
    suspend fun install(choice: EngineChoice, progress: (Double) -> Unit) {
        val parts = choice.components
        parts.forEachIndexed { i, part ->
            installOne(part) { p -> progress((i + p) / parts.size) }
        }
        progress(1.0)
    }

    /**
     * Streams the .tar.bz2 straight into the model folder, keeping only the
     * int8 weights and token files. [progress] reports 0..1.
     */
    private suspend fun installOne(choice: EngineChoice, progress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled(choice)) return@withContext
        val target = directory(choice)
        target.deleteRecursively()
        target.mkdirs()

        val connection = URL(choice.downloadUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            check(connection.responseCode == 200) { "Model download failed (HTTP ${connection.responseCode})." }
            val total = connection.contentLengthLong
            val counting = CountingInputStream(connection.inputStream.buffered(1 shl 16)) { read ->
                if (total > 0) progress((read.toDouble() / total).coerceAtMost(0.999))
            }
            TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                while (true) {
                    ensureActive()
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/')
                    if (!wanted(name)) continue
                    File(target, name).outputStream().use { tar.copyTo(it, 1 shl 16) }
                }
            }
        } finally {
            connection.disconnect()
        }
        File(target, ".complete").writeText(choice.archive)
        progress(1.0)
    }

    private fun wanted(name: String): Boolean =
        name.endsWith("tokens.txt") || name.endsWith(".int8.onnx")

    private class CountingInputStream(
        input: InputStream,
        private val onRead: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var count = 0L
        private var lastReported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        private fun advance(n: Long) {
            count += n
            if (count - lastReported > 512 * 1024) {
                lastReported = count
                onRead(count)
            }
        }
    }
}
