package com.voicepad.app.llm

import com.voicepad.app.llm.LlmPrompt.TypeStyle
import java.io.File

/** Post-processes text before it is typed out. Not thread-safe; callers serialise access. */
interface LlmEngine {
    suspend fun transform(text: String, style: TypeStyle): String
    fun release()
}

/**
 * Creates the best available engine for [choice]. Returns [FakeLlmEngine] when
 * the llama.cpp native library isn't bundled, so the rest of the pipeline
 * stays testable without it.
 */
fun createLlmEngine(choice: LlmChoice, modelFile: File): LlmEngine =
    LlamaEngine.create(choice, modelFile) ?: FakeLlmEngine

/**
 * Stand-in used until the llama.cpp JNI library is built (or when it is not
 * bundled). Applies deterministic, safe cleanup: collapse whitespace and trim.
 * Deliberately conservative — it never changes meaning.
 */
object FakeLlmEngine : LlmEngine {
    override suspend fun transform(text: String, style: TypeStyle): String =
        text.replace(Regex("\\s+"), " ").trim()

    override fun release() {}
}
