package com.voicepad.app.llm

import android.content.Context
import com.voicepad.app.transcription.ModelFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads and caches one GGUF model per [LlmChoice] into app storage. */
class LlmModelManager(private val context: Context) {
    private val modelsDir = File(context.filesDir, "llm").apply { mkdirs() }

    fun file(choice: LlmChoice) = File(modelsDir, choice.fileName)

    fun isInstalled(choice: LlmChoice) =
        file(choice).exists() && File(modelsDir, choice.fileName + ".complete").exists()

    /** Whether the installed file at least starts like a GGUF model (magic "GGUF"). */
    fun looksValid(choice: LlmChoice): Boolean {
        val f = file(choice)
        if (!f.isFile || f.length() < 4) return false
        val magic = ByteArray(4)
        return runCatching { f.inputStream().use { it.read(magic) } == 4 && String(magic, Charsets.US_ASCII) == "GGUF" }
            .getOrDefault(false)
    }

    /** Deletes a choice's files (e.g. after they failed to load) so they download fresh. */
    fun discard(choice: LlmChoice) {
        file(choice).delete()
        File(modelsDir, choice.fileName + ".complete").delete()
    }

    /**
     * Streams the GGUF to disk; [progress] reports 0..1 at most once per
     * percent. A failed or cancelled download leaves nothing behind.
     */
    suspend fun install(choice: LlmChoice, progress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled(choice)) return@withContext
        val target = file(choice)
        target.parentFile?.mkdirs()
        val folder = ModelFolder.get(context)
        // A copy kept in the user's model folder (e.g. from before a reinstall) beats downloading.
        if (!target.exists() && folder.restore("llm/${choice.fileName}", target)) {
            File(target.path + ".complete").writeText(choice.fileName)
            progress(1.0)
            return@withContext
        }
        discard(choice)

        val tmp = File(target.path + ".part")
        val connection = URL(choice.downloadUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        try {
            check(connection.responseCode == 200) { "Model download failed (HTTP ${connection.responseCode})." }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    var lastPercent = -1
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt().coerceAtMost(99)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                progress(percent / 100.0)
                            }
                        }
                    }
                    out.fd.sync()
                }
            }
            check(total <= 0 || tmp.length() == total) { "Model download was incomplete." }
            check(tmp.renameTo(target)) { "Could not save the model." }
            File(target.path + ".complete").writeText(choice.fileName)
            folder.saveLater("llm/${choice.fileName}", target)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        progress(1.0)
    }
}
