package com.voicepad.app.transmit

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the "type as keyboard" sink is doing. */
enum class SinkStatus { UNAVAILABLE, IDLE, REGISTERING, ADVERTISING, CONNECTED, FAILED }

/** [host] is the connected device's name while [status] is CONNECTED. */
data class SinkState(val status: SinkStatus, val error: String? = null, val host: String? = null)

/**
 * A device the keyboard could connect to. [isKnown]: VoicePad has typed into
 * it before. [isBonded]: paired with the phone (unpaired ones must be paired
 * again first). [isLast]: the most recently used.
 */
data class KeyboardHost(
    val address: String,
    val name: String,
    val isComputer: Boolean,
    val isLast: Boolean,
    val isKnown: Boolean = false,
    val isBonded: Boolean = true,
)

/** Looking for new computers to pair with. [pairing] is the address being paired. */
data class ScanState(
    val scanning: Boolean = false,
    val found: List<KeyboardHost> = emptyList(),
    val pairing: String? = null,
)

/** Output side of "type as keyboard": advertises a HID keyboard and sends text. */
interface KeyboardSink {
    val state: StateFlow<SinkState>
    fun start()
    fun stop()
    suspend fun type(text: String)

    /** Paired devices, computers and the last-used host first. Empty without permission. */
    fun hosts(): List<KeyboardHost>

    /**
     * Connects to an already-paired host from the phone side, so the host
     * never has to discover the phone. Only valid once registered.
     */
    fun connect(address: String): Boolean

    /** Nearby unpaired devices while [startScan] runs. */
    val scan: StateFlow<ScanState>

    /** Starts looking for nearby computers (needs scan permission). False if it couldn't start. */
    fun startScan(): Boolean
    fun stopScan()

    /** Pairs with [address] (both sides confirm a code), then connects as a keyboard. */
    fun pair(address: String): Boolean

    /** Removes [address] from the remembered computers (it stays paired with the phone). */
    fun forget(address: String)
}

/** Creates the only supported sink (Bluetooth HID device), or an inert one below API 30. */
fun createKeyboardSink(context: Context): KeyboardSink =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BluetoothKeyboardSink(context) else UnavailableSink

private object UnavailableSink : KeyboardSink {
    override val state = MutableStateFlow(SinkState(SinkStatus.UNAVAILABLE))
    override fun start() {}
    override fun stop() {}
    override suspend fun type(text: String) {}
    override fun hosts() = emptyList<KeyboardHost>()
    override fun connect(address: String) = false
    override val scan = MutableStateFlow(ScanState())
    override fun startScan() = false
    override fun stopScan() {}
    override fun pair(address: String) = false
    override fun forget(address: String) {}
}
