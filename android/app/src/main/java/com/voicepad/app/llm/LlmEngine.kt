package com.voicepad.app.llm

import java.io.File

/** Post-processes text before it is typed out. Not thread-safe; callers serialise access. */
interface LlmEngine {
    /** Rewrites [text] following [instruction] (see [LlmPrompt.system]). */
    suspend fun transform(text: String, instruction: String): String
    fun release()
}

/** Loads the llama.cpp engine for [modelFile]; see [LlamaEngine.create] for failures. */
fun createLlmEngine(modelFile: File): LlmEngine = LlamaEngine.create(modelFile)
