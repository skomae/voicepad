package com.voicepad.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPromptTest {

    @Test
    fun systemWrapsTheInstructionAndNeverAnswersTheTranscript() {
        val system = LlmPrompt.system("Make it shorter.")
        assertTrue(system.startsWith("You are a careful editor. Make it shorter."))
        assertTrue(system.contains("never answer it"))
    }

    @Test
    fun blankInstructionFallsBackToCleanup() {
        assertTrue(LlmPrompt.system("  ").contains(LlmPrompt.CLEANUP.instruction))
    }

    @Test
    fun twoDistinctPresets() {
        assertEquals(2, LlmPrompt.PRESETS.size)
        assertEquals(LlmPrompt.POLISH, LlmPrompt.presetFor(LlmPrompt.POLISH.instruction))
        assertNull(LlmPrompt.presetFor("something else"))
    }

    @Test
    fun rememberKeepsUserPromptsMostRecentFirstWithoutDuplicates() {
        var recent = emptyList<String>()
        recent = LlmPrompt.remember(recent, "a")
        recent = LlmPrompt.remember(recent, "b")
        recent = LlmPrompt.remember(recent, " a ")
        assertEquals(listOf("a", "b"), recent)
    }

    @Test
    fun rememberSkipsPresetsAndBlanks() {
        val recent = listOf("mine")
        assertEquals(recent, LlmPrompt.remember(recent, LlmPrompt.CLEANUP.instruction))
        assertEquals(recent, LlmPrompt.remember(recent, "   "))
    }

    @Test
    fun rememberIsCapped() {
        var recent = emptyList<String>()
        repeat(20) { recent = LlmPrompt.remember(recent, "p$it") }
        assertEquals(LlmPrompt.MAX_RECENT, recent.size)
        assertEquals("p19", recent.first())
    }

    @Test
    fun replyBudgetScalesWithInputWithinBounds() {
        assertEquals(64, LlmPrompt.maxTokens(""))
        assertEquals(114, LlmPrompt.maxTokens("x".repeat(100)))
        assertEquals(1024, LlmPrompt.maxTokens("x".repeat(100_000)))
    }

    @Test
    fun vocabularyIsAddedToTheInstruction() {
        assertEquals("Fix it.", LlmPrompt.withVocabulary("Fix it.", emptyList()))
        val withWords = LlmPrompt.withVocabulary("Fix it.", listOf("Priya", " Kubernetes ", "Priya"))
        assertTrue(withWords.startsWith("Fix it. "))
        assertTrue(withWords.endsWith(": Priya, Kubernetes."))
        val many = LlmPrompt.withVocabulary("x", (1..500).map { "w$it" })
        assertTrue(many.contains("w${LlmPrompt.MAX_VOCABULARY}."))
        assertTrue(!many.contains("w${LlmPrompt.MAX_VOCABULARY + 1}"))
    }
}
