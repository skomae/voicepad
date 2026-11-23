package com.voicepad.app.transcription

/** The on-device speech models the user can pick from (all via sherpa-onnx). */
enum class EngineChoice(
    /** Release asset name under k2-fsa/sherpa-onnx `asr-models`. */
    val archive: String,
    val displayName: String,
    val shortName: String,
    val approximateSize: String,
    val kind: Kind,
    /** Which spoken languages the model understands (shown in Settings). */
    val languages: String,
) {
    PARAKEET_V3(
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        "Parakeet TDT v3 (multilingual)", "Parakeet v3", "~490 MB download", Kind.TRANSDUCER,
        "25 European languages incl. English, French, Spanish, German — not Chinese",
    ),
    PARAKEET_V2(
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        "Parakeet TDT v2 (English)", "Parakeet v2", "~480 MB download", Kind.TRANSDUCER,
        "English only",
    ),
    SENSE_VOICE(
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        "SenseVoice (Chinese & East Asian)", "SenseVoice", "~165 MB download", Kind.SENSE_VOICE,
        "Mandarin, Cantonese, English, Japanese, Korean",
    ),
    WHISPER_TINY_EN(
        "sherpa-onnx-whisper-tiny.en",
        "Whisper Tiny (English)", "Whisper tiny", "~120 MB download", Kind.WHISPER,
        "English only",
    ),
    WHISPER_BASE_EN(
        "sherpa-onnx-whisper-base.en",
        "Whisper Base (English)", "Whisper base", "~210 MB download", Kind.WHISPER,
        "English only",
    ),
    WHISPER_SMALL_EN(
        "sherpa-onnx-whisper-small.en",
        "Whisper Small (English)", "Whisper small", "~640 MB download", Kind.WHISPER,
        "English only",
    ),
    WHISPER_SMALL(
        "sherpa-onnx-whisper-small",
        "Whisper Small (multilingual)", "Whisper small ML", "~640 MB download", Kind.WHISPER,
        "~100 languages incl. Mandarin, French, Spanish",
    ),
    WHISPER_TURBO(
        "sherpa-onnx-whisper-turbo",
        "Whisper Large v3 Turbo", "Whisper turbo", "~560 MB download", Kind.WHISPER,
        "~100 languages incl. Mandarin, French, Spanish (best accuracy, slowest)",
    );

    enum class Kind { TRANSDUCER, WHISPER, SENSE_VOICE }

    val downloadUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive.tar.bz2"
}
