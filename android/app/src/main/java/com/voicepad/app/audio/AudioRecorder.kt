package com.voicepad.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Captures the microphone as 16 kHz mono float samples (what Whisper and
 * Parakeet expect), keeps them in memory for live transcription and writes
 * a 16-bit WAV file alongside.
 */
class AudioRecorder {
    companion object {
        const val SAMPLE_RATE = 16_000
    }

    private val lock = Any()
    private var samples = FloatArray(SAMPLE_RATE * 60)
    private var count = 0

    @Volatile var level: Float = 0f
        private set

    @Volatile var isRunning = false
        private set

    /** Set if the audio file could not be written (e.g. storage full). */
    @Volatile var writeError: String? = null
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var wav: RandomAccessFile? = null
    private var wavDataBytes = 0L

    val sampleCount: Int get() = synchronized(lock) { count }

    /** Copy of captured samples in [from, to), clamped to what exists. */
    fun samples(from: Int, to: Int): FloatArray = synchronized(lock) {
        val lower = from.coerceIn(0, count)
        val upper = to.coerceIn(lower, count)
        samples.copyOfRange(lower, upper)
    }

    @SuppressLint("MissingPermission") // Checked by the caller.
    fun start(file: File) {
        if (isRunning) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            max(minBuffer, SAMPLE_RATE * 4 / 5), // ~200 ms of float samples
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable." }

        synchronized(lock) { count = 0 }
        level = 0f
        writeError = null
        wav = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(WavFile.header(0))
        }
        wavDataBytes = 0

        recorder.startRecording()
        record = recorder
        isRunning = true
        thread = Thread({ readLoop(recorder) }, "VoicePad-recorder").apply { start() }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        thread?.join(1000)
        thread = null
        record?.run { stop(); release() }
        record = null
        level = 0f
        wav?.let {
            runCatching {
                WavFile.writeHeader(it, wavDataBytes)
                it.fd.sync()
            }.onFailure { e -> writeError = e.message ?: "Could not save audio." }
            runCatching { it.close() }
        }
        wav = null
    }

    private fun readLoop(recorder: AudioRecord) {
        val buffer = FloatArray(SAMPLE_RATE / 10) // 100 ms
        val pcm = ByteArray(buffer.size * 2)
        var buffersSinceCheckpoint = 0
        while (isRunning) {
            val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) continue

            var sumSquares = 0f
            for (i in 0 until read) {
                val s = buffer[i]
                sumSquares += s * s
                val v = (min(1f, max(-1f, s)) * Short.MAX_VALUE).toInt()
                pcm[2 * i] = (v and 0xff).toByte()
                pcm[2 * i + 1] = ((v shr 8) and 0xff).toByte()
            }
            val rms = sqrt(sumSquares / read)
            val db = 20 * log10(max(rms, 1e-6f))
            level = ((db + 50f) / 50f).coerceIn(0f, 1f)

            synchronized(lock) {
                if (count + read > samples.size) samples = samples.copyOf(max(samples.size * 2, count + read))
                System.arraycopy(buffer, 0, samples, count, read)
                count += read
            }
            if (writeError == null) wav?.let { file ->
                try {
                    file.write(pcm, 0, read * 2)
                } catch (e: java.io.IOException) {
                    // Keep capturing into memory so live text still works.
                    writeError = e.message ?: "Could not save audio."
                    return@let
                }
                wavDataBytes += read * 2
                // Every ~5 s: fix up the header and flush to disk, so the
                // file stays playable even if the app is killed right now.
                if (++buffersSinceCheckpoint >= 50) {
                    buffersSinceCheckpoint = 0
                    runCatching {
                        WavFile.writeHeader(file, wavDataBytes)
                        file.fd.sync()
                    }
                }
            }
        }
    }
}
