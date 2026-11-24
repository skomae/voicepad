package com.voicepad.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPromptTest {

    @Test
    fun everyStyleIsAnEditorThatNeverAnswersTheTranscript() {
        LlmPrompt.TypeStyle.entries.forEach { style ->
            assertTrue(style.system.startsWith("You are a careful editor."))
            assertTrue(style.system.contains("never answer it"))
        }
    }

    @Test
    fun replyBudgetScalesWithInputWithinBounds() {
        assertEquals(64, LlmPrompt.maxTokens(""))
        assertEquals(114, LlmPrompt.maxTokens("x".repeat(100)))
        assertEquals(1024, LlmPrompt.maxTokens("x".repeat(100_000)))
    }
}
