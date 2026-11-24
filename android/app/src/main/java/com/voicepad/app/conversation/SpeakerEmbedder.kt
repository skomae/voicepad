package com.voicepad.app.conversation

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.voicepad.app.CrashGuard
import com.voicepad.app.audio.AudioRecorder
import com.voicepad.app.transcription.ModelFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns a stretch of speech into a voice fingerprint (speaker embedding) with
 * 3D-Speaker's CAM++ model, trained on Chinese and English speech (~28 MB,
 * downloaded once). Not thread-safe; callers serialise access.
 */
class SpeakerEmbedder private constructor(private val extractor: SpeakerEmbeddingExtractor) {

    /** The fingerprint of [samples] (16 kHz mono), or null if too short to tell. */
    fun embed(samples: FloatArray): FloatArray? {
        if (samples.size < MIN_SAMPLES) return null
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, AudioRecorder.SAMPLE_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) return null
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    fun release() = extractor.release()

    companion object {
        private const val URL_STRING =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/" +
                "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        const val SIZE_LABEL = "28 MB"

        /** Shorter speech gives fingerprints too noisy to trust. */
        val MIN_SAMPLES = AudioRecorder.SAMPLE_RATE

        private fun file(context: Context) = File(context.filesDir, "models/speaker/campplus_zh_en.onnx")

        fun isInstalled(context: Context) = file(context).exists()

        /** Downloads the model if needed, then loads it. [progress] reports 0..1. */
        suspend fun load(context: Context, progress: (Double) -> Unit): SpeakerEmbedder {
            val target = file(context)
            if (!target.exists()) {
                val folder = ModelFolder.get(context)
                val restored = withContext(Dispatchers.IO) { folder.restore("models/speaker/${target.name}", target) }
                if (!restored) {
                    download(target, progress)
                    folder.saveLater("models/speaker/${target.name}", target)
                }
            }
            return withContext(Dispatchers.Default) {
                CrashGuard.during(CrashGuard.Risky.VOICE_ID) { SpeakerEmbedder(
                    SpeakerEmbeddingExtractor(
                        config = SpeakerEmbeddingExtractorConfig(model = target.absolutePath, numThreads = 2),
                    )
                ) }
            }
        }

        private suspend fun download(target: File, progress: (Double) -> Unit) = withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val part = File(target.path + ".part")
            val connection = URL(URL_STRING).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            try {
                check(connection.responseCode == 200) { "Voice model download failed (HTTP ${connection.responseCode})." }
                val total = connection.contentLengthLong
                connection.inputStream.buffered(1 shl 16).use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) progress(done.toDouble() / total)
                        }
                    }
                }
                check(total <= 0 || part.length() == total) { "Voice model download was incomplete." }
                check(part.renameTo(target)) { "Couldn't save the voice model." }
            } finally {
                connection.disconnect()
                part.delete()
            }
        }
    }
}
