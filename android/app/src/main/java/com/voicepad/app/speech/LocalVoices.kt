package com.voicepad.app.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Speech voices that run on the phone through sherpa-onnx. Each downloads
 * once. Both were checked by synthesising sentences and transcribing the
 * result back with SenseVoice, which recovered the text exactly.
 */
enum class LocalVoice(
    /** Release asset under k2-fsa/sherpa-onnx `tts-models`. */
    val archive: String,
    val displayName: String,
    val sizeLabel: String,
    /** Languages this voice is used for (the others go to the phone's voice). */
    val languages: Set<String>,
) {
    /** KittenTTS nano v0.8 (int8): tiny and fast; needs its pronunciation dictionary. */
    KITTEN_EN("kitten-nano-en-v0_8-int8", "Kitten (English)", "37 MB", setOf("en")),

    /**
     * MeloTTS Chinese + English. Its smaller int8 model in the archive fails
     * to load, so the full one is kept.
     */
    MELO_ZH("vits-melo-tts-zh_en", "MeloTTS (Mandarin)", "167 MB", setOf("zh"));

    val downloadUrl get() = "$BASE/$archive.tar.bz2"

    companion object {
        const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
        /** Kitten's English pronunciation dictionary, a separate download. */
        const val ENGLISH_LEXICON_URL = "$BASE/lexicon-en-us.txt"

        fun forLanguage(language: String): LocalVoice? = entries.firstOrNull { language in it.languages }
    }
}

/** Downloads and unpacks local voices into app storage. */
class LocalVoiceFiles(context: Context) {
    private val root = File(context.filesDir, "voices").apply { mkdirs() }

    fun directory(voice: LocalVoice) = File(root, voice.archive)

    fun isInstalled(voice: LocalVoice) = File(directory(voice), ".complete").exists()

    fun discard(voice: LocalVoice) = directory(voice).deleteRecursively()

    /** Files kept from the archive (paths below its top folder). */
    private fun wanted(voice: LocalVoice, path: String): Boolean = when (voice) {
        // The espeak data in the archive is unused: the dictionary replaces it.
        LocalVoice.KITTEN_EN -> path in setOf("model.int8.onnx", "voices.bin", "tokens.txt")
        LocalVoice.MELO_ZH -> path == "model.onnx" || path == "lexicon.txt" || path == "tokens.txt" ||
            path.endsWith(".fst") || path.startsWith("dict/")
    }

    /** Downloads [voice] (and its dictionary); [progress] reports 0..1. */
    suspend fun install(voice: LocalVoice, progress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled(voice)) return@withContext
        val target = directory(voice)
        target.deleteRecursively()
        target.mkdirs()
        val extra = if (voice == LocalVoice.KITTEN_EN) 0.15 else 0.0
        download(voice.downloadUrl, { p -> progress(p * (1 - extra)) }) { input ->
            TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
                while (true) {
                    ensureActive()
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val path = entry.name.substringAfter('/')
                    if (path.contains("..") || !wanted(voice, path)) continue
                    val out = File(target, path)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                }
            }
        }
        if (voice == LocalVoice.KITTEN_EN) {
            download(LocalVoice.ENGLISH_LEXICON_URL, { p -> progress(1 - extra + p * extra) }) { input ->
                File(target, "lexicon.txt").outputStream().use { input.copyTo(it, 1 shl 16) }
            }
        }
        File(target, ".complete").writeText(voice.archive)
        progress(1.0)
    }

    private suspend fun download(url: String, progress: (Double) -> Unit, consume: suspend (InputStream) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            check(connection.responseCode == 200) { "Voice download failed (HTTP ${connection.responseCode})." }
            val total = connection.contentLengthLong
            var read = 0L
            var reported = 0L
            val counting = object : java.io.FilterInputStream(connection.inputStream.buffered(1 shl 16)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
                    if (it > 0) {
                        read += it
                        if (total > 0 && read - reported > 512 * 1024) {
                            reported = read
                            progress((read.toDouble() / total).coerceAtMost(0.999))
                        }
                    }
                }
            }
            consume(counting)
        } finally {
            connection.disconnect()
        }
    }
}

/** One loaded local voice. Not thread-safe; callers serialise access. */
class LocalSynthesizer(val voice: LocalVoice, dir: File) {
    private val tts: OfflineTts
    /** Words the voice can say (English only; null = no check needed). */
    val knownWords: Set<String>?

    init {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        fun path(name: String) = File(dir, name).absolutePath
        val config = when (voice) {
            LocalVoice.KITTEN_EN -> OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kitten = OfflineTtsKittenModelConfig(
                        model = path("model.int8.onnx"),
                        voices = path("voices.bin"),
                        tokens = path("tokens.txt"),
                        lexicon = path("lexicon.txt"),
                    ),
                    numThreads = threads,
                ),
                maxNumSentences = 1,
            )
            LocalVoice.MELO_ZH -> OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = path("model.onnx"),
                        lexicon = path("lexicon.txt"),
                        tokens = path("tokens.txt"),
                        dictDir = path("dict"),
                    ),
                    numThreads = threads,
                ),
                // Reads dates, numbers and phone numbers properly.
                ruleFsts = listOf("date.fst", "number.fst", "phone.fst").map(::path).filter { File(it).exists() }.joinToString(","),
                maxNumSentences = 1,
            )
        }
        tts = OfflineTts(config = config)
        knownWords = if (voice == LocalVoice.KITTEN_EN) {
            File(dir, "lexicon.txt").bufferedReader().useLines { SpeechText.dictionaryWords(it) }
        } else {
            null
        }
    }

    /** Speech for [text] (already prepared), or null when it came out empty. */
    fun generate(text: String): GeneratedAudio? =
        tts.generate(text, sid = 0, speed = 1f).takeIf { it.samples.isNotEmpty() && it.sampleRate > 0 }

    fun release() = tts.release()
}
