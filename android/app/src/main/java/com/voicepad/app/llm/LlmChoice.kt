package com.voicepad.app.llm

/**
 * Small on-device instruction-following models for the text transformer.
 * All run as Q4_K_M GGUF via llama.cpp. [downloadSizeMb] is the real size of
 * the Q4_K_M file; [inferenceRamGb] is an approximate peak-RAM budget (weights
 * + KV cache + activations) used only for device guidance.
 */
enum class LlmChoice(
    val displayName: String,
    val shortName: String,
    val fileName: String,
    val downloadUrl: String,
    val downloadSizeMb: Int,
    val inferenceRamGb: Double,
) {
    QWEN_0_5B(
        "Qwen2.5 0.5B Instruct",
        "Qwen 0.5B",
        "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        469,
        0.6,
    ),
    GEMMA_3_1B(
        "Gemma 3 1B it",
        "Gemma 1B",
        "gemma-3-1b-it-Q4_K_M.gguf",
        "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
        769,
        0.9,
    ),
    QWEN_1_5B(
        "Qwen2.5 1.5B Instruct",
        "Qwen 1.5B",
        "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        1066,
        1.2,
    );

    val approximateSize: String get() = "~$downloadSizeMb MB download"
}
