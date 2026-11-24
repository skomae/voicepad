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
         * Loads [modelFile], using the CPU backend libraries in
         * [nativeLibDir]. Throws [ModelLoadException] if llama.cpp rejects
         * the file, or [IllegalStateException] if the engine itself can't run.
         */
        fun create(modelFile: File, nativeLibDir: String): LlamaEngine {
            nativeLoadError?.let { throw IllegalStateException("On-device LLM isn't available in this build.", it) }
            if (!LlamaJni.init(nativeLibDir)) {
                throw IllegalStateException(
                    "llama.cpp found no CPU backend in $nativeLibDir" +
                        LlamaJni.lastError().takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                )
            }
            if (!modelFile.exists()) throw ModelLoadException("Model file is missing.")
            val handle = LlamaJni.create(modelFile.absolutePath, THREADS)
            if (handle == 0L) {
                val why = LlamaJni.lastError().takeIf { it.isNotBlank() } ?: "unknown error"
                throw ModelLoadException("Couldn't load ${modelFile.name}: $why")
            }
            return LlamaEngine(handle)
        }
    }
}

internal object LlamaJni {
    @JvmStatic external fun init(nativeLibDir: String): Boolean
    @JvmStatic external fun lastError(): String
    @JvmStatic external fun create(path: String, threads: Int): Long
    @JvmStatic external fun complete(handle: Long, system: String, user: String, maxTokens: Int): ByteArray?
    @JvmStatic external fun release(handle: Long)
}
