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
    fun blankInstructionFallsBackToCleanDictation() {
        assertTrue(LlmPrompt.system("  ").contains(LlmPrompt.DICTATION.instruction))
    }

    @Test
    fun twoDistinctPresets() {
        assertEquals(2, LlmPrompt.PRESETS.size)
        assertEquals(LlmPrompt.CASUAL, LlmPrompt.presetFor(LlmPrompt.CASUAL.instruction))
        assertNull(LlmPrompt.presetFor("something else"))
    }

    @Test
    fun retiredPresetsMigrateToTheirReplacements() {
        assertEquals(
            LlmPrompt.DICTATION.instruction,
            LlmPrompt.migrate(
                "Fix speech-recognition mistakes, punctuation and capitalisation. " +
                    "Keep the wording, meaning and language; don't add or remove information.",
            ),
        )
        assertEquals(
            LlmPrompt.CASUAL.instruction,
            LlmPrompt.migrate(
                "Turn this dictation into clear written text: remove filler words, false starts and repetitions, " +
                    "fix grammar, and split it into sentences and paragraphs. Keep the meaning, tone and language.",
            ),
        )
        assertEquals("my own prompt", LlmPrompt.migrate("my own prompt"))
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
        assertEquals(recent, LlmPrompt.remember(recent, LlmPrompt.DICTATION.instruction))
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
