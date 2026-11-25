package com.voicepad.app.glasses

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.voicepad.app.glasses.basis.G1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Even Realities G1 glasses over Bluetooth, through the reverse-engineered
 * Basis protocol (see `basis/`).
 *
 * The G1 is two BLE peripherals, one per arm, paired by the channel number in
 * their advertised names; both are written for text to appear across the
 * waveguide. The link drops after about half a minute of silence, so each
 * arm keeps the official app's heartbeat going for as long as it's connected.
 *
 * A link lost while in use (out of range, glasses folded for a moment) is
 * reconnected by Android on its own; this waits [RECONNECT_GRACE_MS] for that
 * before letting the glasses go and reporting them disconnected.
 */
class BasisGlassesLink(
    private val context: Context,
    private val scope: CoroutineScope,
) : GlassesLink {

    companion object {
        /** Permissions needed to scan and connect, from Android 12. */
        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun hasPermission(context: Context): Boolean = permissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        /** How long to look for the glasses before giving up on this attempt. */
        private val SCAN_WINDOW = 15.seconds
        private const val SCAN_TIMEOUT_MS = 25_000L
        /** Writes are cheap but not free: keep at most a few per second. */
        private const val MIN_GAP_MS = 350L
        /** A page is usually two packets per arm, each acknowledged: give it time. */
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val RECONNECT_GRACE_MS = 45_000L
    }

    private val _status = MutableStateFlow<GlassesStatus>(GlassesStatus.Off)
    override val status: StateFlow<GlassesStatus> = _status.asStateFlow()

    private var connectJob: Job? = null
    private var watchJob: Job? = null
    private var graceJob: Job? = null
    private var glasses: G1? = null
    private var lastWriteAt = 0L

    override fun connect() {
        if (connectJob?.isActive == true) return
        val current = glasses
        if (current != null) {
            if (current.state.value.connectionState == G1.ConnectionState.CONNECTED) return
            // Asked to look again while a lost link is still trying to come back:
            // let that go and scan afresh (connected glasses don't advertise).
            forget(current)
        }
        if (!hasPermission(context)) {
            _status.value = GlassesStatus.NeedsPermission
            return
        }
        if (!bluetoothOn()) {
            _status.value = GlassesStatus.Failed("Bluetooth is off.")
            return
        }
        connectJob = scope.launch {
            try {
                findAndConnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _status.value = GlassesStatus.Failed(e.message ?: "Couldn't reach the glasses.")
            }
        }
    }

    private suspend fun findAndConnect() {
        _status.value = GlassesStatus.Scanning
        val found = try {
            withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                G1.find(SCAN_WINDOW).filterNotNull().firstOrNull()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        if (found == null) {
            _status.value = GlassesStatus.NotFound
            return
        }
        _status.value = GlassesStatus.Connecting(found.name)
        val connected = try {
            found.connect(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
        if (!connected) {
            runCatching { found.disconnect() }
            _status.value = GlassesStatus.Failed("Couldn't connect to ${found.name}.")
            return
        }
        glasses = found
        publish(found)
        watchJob?.cancel()
        watchJob = scope.launch {
            found.state.collect { state ->
                when (state.connectionState) {
                    G1.ConnectionState.CONNECTED -> {
                        graceJob?.cancel()
                        graceJob = null
                        publish(found)
                    }
                    G1.ConnectionState.CONNECTING -> {
                        // Reconnecting after a link loss.
                        _status.value = GlassesStatus.Connecting(found.name)
                        if (graceJob?.isActive != true) {
                            graceJob = scope.launch {
                                delay(RECONNECT_GRACE_MS)
                                forget(found)
                            }
                        }
                    }
                    G1.ConnectionState.DISCONNECTED, G1.ConnectionState.ERROR -> forget(found)
                    else -> Unit
                }
            }
        }
    }

    private fun publish(g1: G1) {
        _status.value = GlassesStatus.Connected(g1.name, g1.state.value.batteryPercentage)
    }

    /** Lets [g1] go for good: no more reconnecting in the background. */
    private fun forget(g1: G1) {
        if (glasses !== g1) return
        glasses = null
        watchJob?.cancel()
        watchJob = null
        graceJob?.cancel()
        graceJob = null
        _status.value = GlassesStatus.Disconnected
        scope.launch { runCatching { g1.disconnect() } }
    }

    override suspend fun show(page: List<String>): Boolean {
        val g1 = glasses ?: return false
        if (g1.state.value.connectionState != G1.ConnectionState.CONNECTED) return false
        // The whole display is 5 lines of 40 columns; anything bigger is a bug, not something to clip.
        if (page.size > GlassesScreen.LINES) return false
        if (page.any { GlassesScreen.width(it) > GlassesScreen.COLUMNS }) return false
        val since = SystemClock.elapsedRealtime() - lastWriteAt
        if (since in 1 until MIN_GAP_MS) delay(MIN_GAP_MS - since)
        lastWriteAt = SystemClock.elapsedRealtime()
        return try {
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { g1.displayTextPage(page) } == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    }

    override suspend fun clear() {
        val g1 = glasses ?: return
        try {
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { g1.stopDisplaying() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Unit
        }
    }

    override suspend fun close() {
        connectJob?.cancel()
        connectJob = null
        watchJob?.cancel()
        watchJob = null
        graceJob?.cancel()
        graceJob = null
        val g1 = glasses
        glasses = null
        _status.value = GlassesStatus.Off
        // Waited for, so a session that starts right after doesn't scan while
        // these arms are still connected (connected glasses don't advertise).
        if (g1 != null) runCatching { g1.disconnect() }
    }

    private fun bluetoothOn(): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return adapter.isEnabled
    }
}
