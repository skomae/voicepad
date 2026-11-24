package com.voicepad.app.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.audiofx.NoiseSuppressor
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
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
 *
 * It can also record several channels at once (a stereo wireless mic kit, a
 * multi-channel USB interface, or a phone's own two mics): each channel is
 * kept separately for [channelSamples], while [samples] and the WAV get
 * their average.
 */
class AudioRecorder {
    companion object {
        const val SAMPLE_RATE = 16_000
        /** Most channels recorded at once (USB interfaces can offer many). */
        const val MAX_CHANNELS = 8

        /**
         * Microphone setting for close speech or for the whole room. For the
         * room: raw, unprocessed audio where the phone offers it, otherwise
         * the camcorder setting (tuned for distant sound).
         */
        fun sourceFor(context: android.content.Context, backgroundVoices: Boolean): Int {
            if (!backgroundVoices) return MediaRecorder.AudioSource.VOICE_RECOGNITION
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            val unprocessed = audio.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.CAMCORDER
        }
    }

    private val lock = Any()
    /** Guards the WAV file, so [switchFile] lands exactly between two buffers. */
    private val wavLock = Any()
    private var samples = FloatArray(SAMPLE_RATE * 60)
    /** Each channel's own samples (same positions as [samples]); null when recording one channel. */
    private var perChannel: Array<FloatArray>? = null
    private var count = 0
    /**
     * Position (in the whole recording) of samples[0]. Audio already
     * transcribed is dropped from memory with [discardBefore]; it's on disk.
     */
    private var offset = 0

    @Volatile var level: Float = 0f
        private set

    /** Live level 0..1 of each channel (one entry when recording mono). */
    @Volatile var channelLevels: FloatArray = FloatArray(1)
        private set

    /** Channels actually being recorded (Android may give fewer than asked for). */
    @Volatile var channelCount: Int = 1
        private set
    private var requestedChannels = 1

    @Volatile var isRunning = false
        private set

    /** Set if the audio file could not be written (e.g. storage full). */
    @Volatile var writeError: String? = null
        private set

    /** Name of the mic actually in use, as reported by Android's routing. */
    @Volatile var routedDeviceName: String? = null
        private set

    /** Called (on the main thread) when Android moves recording to another mic. */
    var onRoutingChanged: (() -> Unit)? = null

    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var wav: RandomAccessFile? = null
    private var wavDataBytes = 0L

    /** Samples recorded so far (including ones discarded from memory). */
    val sampleCount: Int get() = synchronized(lock) { offset + count }

    /** Copy of captured samples in [from, to) (recording positions), clamped to what is still in memory. */
    fun samples(from: Int, to: Int): FloatArray = synchronized(lock) {
        val lower = (from - offset).coerceIn(0, count)
        val upper = (to - offset).coerceIn(lower, count)
        samples.copyOfRange(lower, upper)
    }

    /** Like [samples], for one channel; the mono mix when recording a single channel. */
    fun channelSamples(channel: Int, from: Int, to: Int): FloatArray = synchronized(lock) {
        val source = perChannel?.getOrNull(channel) ?: samples
        val lower = (from - offset).coerceIn(0, count)
        val upper = (to - offset).coerceIn(lower, count)
        source.copyOfRange(lower, upper)
    }

    /**
     * Frees memory for audio before [position] (it's already transcribed and
     * saved in the WAV), so hour-long recordings don't fill RAM.
     */
    fun discardBefore(position: Int) = synchronized(lock) {
        val drop = (position - offset).coerceIn(0, count)
        if (drop < SAMPLE_RATE * 30) return@synchronized // not worth copying yet
        System.arraycopy(samples, drop, samples, 0, count - drop)
        perChannel?.forEach { System.arraycopy(it, drop, it, 0, count - drop) }
        count -= drop
        offset += drop
        if (samples.size > SAMPLE_RATE * 120 && count < samples.size / 4) {
            samples = samples.copyOf(samples.size / 2)
            perChannel = perChannel?.map { it.copyOf(samples.size) }?.toTypedArray()
        }
    }

    private var preferred: AudioDeviceInfo? = null
    private var source = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private var noiseSuppressor: NoiseSuppressor? = null

    /**
     * @param audioSource from [sourceFor]: close-talk voice recognition
     *   normally, room-sound capture for background voices.
     * @param channels how many microphone channels to record; throws if the
     *   microphone can't provide that many (check [channelCount] afterwards,
     *   Android may also quietly give fewer).
     */
    fun start(
        file: File,
        preferredDevice: AudioDeviceInfo? = null,
        audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        channels: Int = 1,
    ) {
        if (isRunning) return
        preferred = preferredDevice
        source = audioSource
        requestedChannels = channels.coerceIn(1, MAX_CHANNELS)
        val recorder = openRecord()
        channelCount = recorder.channelCount.coerceIn(1, MAX_CHANNELS)
        channelLevels = FloatArray(channelCount)

        synchronized(lock) {
            count = 0
            offset = 0
            perChannel = if (channelCount > 1) Array(channelCount) { FloatArray(samples.size) } else null
        }
        level = 0f
        writeError = null
        wav = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(WavFile.header(0))
        }
        wavDataBytes = 0

        isRunning = true
        startCapture(recorder)
    }

    /**
     * Switches the microphone processing mid-recording (e.g. background
     * voices on/off). Capture pauses for about a tenth of a second; the
     * recording, its file and its positions carry on.
     */
    fun switchSource(audioSource: Int) {
        if (audioSource == source) return
        source = audioSource
        if (!isRunning) return
        stopCapture()
        startCapture(openRecord())
    }

    @SuppressLint("MissingPermission") // Checked by the caller.
    private fun openRecord(): AudioRecord {
        val n = requestedChannels
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .apply {
                when (n) {
                    1 -> setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    2 -> setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    // Beyond stereo, channels are numbered rather than positioned (USB interfaces).
                    else -> setChannelIndexMask((1 shl n) - 1)
                }
            }
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, if (n == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(0) * max(1, n / 2)
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuffer, SAMPLE_RATE * 4 * n / 5)) // ~200 ms of float samples
                .build()
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException(if (n > 1) "This microphone can't record $n channels." else "Microphone is unavailable.", e)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error(if (n > 1) "This microphone can't record $n channels." else "Microphone is unavailable.")
        }
        recorder.setPreferredDevice(preferred)
        recorder.addOnRoutingChangedListener(
            AudioRouting.OnRoutingChangedListener { updateRouting(it) },
            Handler(Looper.getMainLooper()),
        )
        // Noise suppression is tuned for one nearby talker and treats a distant
        // voice as noise to remove; turn it off when capturing the room.
        noiseSuppressor?.release()
        noiseSuppressor = null
        if (source != MediaRecorder.AudioSource.VOICE_RECOGNITION && NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = false }
            }.getOrNull()
        }
        return recorder
    }

    private fun startCapture(recorder: AudioRecord) {
        recorder.startRecording()
        record = recorder
        updateRouting(recorder)
        thread = Thread({ readLoop(recorder) }, "VoicePad-recorder").apply { start() }
    }

    private fun stopCapture() {
        val old = record
        record = null // the read loop stops after its current buffer
        thread?.join(1000)
        thread = null
        old?.run { stop(); release() }
        noiseSuppressor?.release()
        noiseSuppressor = null
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        stopCapture()
        routedDeviceName = null
        level = 0f
        channelLevels = FloatArray(channelCount)
        synchronized(wavLock) {
            wav?.let {
                runCatching {
                    WavFile.writeHeader(it, wavDataBytes)
                    it.fd.sync()
                }.onFailure { e -> writeError = e.message ?: "Could not save audio." }
                runCatching { it.close() }
            }
            wav = null
        }
    }

    /**
     * Splits the recording without stopping the microphone: finishes the
     * current WAV and continues into [file]. Returns the recording position of
     * the split; audio before it is in the old file, audio after it in the new.
     */
    fun switchFile(file: File): Int = synchronized(wavLock) {
        wav?.let { old ->
            runCatching {
                WavFile.writeHeader(old, wavDataBytes)
                old.fd.sync()
            }.onFailure { e -> writeError = e.message ?: "Could not save audio." }
            runCatching { old.close() }
        }
        writeError = null
        wav = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(WavFile.header(0))
        }
        wavDataBytes = 0
        sampleCount
    }

    /** Switches the live recording to [device] (null = system default). */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferred = device
        record?.setPreferredDevice(device)
    }

    private fun updateRouting(routing: AudioRouting) {
        val device = routing.routedDevice
        routedDeviceName = device?.let {
            InputDeviceNaming.name(it.type, it.productName?.toString().orEmpty(), it.address)
        }
        onRoutingChanged?.invoke()
    }

    private fun readLoop(recorder: AudioRecord) {
        val n = channelCount
        val frames = SAMPLE_RATE / 10 // 100 ms
        val buffer = FloatArray(frames * n)
        val mix = if (n == 1) buffer else FloatArray(frames)
        val split = if (n == 1) null else Array(n) { FloatArray(frames) }
        val channelSquares = FloatArray(n)
        val pcm = ByteArray(frames * 2)
        var buffersSinceCheckpoint = 0
        while (isRunning && record === recorder) {
            val readValues = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (readValues <= 0) continue
            val read = readValues / n
            if (read == 0) continue

            if (split != null) {
                // Interleaved L R L R… → one array per channel, plus their average.
                channelSquares.fill(0f)
                for (i in 0 until read) {
                    var sum = 0f
                    for (c in 0 until n) {
                        val v = buffer[i * n + c]
                        split[c][i] = v
                        channelSquares[c] += v * v
                        sum += v
                    }
                    mix[i] = sum / n
                }
                channelLevels = FloatArray(n) { toLevel(sqrt(channelSquares[it] / read)) }
            }

            var sumSquares = 0f
            for (i in 0 until read) {
                val s = mix[i]
                sumSquares += s * s
                val v = (min(1f, max(-1f, s)) * Short.MAX_VALUE).toInt()
                pcm[2 * i] = (v and 0xff).toByte()
                pcm[2 * i + 1] = ((v shr 8) and 0xff).toByte()
            }
            level = toLevel(sqrt(sumSquares / read))
            if (split == null) channelLevels = floatArrayOf(level)

            synchronized(wavLock) {
            synchronized(lock) {
                if (count + read > samples.size) {
                    val size = max(samples.size * 2, count + read)
                    samples = samples.copyOf(size)
                    perChannel = perChannel?.map { it.copyOf(size) }?.toTypedArray()
                }
                System.arraycopy(mix, 0, samples, count, read)
                if (split != null) perChannel?.forEachIndexed { c, target -> System.arraycopy(split[c], 0, target, count, read) }
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

    /** RMS → 0..1 for level meters (−50 dB … 0 dB). */
    private fun toLevel(rms: Float): Float {
        val db = 20 * log10(max(rms, 1e-6f))
        return ((db + 50f) / 50f).coerceIn(0f, 1f)
    }
}
