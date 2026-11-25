package com.voicepad.app.transcription

/** The on-device speech models the user can pick from (all via sherpa-onnx). */
enum class EngineChoice(
    /** Release asset name under k2-fsa/sherpa-onnx `asr-models`. */
    val archive: String,
    val displayName: String,
    val shortName: String,
    /** Approximate download size in megabytes. */
    val sizeMb: Int,
    val kind: Kind,
    /** Which spoken languages the model understands (shown in Settings). */
    val languages: String,
    /** What the model is for, in one line (shown when pre-downloading models). */
    val description: String,
) {
    PARAKEET_V3(
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        "Auto: Western (Parakeet v3)", "Parakeet v3", 490, Kind.TRANSDUCER,
        "English, French, Spanish, German and 21 more European languages, switching per sentence — not Chinese",
        "Default. Chooses the right European language per sentence — English, French, Spanish, German and 21 more.",
    ),
    AUTO_ASIAN(
        "",
        "Auto: Asian + English (SenseVoice + Parakeet)", "Auto Asian", 655, Kind.AUTO,
        "Mandarin, Cantonese, Japanese, Korean via SenseVoice and English via Parakeet, picked per sentence",
        "Mandarin, Cantonese, Japanese and Korean via SenseVoice, English via Parakeet — picked per sentence.",
    ),
    PARAKEET_V2(
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        "Parakeet TDT v2 (English)", "Parakeet v2", 480, Kind.TRANSDUCER,
        "English only",
        "English only; an older Parakeet, a little smaller than v3.",
    ),
    SENSE_VOICE(
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        "SenseVoice (Chinese & East Asian)", "SenseVoice", 165, Kind.SENSE_VOICE,
        "Mandarin, Cantonese, English, Japanese, Korean",
        "Mandarin, Cantonese, English, Japanese and Korean in one fast model.",
    ),
    WHISPER_TINY_EN(
        "sherpa-onnx-whisper-tiny.en",
        "Whisper Tiny (English)", "Whisper tiny", 120, Kind.WHISPER,
        "English only",
        "Smallest English Whisper — fastest, least accurate.",
    ),
    WHISPER_BASE_EN(
        "sherpa-onnx-whisper-base.en",
        "Whisper Base (English)", "Whisper base", 210, Kind.WHISPER,
        "English only",
        "Small English Whisper — a step up in accuracy.",
    ),
    WHISPER_SMALL_EN(
        "sherpa-onnx-whisper-small.en",
        "Whisper Small (English)", "Whisper small", 640, Kind.WHISPER,
        "English only",
        "English Whisper — more accurate, slower.",
    ),
    WHISPER_SMALL(
        "sherpa-onnx-whisper-small",
        "Whisper Small (multilingual)", "Whisper small ML", 640, Kind.WHISPER,
        "~100 languages incl. Mandarin, French, Spanish",
        "Multilingual Whisper for ~100 languages — more accurate, slower.",
    ),
    WHISPER_TURBO(
        "sherpa-onnx-whisper-turbo",
        "Whisper Large v3 Turbo", "Whisper turbo", 560, Kind.WHISPER,
        "~100 languages incl. Mandarin, French, Spanish (best accuracy, slowest)",
        "Most accurate multilingual model; used for background voices and conversation mode.",
    );

    /** For Settings and download prompts, e.g. "~490 MB download". */
    val approximateSize: String get() = "~$sizeMb MB download"

    enum class Kind { TRANSDUCER, WHISPER, SENSE_VOICE, AUTO }

    /** The model archives this choice needs (Auto needs two). */
    val components: List<EngineChoice>
        get() = if (kind == Kind.AUTO) listOf(PARAKEET_V3, SENSE_VOICE) else listOf(this)

    val downloadUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive.tar.bz2"

    /** Whether this model understands speech in [language] (a code like "zh"). */
    fun understands(language: String): Boolean = when (this) {
        PARAKEET_V3 -> language in PARAKEET_V3_LANGUAGES
        AUTO_ASIAN, SENSE_VOICE -> language in EAST_ASIAN_AND_ENGLISH
        PARAKEET_V2, WHISPER_TINY_EN, WHISPER_BASE_EN, WHISPER_SMALL_EN -> language == "en"
        // Whisper's ~100 languages cover everything the translator offers well enough.
        WHISPER_SMALL, WHISPER_TURBO -> true
    }

    companion object {
        /** The 25 European languages Parakeet TDT v3 was trained on. */
        val PARAKEET_V3_LANGUAGES = setOf(
            "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it", "lv", "lt",
            "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
        )
        private val EAST_ASIAN_AND_ENGLISH = setOf("en", "zh", "yue", "ja", "ko")

        /**
         * The model to use for a conversation between [a] and [b]: the
         * fastest one that understands both, preferring one already
         * downloaded ([installed]) over a download.
         */
        fun recommendedFor(a: String, b: String, installed: Set<EngineChoice> = emptySet()): EngineChoice {
            // Auto Asian adds Parakeet for better English; without English, SenseVoice alone is enough.
            val asian = if (a == "en" || b == "en") listOf(AUTO_ASIAN, SENSE_VOICE) else listOf(SENSE_VOICE, AUTO_ASIAN)
            val order = listOf(PARAKEET_V3) + asian + listOf(WHISPER_SMALL, WHISPER_TURBO)
            val fitting = order.filter { it.understands(a) && it.understands(b) }
            // Whisper small is only worth it over turbo when it saves a download.
            return fitting.firstOrNull { it in installed } ?: fitting.first { it != WHISPER_SMALL }
        }
    }
}
