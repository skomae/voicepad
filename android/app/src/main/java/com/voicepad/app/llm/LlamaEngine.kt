package com.voicepad.app.llm

import com.voicepad.app.llm.LlmPrompt.TypeStyle
import java.io.File

/**
 * llama.cpp-backed [LlmEngine] (GGUF). Requires the `voicepad_llm` JNI library,
 * which is built separately with the NDK (see the keyboard/LLM plan doc).
 *
 * Native contract (C symbols):
 *   jlong Java_com_voicepad_app_llm_LlamaJni_create(JNIEnv*, jclass, jstring, jint)
 *   jstring Java_com_voicepad_app_llm_LlamaJni_complete(JNIEnv*, jclass, jlong, jstring, jint)
 *   void Java_com_voicepad_app_llm_LlamaJni_release(JNIEnv*, jclass, jlong)
 *
 * [create] returns null when the library isn't bundled so callers fall back to
 * [FakeLlmEngine] instead of crashing.
 */
class LlamaEngine private constructor(private val handle: Long) : LlmEngine {

    override suspend fun transform(text: String, style: TypeStyle): String =
        LlamaJni.complete(handle, LlmPrompt.build(style, text), MAX_TOKENS).trim()

    override fun release() = LlamaJni.release(handle)

    companion object {
        private const val MAX_TOKENS = 512
        private const val THREADS = 2 // leaves the speech model its own cores

        val available: Boolean by lazy { runCatching { System.loadLibrary("voicepad_llm") }.isSuccess }

        fun create(choice: LlmChoice, modelFile: File): LlmEngine? {
            if (!available || !modelFile.exists()) return null
            return runCatching {
                val handle = LlamaJni.create(modelFile.absolutePath, THREADS)
                if (handle == 0L) null else LlamaEngine(handle)
            }.getOrNull()
        }
    }
}

internal object LlamaJni {
    external fun create(path: String, threads: Int): Long
    external fun complete(handle: Long, prompt: String, maxTokens: Int): String
    external fun release(handle: Long)
}
