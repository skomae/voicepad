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

/** Text plus the spoken language the model reported (e.g. "zh"), if it reports one. */
data class Transcript(val text: String, val language: String?)

/** Speech-to-text over 16 kHz mono float samples, backed by sherpa-onnx. */
class SherpaEngine(choice: EngineChoice, modelDir: File) {
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

    /** Not thread-safe; callers serialise access. */
    fun transcribe(samples: FloatArray): Transcript {
        // Very short clips just produce noise or hallucinations.
        if (samples.size < AudioRecorder.SAMPLE_RATE / 2) return Transcript("", null)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AudioRecorder.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return Transcript(result.text.trim(), LanguageTags.fromModel(result.lang))
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()
}
