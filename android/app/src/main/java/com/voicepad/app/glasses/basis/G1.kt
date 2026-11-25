package com.voicepad.app.glasses.basis

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import kotlin.time.Duration

class G1 {

    // internals -----------------------------------------------------------------------------------

    private val right: G1Device
    private val left: G1Device

    // externally visible --------------------------------------------------------------------------

    val id: String
    val name: String

    enum class ConnectionState { UNINITIALIZED, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }
    data class State(
        val connectionState: ConnectionState,
        val batteryPercentage: Int?
    )
    val state: StateFlow<State>
    private var currentState: State? = null

    /** Holds [state]'s combination of the two arms; cancelled by [disconnect]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Identifies each page; the glasses expect it to change from one page to the next. */
    private var textSeq = 0

    // construction --------------------------------------------------------------------------------

    private constructor(right: G1Device, left: G1Device) {
        val leftName = G1Name.parse(left.name)
        this.id = "${left.address}${right.address}".filter { it != ':' }
        this.name = leftName?.channel?.let { "G1 $it" } ?: leftName?.raw ?: "G1"
        this.right = right
        this.left = left
        this.state = left.state.combine(right.state) { l, r ->
            val lConnectionState = l.connectionState
            val rConnectionState = r.connectionState
            val connectionState = when {
                lConnectionState == ConnectionState.CONNECTED && rConnectionState == ConnectionState.CONNECTED -> ConnectionState.CONNECTED
                lConnectionState == ConnectionState.DISCONNECTING || rConnectionState == ConnectionState.DISCONNECTING -> ConnectionState.DISCONNECTING
                lConnectionState == ConnectionState.DISCONNECTED && rConnectionState == ConnectionState.CONNECTED -> ConnectionState.DISCONNECTING
                lConnectionState == ConnectionState.CONNECTED && rConnectionState == ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTING
                lConnectionState == ConnectionState.CONNECTING || rConnectionState == ConnectionState.CONNECTING -> ConnectionState.CONNECTING
                lConnectionState == ConnectionState.DISCONNECTED && rConnectionState == ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
                lConnectionState == ConnectionState.ERROR || rConnectionState == ConnectionState.ERROR -> ConnectionState.ERROR
                else -> ConnectionState.UNINITIALIZED
            }

            val batteryPercentage = if(l.batteryPercentage == null || r.batteryPercentage == null) null else l.batteryPercentage.coerceAtMost(
                r.batteryPercentage
            )

            val current = currentState
            if(
                current != null &&
                connectionState == current.connectionState &&
                batteryPercentage == current.batteryPercentage
            ) {
                current
            } else {
                val newState = State(
                    connectionState = connectionState,
                    batteryPercentage = batteryPercentage
                )
                Log.d("G1", "G1_STATE - composing - ${l} and ${r} = ${newState}")
                currentState = newState
                newState
            }
        }.stateIn(scope, SharingStarted.Eagerly, State(ConnectionState.UNINITIALIZED, null))
    }

    // connect / disconnect ------------------------------------------------------------------------

    suspend fun connect(context: Context) = coroutineScope<Boolean> {
        if(state.value.connectionState == ConnectionState.CONNECTED) {
            true
        } else {
            val results = awaitAll(
                async { left.connect(context) },
                async { right.connect(context) }
            )
            if (results[0] == true && results[1] == true) {
                true
            } else {
                left.disconnect()
                right.disconnect()
                false
            }
        }
    }

    suspend fun disconnect() {
        left.disconnect()
        right.disconnect()
        scope.cancel()
    }

    // requests ------------------------------------------------------------------------------------

    /**
     * Shows [page] (lines joined with `\n`) as one 0x4E "text show" page:
     * every packet to the left arm, each acknowledged, then the same to the
     * right — the order Even Realities' own app uses.
     */
    suspend fun displayTextPage(page: List<String>): Boolean {
        val packets = G1Protocol.text(page.joinToString("\n"), seq = textSeq++ and 0xFF)
        return left.requestAll(packets, TEXT_TIMEOUT_MS) && right.requestAll(packets, TEXT_TIMEOUT_MS)
    }

    /** Back to the dashboard. */
    suspend fun stopDisplaying(): Boolean =
        G1Protocol.accepted(left.request(G1Protocol.exit(), EXIT_TIMEOUT_MS)) &&
            G1Protocol.accepted(right.request(G1Protocol.exit(), EXIT_TIMEOUT_MS))

    // find devices --------------------------------------------------------------------------------

    companion object {
        private const val TEXT_TIMEOUT_MS = 2_000L
        private const val EXIT_TIMEOUT_MS = 1_500L

        @SuppressLint("MissingPermission")
        fun find(duration: Duration) = callbackFlow<G1?> {
            data class FoundPair(
                val left: ScanResult? = null,
                val right: ScanResult? = null
            )

            val scanner = BluetoothLeScannerCompat.getScanner()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            // Arms already paired into one G1, so a pair found once isn't announced again.
            val pairedChannels = mutableSetOf<String>()
            val foundPairs = mutableMapOf<String, FoundPair>()
            /** The advertised name: newer firmware prefixes it with "Even ". */
            fun nameOf(result: ScanResult): String? = result.scanRecord?.deviceName ?: result.device.name
            val callback = object: ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    Log.v("G1", "onScanResult: ${result.device.name ?: "(unnamed)"} @ ${result.device.address}")
                    val name = nameOf(result) ?: return
                    val parsed = G1Name.parse(name) ?: return
                    val side = parsed.side ?: return
                    if (parsed.pairKey in pairedChannels) return
                    val key = parsed.pairKey
                    val pair = foundPairs.getOrPut(key) { FoundPair() }
                    if (side == G1Name.Side.LEFT) {
                        if (pair.left != null) return
                        foundPairs[key] = pair.copy(left = result)
                    } else {
                        if (pair.right != null) return
                        foundPairs[key] = pair.copy(right = result)
                    }
                    val updated = foundPairs.getValue(key)
                    if (updated.left != null && updated.right != null) {
                        pairedChannels.add(key)
                        foundPairs.remove(key)
                        trySendBlocking(G1(
                            G1Device(updated.right),
                            G1Device(updated.left)
                        ))
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w("G1", "BLE scan failed: errorCode=$errorCode")
                }
            }
            scanner.startScan(listOf(), settings, callback)
            Handler(Looper.getMainLooper()).postDelayed({
                scanner.stopScan(callback)
                trySendBlocking(null)
            }, duration.inWholeMilliseconds)
            awaitClose {
                scanner.stopScan(callback)
            }
        }
    }
}