package com.voicepad.app.crash

import com.voicepad.app.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TombstoneTextTest {
    @Test fun keepsTheLinesThatSayWhatCrashed() {
        // Text fields separated by binary protobuf framing, as in a real tombstone.
        val parts = listOf("SIGSEGV", "SEGV_MAPERR", "xx", "/data/app/com.voicepad.app/lib/arm64/libsherpa-onnx-jni.so",
            "_ZN6sherpa4onnx3Vad14AcceptWaveformEPKfi", "random noise text", "libaudioclient.so", "android::AudioRecord::read")
        val bytes = parts.flatMap { it.toByteArray().toList() + listOf(0x12.toByte(), 0x00.toByte()) }.toByteArray()
        val text = CrashLog.tombstoneText(bytes)
        assertEquals(
            listOf("SIGSEGV", "SEGV_MAPERR", "/data/app/com.voicepad.app/lib/arm64/libsherpa-onnx-jni.so",
                "_ZN6sherpa4onnx3Vad14AcceptWaveformEPKfi", "libaudioclient.so", "android::AudioRecord::read"),
            text.split("\n"),
        )
    }

    @Test fun saysSoWhenNothingIsReadable() {
        assertTrue(CrashLog.tombstoneText(byteArrayOf(1, 2, 3)).contains("no readable"))
    }
}
