package com.voicepad.app.transmit

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    /** Computers keyboard mode has connected to, most recent first. */
    @Volatile
    private var known: List<KnownHost> = KnownHosts.decode(prefs.getString(KNOWN_HOSTS, null)).ifEmpty {
        // Earlier builds remembered only the last host's address.
        listOfNotNull(prefs.getString("lastHost", null)?.let { KnownHost(it, it, 0) })
    }

    private val _scan = MutableStateFlow(ScanState())
    override val scan: StateFlow<ScanState> = _scan.asStateFlow()

    /**
     * A host to connect to once the keyboard is registered, or once the
     * current host has disconnected (switching computers).
     */
    @Volatile
    private var pendingConnect: String? = null

    /** The host [connect] is waiting on, for [connectTimeout]'s message. */
    @Volatile
    private var connectingTo: String? = null

    private val connectTimeout = Runnable {
        val name = connectingTo?.let(::nameOf) ?: return@Runnable
        connectingTo = null
        _scan.value = _scan.value.copy(pairing = null)
        if (_state.value.status == SinkStatus.ADVERTISING) {
            _state.value = SinkState(
                SinkStatus.ADVERTISING,
                "Couldn't connect to $name. Make sure its Bluetooth is on and it's nearby; " +
                    "if it was reset, forget it and pair again.",
            )
        }
    }

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
                val pending = pendingConnect
                pendingConnect = null
                if (pending != null) connect(pending) else reconnectLastHost()
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
                    main.removeCallbacks(connectTimeout)
                    connectingTo = null
                    val name = nameOf(device.address)
                    remember(KnownHost(device.address, name, System.currentTimeMillis()))
                    if (_scan.value.pairing == device.address) _scan.value = _scan.value.copy(pairing = null)
                    _state.value = SinkState(SinkStatus.CONNECTED, host = name)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connected == device) connected = null
                    if (profile != null) {
                        _state.value = SinkState(if (registered) SinkStatus.ADVERTISING else SinkStatus.IDLE)
                    }
                    // Switching computers: the old one is gone, connect the new one.
                    pendingConnect?.let {
                        pendingConnect = null
                        connect(it)
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
        pendingConnect = null
        main.removeCallbacks(registrationTimeout)
        main.removeCallbacks(connectTimeout)
        connectingTo = null
        stopScan()
        unregisterBondReceiver()
        _scan.value = ScanState()
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

    private fun remember(host: KnownHost) {
        known = KnownHosts.upsert(known, host)
        prefs.edit { putString(KNOWN_HOSTS, KnownHosts.encode(known)) }
    }

    override fun forget(address: String) {
        known = KnownHosts.forget(known, address)
        prefs.edit { putString(KNOWN_HOSTS, KnownHosts.encode(known)) }
    }

    /**
     * The device's major class, or null when reading it isn't allowed (API 31+
     * needs BLUETOOTH_CONNECT; the user can refuse it).
     */
    private fun majorClassOf(device: BluetoothDevice): Int? = try {
        device.bluetoothClass?.majorDeviceClass
    } catch (_: SecurityException) {
        null
    }

    /** The device's Bluetooth name, else the name we remembered, else its address. */
    private fun nameOf(address: String): String {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
        val name = try {
            device?.name
        } catch (_: SecurityException) {
            null
        }
        return name?.takeIf { it.isNotBlank() } ?: known.firstOrNull { it.address == address }?.name ?: address
    }

    private fun bondedAddresses(): Set<String> = try {
        adapter?.bondedDevices.orEmpty().map { it.address }.toSet()
    } catch (_: SecurityException) {
        emptySet()
    }

    /** Reconnects to the most recently used computer that's still paired. */
    private fun reconnectLastHost() {
        val bonded = bondedAddresses()
        known.firstOrNull { it.address in bonded }?.let { connect(it.address) }
    }

    override fun hosts(): List<KeyboardHost> {
        if (!hasPermission()) return emptyList()
        val bonded = try {
            adapter?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            return emptyList()
        }
        val knownByAddress = known.associateBy { it.address }
        val last = known.firstOrNull()?.address
        val paired = bonded.map { d ->
            KeyboardHost(
                address = d.address,
                name = nameOf(d.address),
                isComputer = majorClassOf(d) == BluetoothClass.Device.Major.COMPUTER,
                isLast = d.address == last,
                isKnown = d.address in knownByAddress,
            )
        }
        // Remembered computers the phone has since unpaired: shown so they can be paired again.
        val unpaired = known.filter { k -> bonded.none { it.address == k.address } }.map { k ->
            KeyboardHost(k.address, k.name, isComputer = true, isLast = k.address == last, isKnown = true, isBonded = false)
        }
        val recency = known.withIndex().associate { (i, k) -> k.address to i }
        return (paired + unpaired).sortedWith(
            compareByDescending<KeyboardHost> { it.isKnown }
                .thenBy { recency[it.address] ?: Int.MAX_VALUE }
                .thenByDescending { it.isComputer }
                .thenBy { it.name.lowercase() },
        )
    }

    /**
     * Connects to [address] from the phone side. Works from any state: turns
     * keyboard mode on first if needed, and switches away from another
     * connected computer.
     */
    override fun connect(address: String): Boolean {
        val hid = profile
        if (hid == null || !registered) {
            pendingConnect = address
            if (!wanted) start()
            return _state.value.status != SinkStatus.FAILED && _state.value.status != SinkStatus.UNAVAILABLE
        }
        val current = connected
        if (current != null) {
            if (current.address == address) return true
            pendingConnect = address
            return try {
                hid.disconnect(current)
            } catch (_: SecurityException) {
                pendingConnect = null
                false
            }
        }
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
        val ok = try {
            hid.connect(device)
        } catch (_: SecurityException) {
            false
        }
        if (ok) {
            connectingTo = address
            main.removeCallbacks(connectTimeout)
            main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        }
        return ok
    }

    // region Finding and pairing new computers

    private fun hasScanPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION // discovery on Android 11
        }
        return ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED
    }

    private var scanReceiver: BroadcastReceiver? = null

    override fun startScan(): Boolean {
        val a = adapter ?: return false
        if (!hasPermission() || !hasScanPermission()) return false
        if (scanReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> onFound(intent)
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scan.value = _scan.value.copy(scanning = false)
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            scanReceiver = receiver
        }
        _scan.value = _scan.value.copy(scanning = true, found = emptyList())
        val started = try {
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (_: SecurityException) {
            false
        }
        if (!started) _scan.value = _scan.value.copy(scanning = false)
        return started
    }

    private fun onFound(intent: Intent) {
        @Suppress("DEPRECATION")
        val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        // Nameless results are mostly BLE beacons; nobody can pick those anyway.
        if (name.isNullOrBlank()) return
        if (device.address in bondedAddresses()) return
        @Suppress("DEPRECATION")
        val major = intent.getParcelableExtra<BluetoothClass>(BluetoothDevice.EXTRA_CLASS)?.majorDeviceClass
            ?: majorClassOf(device)
        val host = KeyboardHost(
            address = device.address,
            name = name,
            isComputer = major == BluetoothClass.Device.Major.COMPUTER,
            isLast = false,
            isKnown = known.any { it.address == device.address },
            isBonded = false,
        )
        val found = (_scan.value.found.filter { it.address != host.address } + host)
            .sortedWith(compareByDescending<KeyboardHost> { it.isComputer }.thenBy { it.name.lowercase() })
        _scan.value = _scan.value.copy(found = found)
    }

    override fun stopScan() {
        try {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        scanReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        scanReceiver = null
        _scan.value = _scan.value.copy(scanning = false)
    }

    private var bondReceiver: BroadcastReceiver? = null

    private fun unregisterBondReceiver() {
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver = null
    }

    override fun pair(address: String): Boolean {
        val a = adapter ?: return false
        val device = try {
            a.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            return false
        }
        // Discovery slows pairing down a lot (and some stacks fail outright).
        stopScan()
        if (address in bondedAddresses()) return connect(address)

        unregisterBondReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val changed: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (changed.address != address) return
                val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                when {
                    bond == BluetoothDevice.BOND_BONDED -> {
                        unregisterBondReceiver()
                        // Keep "pairing" until the keyboard connection lands (or times out).
                        if (!connect(address)) _scan.value = _scan.value.copy(pairing = null)
                    }
                    bond == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING -> {
                        unregisterBondReceiver()
                        _scan.value = _scan.value.copy(pairing = null)
                        _state.value = _state.value.copy(
                            error = "Pairing with ${nameOf(address)} failed or was declined. Try again with " +
                                "its Bluetooth settings open.",
                        )
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        bondReceiver = receiver
        val started = try {
            device.createBond()
        } catch (_: SecurityException) {
            false
        }
        if (started) {
            _scan.value = _scan.value.copy(pairing = address)
        } else {
            unregisterBondReceiver()
        }
        return started
    }

    // endregion

    override suspend fun type(text: String) {
        val strokes = HidKeyMap.strokes(text)
        if (strokes.isEmpty()) return
        val hid = profile ?: return
        val device = connected ?: return
        // Phones (an iPhone above all) drop keystrokes at computer speed: give them time.
        val phone = majorClassOf(device) == BluetoothClass.Device.Major.PHONE
        val downUp = if (phone) PHONE_DOWN_UP_MS else DOWN_UP_MS
        val between = if (phone) PHONE_KEY_MS else KEY_MS
        try {
            for (stroke in strokes) {
                if (!send(hid, device, stroke)) return
                delay(downUp)
                if (!send(hid, device, null)) return
                delay(between)
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
        /** Computers: about 75 keys a second. */
        private const val DOWN_UP_MS = 5L
        private const val KEY_MS = 8L
        /**
         * Phones (by Bluetooth device class): about 22 keys a second. An iPhone as host
         * dropped characters at the computer pace, most likely because it polls on a
         * longer connection interval than a computer, so a key-down and its key-up a few
         * milliseconds apart can fall into one interval and a keystroke is lost.
         */
        private const val PHONE_DOWN_UP_MS = 20L
        private const val PHONE_KEY_MS = 25L
        private const val REGISTRATION_TIMEOUT_MS = 10_000L
        private const val KNOWN_HOSTS = "knownHosts"
        private const val CONNECT_TIMEOUT_MS = 15_000L

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
