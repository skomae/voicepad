package com.voicepad.app.llm

import com.voicepad.app.llm.LlmPrompt.TypeStyle
import java.io.File

/** Post-processes text before it is typed out. Not thread-safe; callers serialise access. */
interface LlmEngine {
    suspend fun transform(text: String, style: TypeStyle): String
    fun release()
}

/** Loads the llama.cpp engine for [modelFile]; see [LlamaEngine.create] for failures. */
fun createLlmEngine(modelFile: File): LlmEngine = LlamaEngine.create(modelFile)
