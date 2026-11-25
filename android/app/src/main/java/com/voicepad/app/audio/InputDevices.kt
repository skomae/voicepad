package com.voicepad.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A microphone the user can record from. */
data class InputDevice(
    /** Stable across reconnects (type + address), used to remember the choice. */
    val key: String,
    val name: String,
    val kind: String,
    val isBluetooth: Boolean,
    /** Most channels it reports it can record at once (Bluetooth call audio is always 1). */
    val maxChannels: Int = 1,
)

/** Pure helpers for naming and identifying input devices (unit-tested). */
object InputDeviceNaming {
    // Literal values so the list also covers types newer than minSdk.
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_REMOTE_SUBMIX = 25

    fun kind(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB microphone"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line in"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line in"
        AudioDeviceInfo.TYPE_DOCK -> "Dock microphone"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI input"
        // Telephony, FM tuner, echo reference, remote submix… aren't microphones.
        TYPE_REMOTE_SUBMIX -> null
        else -> null
    }

    fun isBluetooth(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == TYPE_BLE_HEADSET

    fun key(type: Int, address: String): String = "$type:$address"

    /**
     * The device's address where the platform exposes one (API 28+). Below that
     * the address isn't part of the public API at all, so devices of the same
     * type share a key — which is right: they can't be told apart on 26/27.
     */
    fun addressOf(device: AudioDeviceInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else ""

    /**
     * Channels a mic can record, from what Android reports: channel counts,
     * index masks (numbered channels) and position masks (bit counts). Mics
     * that report nothing are assumed to manage stereo, as most phones do.
     */
    fun maxChannels(type: Int, counts: IntArray, indexMasks: IntArray, positionMasks: IntArray): Int {
        if (isBluetooth(type)) return 1
        val reported = listOf(
            counts.maxOrNull() ?: 0,
            indexMasks.maxOfOrNull(Integer::bitCount) ?: 0,
            positionMasks.maxOfOrNull(Integer::bitCount) ?: 0,
        ).max()
        return if (reported == 0) 2 else reported.coerceIn(1, 8)
    }

    /**
     * What to show the user. Phones often report their own model name for the
     * built-in mic, and some list several (bottom, back) by address.
     */
    fun name(type: Int, productName: String, address: String): String {
        val kind = kind(type) ?: "Microphone"
        val product = productName.trim()
        return when {
            type == AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                if (address.isNotBlank() && !address.contains(':')) "$kind ($address)" else kind
            product.isEmpty() || product.equals(Build.MODEL, ignoreCase = true) -> kind
            else -> product
        }
    }
}

/**
 * Tracks the connected microphones and routes recording to the chosen one.
 * Bluetooth headset mics only carry audio once the headset is switched into
 * its call (SCO) mode, which [beginRecording] does and [endRecording] undoes.
 */
class AudioInputs(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _devices = MutableStateFlow(scan())
    val devices: StateFlow<List<InputDevice>> = _devices.asStateFlow()

    private var bluetoothActive = false

    /** Called (on the main thread) whenever a mic is plugged in or removed. */
    var onDevicesChanged: (() -> Unit)? = null

    init {
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
        }, Handler(Looper.getMainLooper()))
    }

    private fun refresh() {
        _devices.value = scan()
        onDevicesChanged?.invoke()
    }

    private fun inputs(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { InputDeviceNaming.kind(it.type) != null }

    private fun scan(): List<InputDevice> = inputs()
        .map {
            val address = InputDeviceNaming.addressOf(it)
            InputDevice(
                key = InputDeviceNaming.key(it.type, address),
                name = InputDeviceNaming.name(it.type, it.productName?.toString().orEmpty(), address),
                kind = InputDeviceNaming.kind(it.type)!!,
                isBluetooth = InputDeviceNaming.isBluetooth(it.type),
                maxChannels = InputDeviceNaming.maxChannels(it.type, it.channelCounts, it.channelIndexMasks, it.channelMasks),
            )
        }
        .distinctBy { it.key }
        .sortedWith(compareBy({ it.kind != "Built-in microphone" }, { it.name }))

    fun find(key: String?): AudioDeviceInfo? =
        key?.let { k -> inputs().firstOrNull { InputDeviceNaming.key(it.type, InputDeviceNaming.addressOf(it)) == k } }

    /** Device to pass to the recorder for [key], switching a Bluetooth headset into call mode if needed. */
    fun beginRecording(key: String?): AudioDeviceInfo? {
        endRecording()
        val device = find(key) ?: return null
        if (InputDeviceNaming.isBluetooth(device.type)) enableBluetoothMic(device)
        return device
    }

    fun endRecording() {
        if (!bluetoothActive) return
        bluetoothActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
    }

    private fun enableBluetoothMic(input: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The communication device is the headset's output side; selecting
            // it brings up the headset's microphone too.
            val output = audioManager.availableCommunicationDevices.firstOrNull {
                it.address == input.address && InputDeviceNaming.isBluetooth(it.type)
            } ?: audioManager.availableCommunicationDevices.firstOrNull { InputDeviceNaming.isBluetooth(it.type) }
            if (output != null && audioManager.setCommunicationDevice(output)) bluetoothActive = true
        } else {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            bluetoothActive = true
        }
    }
}
