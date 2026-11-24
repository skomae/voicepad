package com.voicepad.app.llm

import com.voicepad.app.transcription.EngineChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmGuidanceTest {

    private val midPhone = DeviceSpecs(totalRamGb = 4.0, cores = 8, soc = "test")
    private val lowRamPhone = DeviceSpecs(totalRamGb = 3.0, cores = 4, soc = "test")

    @Test
    fun heavyAsrTightensTheBudget() {
        assertEquals(
            Guidance.OK,
            LlmGuidance.advice(midPhone, EngineChoice.WHISPER_TURBO, LlmChoice.QWEN_0_5B).verdict,
        )
        assertEquals(
            Guidance.TIGHT,
            LlmGuidance.advice(midPhone, EngineChoice.WHISPER_TURBO, LlmChoice.QWEN_1_5B).verdict,
        )
    }

    @Test
    fun lightAsrLeavesMoreRoom() {
        assertEquals(
            Guidance.OK,
            LlmGuidance.advice(midPhone, EngineChoice.SENSE_VOICE, LlmChoice.QWEN_1_5B).verdict,
        )
        assertEquals(
            Guidance.TIGHT,
            LlmGuidance.advice(midPhone, EngineChoice.WHISPER_TURBO, LlmChoice.QWEN_1_5B).verdict,
        )
    }

    @Test
    fun lowRamRejects() {
        assertEquals(
            Guidance.NOT_RECOMMENDED,
            LlmGuidance.advice(lowRamPhone, EngineChoice.WHISPER_TURBO, LlmChoice.QWEN_0_5B).verdict,
        )
    }

    @Test
    fun bestPicksTheSmallestAcceptableModel() {
        assertEquals(
            LlmChoice.QWEN_0_5B,
            LlmGuidance.best(midPhone, EngineChoice.WHISPER_TURBO, LlmChoice.entries),
        )
    }
}
