package com.voicepad.app.transcription

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.voicepad.app.audio.AudioRecorder
import com.voicepad.app.transcription.ChunkPlanner.Span

/**
 * Finds speech in a stream of 16 kHz audio with the Silero voice-activity
 * detector. Feed audio in order with [feed]; it returns each utterance as soon
 * as the pause after it is long enough, with positions counted from the first
 * sample fed since [reset].
 *
 * Not thread-safe: use one instance per stream.
 */
class SpeechDetector(assets: AssetManager) {
    private val vad = Vad(
        assetManager = assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                // A quarter-second gap ends an utterance: short enough to keep
                // a switch between languages out of one model call, long
                // enough not to cut between words.
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                // Longer speech without a pause is split at its quietest point.
                maxSpeechDuration = 20f,
                windowSize = WINDOW,
            ),
            sampleRate = AudioRecorder.SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    /** True while the detector is inside speech that hasn't ended yet. */
    val inSpeech: Boolean get() = vad.isSpeechDetected()

    /** Audio not yet a whole window long. */
    private var leftover = FloatArray(0)

    /**
     * Accepts any amount of audio. It is passed on in exact 512-sample windows:
     * handing the detector larger blocks shifts and merges the utterances it
     * reports (found in testing with 1.2 s blocks).
     */
    fun feed(samples: FloatArray): List<Span> {
        val all = if (leftover.isEmpty()) samples else leftover + samples
        val whole = all.size / WINDOW * WINDOW
        var at = 0
        while (at < whole) {
            vad.acceptWaveform(all.copyOfRange(at, at + WINDOW))
            at += WINDOW
        }
        leftover = all.copyOfRange(whole, all.size)
        return drain()
    }

    /** Ends the stream, returning any utterance still open. */
    fun flush(): List<Span> {
        if (leftover.isNotEmpty()) vad.acceptWaveform(leftover)
        leftover = FloatArray(0)
        vad.flush()
        return drain()
    }

    fun release() = vad.release()

    private companion object {
        const val WINDOW = 512
    }

    private fun drain(): List<Span> {
        val out = mutableListOf<Span>()
        while (!vad.empty()) {
            val segment = vad.front()
            out += Span(segment.start.toInt(), segment.start.toInt() + segment.samples.size)
            vad.pop()
        }
        return out
    }
}
