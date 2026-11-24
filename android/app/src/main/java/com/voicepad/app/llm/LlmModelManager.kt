package com.voicepad.app.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads and caches one GGUF model per [LlmChoice] into app storage. */
class LlmModelManager(context: Context) {
    private val modelsDir = File(context.filesDir, "llm").apply { mkdirs() }

    fun file(choice: LlmChoice) = File(modelsDir, choice.fileName)

    fun isInstalled(choice: LlmChoice) =
        file(choice).exists() && File(modelsDir, choice.fileName + ".complete").exists()

    /** Deletes a choice's files (e.g. after they failed to load) so they download fresh. */
    fun discard(choice: LlmChoice) {
        file(choice).delete()
        File(modelsDir, choice.fileName + ".complete").delete()
    }

    /** Streams the GGUF to disk; [progress] reports 0..1. */
    suspend fun install(choice: LlmChoice, progress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled(choice)) return@withContext
        val target = file(choice)
        target.parentFile?.mkdirs()
        discard(choice)

        val connection = URL(choice.downloadUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        try {
            check(connection.responseCode == 200) { "Model download failed (HTTP ${connection.responseCode})." }
            val total = connection.contentLengthLong
            val tmp = File(target.path + ".part")
            connection.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) progress((read.toDouble() / total).coerceAtMost(0.999))
                    }
                    out.fd.sync()
                }
            }
            check(tmp.renameTo(target)) { "Could not save the model." }
            File(target.path + ".complete").writeText(choice.fileName)
        } finally {
            connection.disconnect()
        }
        progress(1.0)
    }
}
