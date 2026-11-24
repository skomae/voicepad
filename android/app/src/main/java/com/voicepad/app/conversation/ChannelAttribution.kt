package com.voicepad.app.conversation

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * With one microphone per person (a stereo wireless kit, a multi-channel USB
 * interface), each person's voice also leaks into the other mics, more
 * quietly. Every channel's voice detector hears it, so an utterance is kept
 * only on the channel where it is loudest. Pure Kotlin.
 */
object ChannelAttribution {
    /** 50 ms frames at 16 kHz. */
    const val FRAME = 800

    /** Frames quieter than this on every channel are ignored. */
    private const val SILENCE_DB = -55f

    /** Level in dB of each [frame]-sample frame of [samples]. */
    fun frameLevels(samples: FloatArray, frame: Int = FRAME): FloatArray {
        val frames = samples.size / frame
        return FloatArray(frames) { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += samples[i] * samples[i]
            (10 * log10(max(sum / frame, 1e-12))).toFloat()
        }
    }

    /**
     * True when the utterance heard on [channel] belongs to that channel's
     * speaker: in at least [minShare] of the frames with sound, [channel] is
     * the loudest. Channels within [tieDb] of each other count as a tie,
     * won by the lower channel, so identical channels (a mono mic exposed as
     * stereo) yield each utterance once, on the first channel.
     *
     * @param levels per channel, the level of each frame (see [frameLevels])
     */
    fun owns(levels: List<FloatArray>, channel: Int, tieDb: Float = 1f, minShare: Float = 0.4f): Boolean {
        val frames = levels.minOfOrNull { it.size } ?: return true
        if (levels.size < 2) return true
        var active = 0
        var won = 0
        for (f in 0 until frames) {
            val loudest = levels.maxOf { it[f] }
            if (loudest < SILENCE_DB) continue
            active++
            val winner = levels.indices.first { levels[it][f] >= loudest - tieDb }
            if (winner == channel) won++
        }
        if (active == 0) return channel == 0
        return won >= minShare * active
    }

    /**
     * Average gap in dB between the loudest and the second-loudest channel
     * over frames with sound. Near 0 means the channels carry the same sound
     * and can't tell people apart.
     */
    fun separationDb(levels: List<FloatArray>): Float? {
        if (levels.size < 2) return null
        val frames = levels.minOf { it.size }
        var total = 0f
        var active = 0
        for (f in 0 until frames) {
            val sorted = levels.map { it[f] }.sortedDescending()
            if (sorted[0] < SILENCE_DB) continue
            total += abs(sorted[0] - sorted[1])
            active++
        }
        return if (active == 0) null else total / active
    }
}
