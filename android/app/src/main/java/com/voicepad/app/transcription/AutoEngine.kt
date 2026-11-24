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

    override fun transcribe(samples: FloatArray, languageHint: String?, preview: Boolean): Transcript {
        // The live preview only needs SenseVoice: it covers every language the
        // Auto engine understands, and the full routing below is applied once
        // the utterance is committed.
        if (preview) return senseVoice.transcribe(samples)

        val first = senseVoice.transcribe(samples)
        // A language latched earlier in this utterance stops the decision from
        // flipping engines mid-sentence.
        val language = languageHint ?: first.language
        if (language in EAST_ASIAN) return first

        // Only trust Parakeet for explicitly-English, Latin-only text. Mandarin
        // (and Japanese/Korean) with English loanwords can be tagged "en" by
        // SenseVoice but transcribes to Han/kana — and Parakeet has no Chinese
        // at all, so routing that to Parakeet is catastrophic.
        if (language == "en" && !LanguageTags.containsHan(first.text) && !LanguageTags.containsKana(first.text)) {
            return parakeet.transcribe(samples).copy(language = "en")
        }
        return first
    }

    override fun release() {
        parakeet.release()
        senseVoice.release()
    }

    companion object {
        val EAST_ASIAN = setOf("zh", "yue", "ja", "ko")
    }
}
