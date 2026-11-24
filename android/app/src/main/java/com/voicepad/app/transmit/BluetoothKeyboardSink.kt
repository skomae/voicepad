package com.voicepad.app.transmit

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Acts as a Bluetooth HID keyboard using [BluetoothHidDevice] (API 30+; the
 * non-deprecated [BluetoothHidDevice.registerApp] form requires API 30).
 *
 * [start] registers the app as a boot keyboard, after which the *host* (the
 * computer or tablet) can pair to the phone as a keyboard. [type] then sends
 * one key-down/key-up report pair per character, paced so hosts don't drop
 * keys. The phone cannot initiate the first connection in this role.
 *
 * Every Bluetooth call needs BLUETOOTH_CONNECT on Android 12+; without it the
 * sink reports [SinkStatus.FAILED] instead of throwing.
 */
@RequiresApi(Build.VERSION_CODES.R)
class BluetoothKeyboardSink(context: Context) : KeyboardSink {

    private val context = context.applicationContext

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow(
        SinkState(if (adapter == null) SinkStatus.UNAVAILABLE else SinkStatus.IDLE)
    )
    override val state: StateFlow<SinkState> = _state.asStateFlow()

    @Volatile
    private var profile: BluetoothHidDevice? = null

    @Volatile
    private var registered = false

    /** Set by [start], cleared by [stop]; a proxy that binds after [stop] is closed again. */
    @Volatile
    private var wanted = false

    @Volatile
    private var connected: BluetoothDevice? = null

    private val prefs = this.context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    /**
     * Some manufacturers ship with the HID Device profile disabled: the proxy
     * request "succeeds" but nothing ever answers. Fail with a clear message
     * instead of sitting in REGISTERING forever.
     */
    private val registrationTimeout = Runnable {
        if (_state.value.status == SinkStatus.REGISTERING) {
            stop()
            _state.value = SinkState(
                SinkStatus.FAILED,
                "This phone didn't enable Bluetooth keyboard mode. Its manufacturer may have " +
                    "turned off the HID Device profile.",
            )
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BluetoothKeyboardSink.registered = registered
            if (registered && _state.value.status == SinkStatus.REGISTERING) {
                main.removeCallbacks(registrationTimeout)
                _state.value = SinkState(SinkStatus.ADVERTISING)
                reconnectLastHost()
            } else if (!registered && profile != null) {
                // The system dropped our registration (e.g. another app took the HID slot).
                connected = null
                _state.value = SinkState(SinkStatus.FAILED, "Bluetooth keyboard was unregistered.")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = device
                    prefs.edit { putString(LAST_HOST, device.address) }
                    _state.value = SinkState(SinkStatus.CONNECTED)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connected == device) connected = null
                    if (profile != null) {
                        _state.value = SinkState(if (registered) SinkStatus.ADVERTISING else SinkStatus.IDLE)
                    }
                }
                // CONNECTING / DISCONNECTING are transient; keep the current state.
            }
        }
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override fun start() {
        val a = adapter
        if (a == null) {
            _state.value = SinkState(SinkStatus.UNAVAILABLE)
            return
        }
        if (!hasPermission()) {
            _state.value = SinkState(SinkStatus.FAILED, "Allow Nearby devices to use VoicePad as a keyboard.")
            return
        }
        if (!a.isEnabled) {
            _state.value = SinkState(SinkStatus.FAILED, "Turn on Bluetooth first.")
            return
        }
        if (profile != null) return
        wanted = true
        _state.value = SinkState(SinkStatus.REGISTERING)
        main.postDelayed(registrationTimeout, REGISTRATION_TIMEOUT_MS)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                if (profileId != HID_DEVICE) return
                val hid = proxy as BluetoothHidDevice
                if (!wanted) {
                    try {
                        adapter?.closeProfileProxy(HID_DEVICE, hid)
                    } catch (_: SecurityException) {
                    }
                    return
                }
                profile = hid
                register()
            }

            override fun onServiceDisconnected(profileId: Int) {
                if (profileId != HID_DEVICE) return
                profile = null
                registered = false
                connected = null
                if (_state.value.status != SinkStatus.FAILED) _state.value = SinkState(SinkStatus.IDLE)
            }
        }
        val ok = try {
            a.getProfileProxy(context, listener, HID_DEVICE)
        } catch (e: SecurityException) {
            false
        }
        if (!ok) {
            wanted = false
            _state.value = SinkState(SinkStatus.FAILED, "Bluetooth keyboard mode isn't supported on this phone.")
        }
    }

    private fun register() {
        val hid = profile ?: return
        val ok = try {
            hid.registerApp(SDP, null, null, context.mainExecutor, callback)
        } catch (e: SecurityException) {
            false
        }
        if (!ok) {
            wanted = false
            closeProxy(hid)
            _state.value = SinkState(SinkStatus.FAILED, "Couldn't register as a Bluetooth keyboard.")
        }
        // Otherwise onAppStatusChanged(registered = true) moves us to ADVERTISING.
    }

    override fun stop() {
        main.removeCallbacks(registrationTimeout)
        wanted = false
        profile?.let { hid ->
            try {
                hid.unregisterApp()
            } catch (_: SecurityException) {
            }
            closeProxy(hid)
        }
        registered = false
        connected = null
        _state.value = SinkState(
            if (adapter == null) SinkStatus.UNAVAILABLE else SinkStatus.IDLE
        )
    }

    private fun closeProxy(hid: BluetoothHidDevice) {
        profile = null
        try {
            adapter?.closeProfileProxy(HID_DEVICE, hid)
        } catch (_: SecurityException) {
        }
    }

    /** Reconnects to the host used last time, if it's still paired. */
    private fun reconnectLastHost() {
        val last = prefs.getString(LAST_HOST, null) ?: return
        if (hosts().any { it.address == last }) connect(last)
    }

    override fun hosts(): List<KeyboardHost> {
        if (!hasPermission()) return emptyList()
        val bonded = try {
            adapter?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            return emptyList()
        }
        val last = prefs.getString(LAST_HOST, null)
        return bonded.map { d ->
            val name = try {
                d.name
            } catch (_: SecurityException) {
                null
            } ?: d.address
            KeyboardHost(
                address = d.address,
                name = name,
                isComputer = d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER,
                isLast = d.address == last,
            )
        }.sortedWith(compareByDescending<KeyboardHost> { it.isLast }.thenByDescending { it.isComputer }.thenBy { it.name })
    }

    override fun connect(address: String): Boolean {
        val hid = profile ?: return false
        if (!registered) return false
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
        return try {
            hid.connect(device)
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun type(text: String) {
        val strokes = HidKeyMap.strokes(text)
        if (strokes.isEmpty()) return
        val hid = profile ?: return
        val device = connected ?: return
        try {
            for (stroke in strokes) {
                if (!send(hid, device, stroke)) return
                delay(DOWN_UP_MS)
                if (!send(hid, device, null)) return
                delay(KEY_MS)
            }
        } finally {
            // Never leave a key held down on the host (it would auto-repeat),
            // even when typing is cancelled between key-down and key-up.
            withContext(NonCancellable) { send(hid, device, null) }
        }
    }

    private fun send(hid: BluetoothHidDevice, device: BluetoothDevice, stroke: HidKeyMap.Stroke?): Boolean {
        if (connected != device) return false
        val report = if (stroke == null) {
            ByteArray(REPORT_LEN)
        } else {
            byteArrayOf(stroke.modifier.toByte(), 0, stroke.key.toByte(), 0, 0, 0, 0, 0)
        }
        return try {
            hid.sendReport(device, 0, report)
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        /** BluetoothProfile.HID_DEVICE (19), kept as a literal for minSdk 26 builds. */
        private const val HID_DEVICE = 19
        private const val REPORT_LEN = 8
        private const val DOWN_UP_MS = 5L
        private const val KEY_MS = 8L
        private const val REGISTRATION_TIMEOUT_MS = 10_000L
        private const val LAST_HOST = "lastHost"

        /**
         * Standard boot-keyboard HID report descriptor: 8-byte input report
         * (1 modifier byte, 1 reserved byte, 6 keycode slots). Every host
         * accepts it without drivers.
         */
        private val SDP = BluetoothHidDeviceAppSdpSettings(
            "VoicePad",
            "VoicePad keyboard",
            "VoicePad",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            hexToBytes(
                "05010906a101050719e029e715002501750195088102" +
                    "9501750881019505750105081901290591029501750391" +
                    "0195067508150025650507190029658100c0",
            ),
        )

        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.filterNot { it.isWhitespace() }
            require(clean.length % 2 == 0) { "Odd-length hex string." }
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
