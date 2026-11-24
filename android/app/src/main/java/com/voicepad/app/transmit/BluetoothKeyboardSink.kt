package com.voicepad.app.transmit

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Acts as a Bluetooth HID keyboard using [BluetoothHidDevice] (API 30+; the
 * non-deprecated [BluetoothHidDevice.registerApp] form requires API 30).
 *
 * [start] registers the app as a boot keyboard, after which the *host* (the
 * computer or tablet) can pair to the phone as a keyboard. [type] then sends
 * one key-down/key-up report pair per character, paced so hosts don't drop
 * keys. The phone cannot initiate the connection in this role.
 */
@RequiresApi(Build.VERSION_CODES.R)
class BluetoothKeyboardSink(private val context: Context) : KeyboardSink {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow(
        SinkState(if (adapter == null) SinkStatus.UNAVAILABLE else SinkStatus.IDLE)
    )
    override val state = _state

    private var profile: BluetoothHidDevice? = null

    @Volatile
    private var registered = false

    @Volatile
    private var connected: BluetoothDevice? = null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BluetoothKeyboardSink.registered = registered
            if (registered && _state.value.status == SinkStatus.REGISTERING) {
                _state.value = SinkState(SinkStatus.ADVERTISING)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            connected = if (state == BluetoothProfile.STATE_CONNECTED) device else null
            _state.value = when (state) {
                BluetoothProfile.STATE_CONNECTED -> SinkState(SinkStatus.CONNECTED)
                else -> SinkState(if (registered) SinkStatus.ADVERTISING else SinkStatus.IDLE)
            }
        }
    }

    override fun start() {
        val a = adapter
        if (a == null) {
            _state.value = SinkState(SinkStatus.UNAVAILABLE)
            return
        }
        if (profile != null) return
        _state.value = SinkState(SinkStatus.REGISTERING)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                if (profileId != HID_DEVICE) return
                profile = proxy as BluetoothHidDevice
                register()
            }

            override fun onServiceDisconnected(profileId: Int) {
                if (profileId != HID_DEVICE) return
                profile = null
                registered = false
                if (_state.value.status != SinkStatus.FAILED) _state.value = SinkState(SinkStatus.IDLE)
            }
        }
        @Suppress("DEPRECATION")
        val ok = a.getProfileProxy(context, listener, HID_DEVICE)
        if (!ok) _state.value = SinkState(SinkStatus.FAILED, "Bluetooth HID profile unavailable.")
    }

    private fun register() {
        val hid = profile ?: return
        val ok = hid.registerApp(SDP, null, null, context.mainExecutor, callback)
        if (!ok) _state.value = SinkState(SinkStatus.FAILED, "Couldn't register as a Bluetooth keyboard.")
        // onAppStatusChanged(registered = true) moves us to ADVERTISING.
    }

    override fun stop() {
        profile?.let { hid ->
            runCatching { hid.unregisterApp() }
            @Suppress("DEPRECATION")
            adapter?.closeProfileProxy(HID_DEVICE, hid)
        }
        profile = null
        registered = false
        connected = null
        _state.value = SinkState(SinkStatus.IDLE)
    }

    override suspend fun type(text: String) {
        val hid = profile ?: return
        val device = connected ?: return
        val strokes = HidKeyMap.strokes(text)
        if (strokes.isEmpty()) return
        for (stroke in strokes) {
            send(hid, device, stroke)
            delay(DOWN_UP_MS)
            send(hid, device, null)
            delay(KEY_MS)
        }
    }

    private fun send(hid: BluetoothHidDevice, device: BluetoothDevice, stroke: HidKeyMap.Stroke?) {
        val report = if (stroke == null) {
            ByteArray(REPORT_LEN)
        } else {
            byteArrayOf(stroke.modifier.toByte(), 0, stroke.key.toByte(), 0, 0, 0, 0, 0)
        }
        hid.sendReport(device, 0, report)
    }

    companion object {
        /** BluetoothProfile.HID_DEVICE (19), kept as a literal for minSdk 26 builds. */
        private const val HID_DEVICE = 19
        private const val REPORT_LEN = 8
        private const val DOWN_UP_MS = 5L
        private const val KEY_MS = 8L

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
