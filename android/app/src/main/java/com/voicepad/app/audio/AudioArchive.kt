package com.voicepad.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Compresses recordings for the archive: 16 kHz mono WAV (~1.9 MB a minute)
 * to AAC in an .m4a file at 24 kbit/s (~0.18 MB a minute), which still
 * plays anywhere and transcribes fine. Decoding goes the other way, so an
 * archived note can still be re-transcribed.
 */
object AudioArchive {
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 24_000
    private const val TIMEOUT_US = 10_000L

    /**
     * Encodes [wav] into [out] and checks the result plays back at the same
     * length. Throws (and leaves no [out]) if anything is off, so the caller
     * keeps the WAV.
     */
    fun compress(wav: File, out: File) {
        val tmp = File(out.path + ".part")
        tmp.delete()
        try {
            encode(wav, tmp)
            val expectedMs = WavFile.durationSeconds(wav) * 1000
            val actualMs = durationMs(tmp)
            check(abs(actualMs - expectedMs) <= maxOf(500.0, expectedMs * 0.02)) {
                "Compressed audio is ${actualMs.toLong()} ms, expected ${expectedMs.toLong()} ms."
            }
            check(tmp.renameTo(out)) { "Could not save the compressed audio." }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun durationMs(file: File): Double {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0
        } finally {
            retriever.release()
        }
    }

    private fun encode(wav: File, out: File) {
        val rate = WavFile.SAMPLE_RATE
        val format = MediaFormat.createAudioFormat(MIME, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val total = WavFile.sampleCount(wav)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var muxing = false
        var fed = 0
        var inputDone = false
        RandomAccessFile(wav, "r").use { input ->
            input.seek(WavFile.HEADER_BYTES.toLong())
            try {
                while (true) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            val samples = minOf(buffer.remaining() / 2, total - fed)
                            val bytes = ByteArray(samples * 2)
                            input.readFully(bytes)
                            buffer.put(bytes)
                            val timeUs = fed * 1_000_000L / rate
                            fed += samples
                            if (fed >= total) {
                                codec.queueInputBuffer(index, 0, bytes.size, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, bytes.size, timeUs, 0)
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                        outIndex >= 0 -> {
                            val data = codec.getOutputBuffer(outIndex)!!
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && muxing && !isConfig) {
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                muxer.writeSampleData(track, data, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
                if (muxing) runCatching { muxer.stop() }
                muxer.release()
            }
        }
    }

    /** Decodes an archived .m4a back to the 16 kHz mono WAV the transcriber reads. */
    fun decodeToWav(source: File, wav: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        val trackIndex = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var outRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var outChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outChannels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (outIndex >= 0) {
                    val data = codec.getOutputBuffer(outIndex)!!
                    val chunk = ByteArray(info.size)
                    data.position(info.offset)
                    data.get(chunk)
                    pcm.write(chunk)
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        // To 16 kHz mono (the decoder normally gives that back already).
        val shorts = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = shorts.remaining() / outChannels
        val mono = FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until outChannels) sum += shorts.get(f * outChannels + c) / 32768f
            sum / outChannels
        }
        val resampled = if (outRate == WavFile.SAMPLE_RATE) mono else {
            val n = (mono.size.toLong() * WavFile.SAMPLE_RATE / outRate).toInt()
            FloatArray(n) { i ->
                val pos = i.toDouble() * outRate / WavFile.SAMPLE_RATE
                val j = pos.toInt().coerceAtMost(mono.size - 1)
                val k = (j + 1).coerceAtMost(mono.size - 1)
                val t = (pos - j).toFloat()
                mono[j] * (1 - t) + mono[k] * t
            }
        }
        val bytes = ByteBuffer.allocate(resampled.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in resampled) bytes.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        RandomAccessFile(wav, "rw").use {
            it.setLength(0)
            it.write(WavFile.header(bytes.capacity().toLong()))
            it.write(bytes.array())
        }
    }
}
