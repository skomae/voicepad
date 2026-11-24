package com.voicepad.app.llm

import com.voicepad.app.transcription.EngineChoice

enum class Guidance { RECOMMENDED, OK, TIGHT, NOT_RECOMMENDED }

data class LlmAdvice(val verdict: Guidance, val reason: String)

/**
 * Device-aware model recommendation. Pure and unit-tested: maps the phone's
 * RAM and the currently selected speech model to a per-LLM verdict.
 */
object LlmGuidance {
    /** RAM reserved for the OS and the foreground app (GB). */
    const val OS_RESERVE_GB = 2.0

    fun advice(specs: DeviceSpecs, asr: EngineChoice, choice: LlmChoice): LlmAdvice {
        val available = specs.totalRamGb - OS_RESERVE_GB - asrFootprintGb(asr)
        val headroom = available - choice.inferenceRamGb
        val verdict = when {
            headroom >= 1.5 -> Guidance.RECOMMENDED
            headroom >= 0.5 -> Guidance.OK
            headroom >= 0.0 -> Guidance.TIGHT
            else -> Guidance.NOT_RECOMMENDED
        }
        val reason = when (verdict) {
            Guidance.RECOMMENDED -> "Comfortable headroom on this phone (${gb(headroom)} free)."
            Guidance.OK -> "Fits, with about ${gb(headroom)} to spare."
            Guidance.TIGHT -> "Fits but may stutter live transcription while the LLM runs."
            Guidance.NOT_RECOMMENDED -> "Too large to run alongside the speech model here."
        }
        return LlmAdvice(verdict, reason)
    }

    /** Smallest model that is at least OK on this device, or null if none fit. */
    fun best(specs: DeviceSpecs, asr: EngineChoice, candidates: List<LlmChoice>): LlmChoice? =
        candidates
            .sortedBy { it.inferenceRamGb }
            .firstOrNull { advice(specs, asr, it).verdict != Guidance.NOT_RECOMMENDED }

    /** Approximate resident weight size for each speech model (GB). */
    private fun asrFootprintGb(choice: EngineChoice): Double = when (choice) {
        EngineChoice.PARAKEET_V3 -> 0.49
        EngineChoice.AUTO_ASIAN -> 0.65
        EngineChoice.PARAKEET_V2 -> 0.48
        EngineChoice.SENSE_VOICE -> 0.17
        EngineChoice.WHISPER_TINY_EN -> 0.12
        EngineChoice.WHISPER_BASE_EN -> 0.21
        EngineChoice.WHISPER_SMALL_EN -> 0.64
        EngineChoice.WHISPER_SMALL -> 0.64
        EngineChoice.WHISPER_TURBO -> 0.56
    }

    private fun gb(v: Double) = "%.1f GB".format(v)
}
