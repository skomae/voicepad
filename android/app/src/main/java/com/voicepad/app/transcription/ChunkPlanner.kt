package com.voicepad.app.transcription

/**
 * Decides how to feed long audio to a speech model. Pure Kotlin so it can be
 * unit-tested.
 *
 * Input is where the voice-activity detector heard speech. Output is a list
 * of chunks, each a list of audio spans to join together:
 * - silence is trimmed: only speech (plus a little padding) is kept, so long
 *   pauses and empty stretches never reach the model (and Whisper can't
 *   "hallucinate" text into silence);
 * - with [Grouping.UTTERANCES] every utterance (speech between pauses) is its
 *   own chunk. Best for models whose cost grows with audio length (Parakeet,
 *   SenseVoice), and it keeps one language per model call, which matters:
 *   Parakeet can drop a sentence when English and French share one call;
 * - with [Grouping.LONGEST_PAUSES] utterances are packed together, and a
 *   stretch too long for one model call is split at its **longest pause**,
 *   then the next longest, and so on, until every chunk fits. Best for
 *   Whisper, which costs the same for 1 s or 30 s of audio;
 * - continuous speech longer than the limit, with no pause at all, is cut by
 *   [plan]'s `cutInside` (the quietest moment in a window), as a last resort.
 */
object ChunkPlanner {
    /** Samples [start, end). */
    data class Span(val start: Int, val end: Int) {
        val length get() = end - start
    }

    enum class Grouping { UTTERANCES, LONGEST_PAUSES }

    fun length(chunk: List<Span>) = chunk.sumOf { it.length }

    /**
     * @param speech speech regions from the VAD, in order.
     * @param audioEnd total samples available (padding is clamped to it).
     * @param maxLength longest chunk, in samples, one model call should get.
     * @param pad samples of context kept either side of each speech region.
     * @param cutInside picks a cut point in [from, to) for speech with no pause.
     */
    fun plan(
        speech: List<Span>,
        audioEnd: Int,
        maxLength: Int,
        pad: Int,
        grouping: Grouping = Grouping.LONGEST_PAUSES,
        cutInside: (from: Int, to: Int) -> Int = { from, to -> (from + to) / 2 },
    ): List<List<Span>> {
        val padded = padWithoutJoining(speech, audioEnd, pad)
        if (padded.isEmpty()) return emptyList()
        return when (grouping) {
            Grouping.LONGEST_PAUSES -> split(padded, maxLength, cutInside)
            Grouping.UTTERANCES -> padded.flatMap { split(listOf(it), maxLength, cutInside) }
        }
    }

    /**
     * Adds up to [pad] of context either side of each utterance, but never
     * more than half the pause to its neighbour, so two utterances separated
     * by a short pause stay separate.
     */
    private fun padWithoutJoining(speech: List<Span>, audioEnd: Int, pad: Int): List<Span> {
        val sorted = speech.filter { it.length > 0 }.sortedBy { it.start }
        val merged = mutableListOf<Span>()
        for (s in sorted) {
            val last = merged.lastOrNull()
            if (last != null && s.start <= last.end) merged[merged.lastIndex] = Span(last.start, maxOf(last.end, s.end))
            else merged += s
        }
        return merged.mapIndexed { i, s ->
            val before = if (i == 0) pad else minOf(pad, (s.start - merged[i - 1].end) / 2)
            val after = if (i == merged.lastIndex) pad else minOf(pad, (merged[i + 1].start - s.end) / 2)
            Span((s.start - before).coerceAtLeast(0), (s.end + after).coerceAtMost(audioEnd))
        }
    }

    private fun split(group: List<Span>, max: Int, cutInside: (Int, Int) -> Int): List<List<Span>> {
        if (length(group) <= max) return listOf(group)
        if (group.size == 1) {
            // One unbroken stretch of speech: cut near the limit, at the quietest point.
            val s = group[0]
            val from = s.start + max * 6 / 10
            val to = s.start + max
            val cut = cutInside(from, to).coerceIn(from, to)
            return listOf(listOf(Span(s.start, cut))) + split(listOf(Span(cut, s.end)), max, cutInside)
        }
        // Split at the longest pause between speech regions.
        val at = (0 until group.lastIndex).maxBy { group[it + 1].start - group[it].end }
        return split(group.subList(0, at + 1), max, cutInside) + split(group.subList(at + 1, group.size), max, cutInside)
    }
}
