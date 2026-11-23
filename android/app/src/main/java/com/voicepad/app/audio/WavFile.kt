package com.voicepad.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz, mono, 16-bit PCM WAV helpers. No Android dependencies, so unit-testable. */
object WavFile {
    const val SAMPLE_RATE = 16_000
    const val HEADER_BYTES = 44

    fun header(dataBytes: Long): ByteArray {
        val data = dataBytes.coerceIn(0, Int.MAX_VALUE.toLong() - 36).toInt()
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }.array()
    }

    /** Rewrites the header in place, leaving the file pointer at the end. */
    fun writeHeader(file: RandomAccessFile, dataBytes: Long) {
        file.seek(0)
        file.write(header(dataBytes))
        file.seek(file.length())
    }

    /**
     * Makes a WAV readable after the app died mid-recording: sizes the header
     * from whatever audio reached the disk. Returns the duration in seconds.
     */
    fun repair(file: File): Double {
        if (!file.exists() || file.length() < HEADER_BYTES) return 0.0
        val dataBytes = (file.length() - HEADER_BYTES) / 2 * 2 // whole samples only
        RandomAccessFile(file, "rw").use {
            it.setLength(HEADER_BYTES + dataBytes)
            writeHeader(it, dataBytes)
        }
        return dataBytes / 2.0 / SAMPLE_RATE
    }

    fun durationSeconds(file: File): Double =
        if (file.length() <= HEADER_BYTES) 0.0 else (file.length() - HEADER_BYTES) / 2 / SAMPLE_RATE.toDouble()

    /** Reads samples as floats in -1..1, ignoring the header's size fields. */
    fun read(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size <= HEADER_BYTES) return FloatArray(0)
        val n = (bytes.size - HEADER_BYTES) / 2
        return FloatArray(n) { i ->
            val lo = bytes[HEADER_BYTES + 2 * i].toInt() and 0xff
            val hi = bytes[HEADER_BYTES + 2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }
}
