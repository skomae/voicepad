package com.voicepad.app.transcription

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.voicepad.app.audio.AudioRecorder
import java.io.File

/**
 * Text plus the spoken language the model reported (e.g. "zh"), if it reports
 * one, and which model produced it.
 */
data class Transcript(val text: String, val language: String?, val engine: String? = null)

/** Speech-to-text over 16 kHz mono float samples. Not thread-safe; callers serialise access. */
interface SpeechEngine {
    /** How long audio should be grouped before it reaches [transcribe]. */
    val grouping: ChunkPlanner.Grouping
    fun transcribe(samples: FloatArray): Transcript
    fun release()
}

/** One sherpa-onnx model. */
class SherpaEngine(private val choice: EngineChoice, modelDir: File) : SpeechEngine {
    override val grouping =
        if (choice.kind == EngineChoice.Kind.WHISPER) ChunkPlanner.Grouping.LONGEST_PAUSES
        else ChunkPlanner.Grouping.UTTERANCES

    private val recognizer: OfflineRecognizer

    init {
        fun find(part: String): String {
            val file = modelDir.listFiles()?.firstOrNull { it.name.contains(part) && it.name.endsWith(".int8.onnx") }
                ?: error("Model file *$part*.int8.onnx missing in ${modelDir.name}")
            return file.absolutePath
        }
        val tokens = modelDir.listFiles()?.firstOrNull { it.name.endsWith("tokens.txt") }?.absolutePath
            ?: error("tokens.txt missing in ${modelDir.name}")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        val modelConfig = when (choice.kind) {
            EngineChoice.Kind.TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = find("encoder"),
                    decoder = find("decoder"),
                    joiner = find("joiner"),
                ),
                tokens = tokens,
                modelType = "nemo_transducer",
                numThreads = threads,
            )
            EngineChoice.Kind.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".int8.onnx") }?.absolutePath
                        ?: error("Model file missing in ${modelDir.name}"),
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = tokens,
                modelType = "sense_voice",
                numThreads = threads,
            )
            EngineChoice.Kind.AUTO -> error("Auto combines two engines; see AutoEngine.")
            EngineChoice.Kind.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = find("encoder"),
                    decoder = find("decoder"),
                    language = "",
                    task = "transcribe",
                ),
                tokens = tokens,
                modelType = "whisper",
                numThreads = threads,
            )
        }
        recognizer = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = AudioRecorder.SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
            ),
        )
    }

    override fun transcribe(samples: FloatArray): Transcript {
        // Very short clips just produce noise or hallucinations.
        if (samples.size < AudioRecorder.SAMPLE_RATE / 2) return Transcript("", null)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AudioRecorder.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return Transcript(result.text.trim(), LanguageTags.fromModel(result.lang), choice.shortName)
        } finally {
            stream.release()
        }
    }

    override fun release() = recognizer.release()
}
