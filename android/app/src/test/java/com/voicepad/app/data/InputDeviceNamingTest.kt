package com.voicepad.app.data

import android.media.AudioDeviceInfo
import com.voicepad.app.audio.InputDeviceNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputDeviceNamingTest {
    @Test
    fun `microphone types get readable kinds and others are hidden`() {
        assertEquals("Built-in microphone", InputDeviceNaming.kind(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals("Bluetooth headset", InputDeviceNaming.kind(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals("Bluetooth LE headset", InputDeviceNaming.kind(26))
        assertEquals("USB headset", InputDeviceNaming.kind(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertNull(InputDeviceNaming.kind(AudioDeviceInfo.TYPE_TELEPHONY))
        assertNull(InputDeviceNaming.kind(AudioDeviceInfo.TYPE_FM_TUNER))
    }

    @Test
    fun `external devices use their product name`() {
        assertEquals("Pixel Buds Pro", InputDeviceNaming.name(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Pixel Buds Pro", "AA:BB:CC:DD:EE:FF"))
        assertEquals("USB microphone", InputDeviceNaming.name(AudioDeviceInfo.TYPE_USB_DEVICE, "  ", "card=1"))
    }

    @Test
    fun `several built-in mics are told apart by address`() {
        assertEquals("Built-in microphone (bottom)", InputDeviceNaming.name(AudioDeviceInfo.TYPE_BUILTIN_MIC, "Pixel 9", "bottom"))
        assertEquals("Built-in microphone", InputDeviceNaming.name(AudioDeviceInfo.TYPE_BUILTIN_MIC, "Pixel 9", ""))
    }

    @Test
    fun `key is stable across reconnects and distinguishes devices`() {
        val a = InputDeviceNaming.key(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB")
        assertEquals(a, InputDeviceNaming.key(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB"))
        assertFalse(a == InputDeviceNaming.key(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "CC:DD"))
        assertTrue(InputDeviceNaming.isBluetooth(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertFalse(InputDeviceNaming.isBluetooth(AudioDeviceInfo.TYPE_WIRED_HEADSET))
    }
}
