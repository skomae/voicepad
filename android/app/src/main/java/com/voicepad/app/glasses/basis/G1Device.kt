package com.voicepad.app.glasses.basis

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.support.v18.scanner.ScanResult

/**
 * One arm of the glasses: a request/response channel over its own BLE link.
 *
 * Requests are strictly one at a time per arm (a [Mutex]), each waiting for
 * the response that starts with its command byte, or for a timeout. While
 * connected the arm gets the official app's heartbeat every 8 s and a battery
 * check every 30 s; without traffic the glasses drop the link after ~30 s.
 */
internal class G1Device(
    private val scanResult: ScanResult
) {

    /** The name the arm advertises, e.g. `G1_45_L_92333`; some firmware says `Even G1_45_L_...`. */
    @SuppressLint("MissingPermission")
    val name = scanResult.scanRecord?.deviceName ?: scanResult.device.name.orEmpty()
    val address: String = scanResult.device.address

    data class State(
        val connectionState: G1.ConnectionState = G1.ConnectionState.UNINITIALIZED,
        val batteryPercentage: Int? = null
    )

    private val writableState = MutableStateFlow(State())
    val state = writableState.asStateFlow()

    /** Lives as long as this arm is in use; cancelled by [disconnect]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: G1BLEManager? = null

    // connection ----------------------------------------------------------------------------------

    suspend fun connect(context: Context): Boolean {
        val manager = manager ?: G1BLEManager(context, ::onIncoming).also { created ->
            manager = created
            scope.launch {
                created.connectionState.collect {
                    Log.d(TAG, "$name: $it")
                    writableState.value = writableState.value.copy(connectionState = it)
                }
            }
            scope.launch {
                // (Re)started on every connection, stopped while the link is down.
                created.connectionState.collectLatest { state ->
                    if (state == G1.ConnectionState.CONNECTED) keepAlive()
                }
            }
        }
        return try {
            manager.connect(scanResult.device)
                .useAutoConnect(true)
                .retry(3)
                .timeout(30_000)
                .suspend()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "$name: couldn't connect: $e")
            false
        }
    }

    suspend fun disconnect() {
        val m = manager
        scope.cancel()
        pending?.response?.cancel()
        if (m != null) {
            runCatching { m.disconnect().suspend() }
            m.close()
        }
        writableState.value = writableState.value.copy(connectionState = G1.ConnectionState.DISCONNECTED)
    }

    // request and response ------------------------------------------------------------------------

    private class Pending(val command: Byte, val response: CompletableDeferred<ByteArray>)

    private val requestLock = Mutex()
    @Volatile private var pending: Pending? = null

    /**
     * Writes [bytes] and waits for the glasses' answer to that command;
     * null if the write failed or no answer came within [timeoutMs].
     */
    suspend fun request(bytes: ByteArray, timeoutMs: Long): ByteArray? = requestLock.withLock {
        val m = manager ?: return null
        val waiting = Pending(bytes[0], CompletableDeferred())
        pending = waiting
        try {
            if (!m.send(bytes)) return null
            withTimeoutOrNull(timeoutMs) { waiting.response.await() }
        } finally {
            if (pending === waiting) pending = null
        }
    }

    /** Sends packets one after another, each acknowledged before the next. */
    suspend fun requestAll(packets: List<ByteArray>, timeoutMs: Long): Boolean =
        packets.all { G1Protocol.accepted(request(it, timeoutMs)) }

    private fun onIncoming(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        G1Protocol.batteryLevel(bytes)?.let { level ->
            if (writableState.value.batteryPercentage != level) {
                writableState.value = writableState.value.copy(batteryPercentage = level)
            }
        }
        val waiting = pending
        if (waiting != null && waiting.command == bytes[0]) waiting.response.complete(bytes)
    }

    // keep-alive ----------------------------------------------------------------------------------

    private var heartbeatSeq = 0

    private suspend fun keepAlive() {
        var tick = 0
        while (scope.isActive) {
            if (tick % BATTERY_EVERY == 0) {
                request(G1Protocol.battery(), RESPONSE_TIMEOUT_MS)
            } else if (!G1Protocol.heartbeatAnswered(request(G1Protocol.heartbeat(heartbeatSeq++), RESPONSE_TIMEOUT_MS))) {
                // The official app retries a missed heartbeat once.
                request(G1Protocol.heartbeat(heartbeatSeq++), RESPONSE_TIMEOUT_MS)
            }
            tick++
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    private companion object {
        const val TAG = "G1Device"
        const val HEARTBEAT_INTERVAL_MS = 8_000L
        /** Every fourth beat (~30 s) asks for the battery instead. */
        const val BATTERY_EVERY = 4
        const val RESPONSE_TIMEOUT_MS = 1_500L
    }
}
