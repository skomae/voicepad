package com.voicepad.app.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPromptTest {

    @Test
    fun includesSystemAndText() {
        val prompt = LlmPrompt.build(LlmPrompt.TypeStyle.CLEANUP, "hello world")
        assertTrue(prompt.contains("careful editor"))
        assertTrue(prompt.endsWith("hello world"))
    }

    @Test
    fun everyStyleHasAnInstruction() {
        LlmPrompt.TypeStyle.entries.forEach { style ->
            assertTrue(style.system.isNotBlank())
        }
    }
}
