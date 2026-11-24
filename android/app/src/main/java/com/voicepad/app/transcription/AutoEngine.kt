package com.voicepad.app.transcription

/**
 * "Auto: Asian + English": for each utterance, SenseVoice decides whether it
 * is Mandarin, Cantonese, Japanese or Korean (keeps its own text) or English
 * (transcribed again by Parakeet, which is stronger on English).
 *
 * SenseVoice's language tag is reliable for that choice — in testing it never
 * mistook English and got Chinese, Japanese and Korean right from about two
 * seconds of speech — though it is not for European languages (it labels
 * Spanish "Japanese"); those belong in "Auto: Western" (Parakeet alone).
 *
 * Works per utterance, so a recording that switches language at a pause gets
 * the right model for each part.
 */
class AutoEngine(
    private val parakeet: SpeechEngine,
    private val senseVoice: SpeechEngine,
) : SpeechEngine {
    override val grouping = ChunkPlanner.Grouping.UTTERANCES

    override fun transcribe(samples: FloatArray): Transcript {
        val first = senseVoice.transcribe(samples)
        if (first.language in EAST_ASIAN) return first
        return parakeet.transcribe(samples).copy(language = "en")
    }

    override fun release() {
        parakeet.release()
        senseVoice.release()
    }

    companion object {
        val EAST_ASIAN = setOf("zh", "yue", "ja", "ko")
    }
}
