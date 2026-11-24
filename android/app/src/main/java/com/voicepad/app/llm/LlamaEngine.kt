package com.voicepad.app.llm

import java.io.File

/**
 * llama.cpp-backed [LlmEngine] (GGUF) over the `voicepad_llm` JNI library
 * built from `src/main/cpp`. Each [transform] starts from an empty context,
 * so notes never leak into one another.
 */
class LlamaEngine private constructor(private val handle: Long) : LlmEngine {

    override suspend fun transform(text: String, instruction: String): String {
        val bytes = LlamaJni.complete(handle, LlmPrompt.system(instruction), text, LlmPrompt.maxTokens(text))
            ?: throw IllegalStateException("The model couldn't process this text.")
        return String(bytes, Charsets.UTF_8).trim()
    }

    override fun release() = LlamaJni.release(handle)

    /** The model file exists but llama.cpp couldn't load it (corrupt or unsupported). */
    class ModelLoadException(message: String) : Exception(message)

    companion object {
        private const val THREADS = 2 // leaves the speech model its own cores

        private val nativeLoadError: Throwable? by lazy {
            runCatching { System.loadLibrary("voicepad_llm") }.exceptionOrNull()
        }

        /**
         * Loads [modelFile]. Throws [ModelLoadException] if the file can't be
         * loaded, or [IllegalStateException] if the native library is missing.
         */
        fun create(modelFile: File): LlamaEngine {
            nativeLoadError?.let { throw IllegalStateException("On-device LLM isn't available in this build.", it) }
            if (!modelFile.exists()) throw ModelLoadException("Model file is missing.")
            val handle = LlamaJni.create(modelFile.absolutePath, THREADS)
            if (handle == 0L) throw ModelLoadException("Couldn't load ${modelFile.name}.")
            return LlamaEngine(handle)
        }
    }
}

internal object LlamaJni {
    @JvmStatic external fun create(path: String, threads: Int): Long
    @JvmStatic external fun complete(handle: Long, system: String, user: String, maxTokens: Int): ByteArray?
    @JvmStatic external fun release(handle: Long)
}
