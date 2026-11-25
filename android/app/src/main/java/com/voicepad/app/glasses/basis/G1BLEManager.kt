package com.voicepad.app.glasses.basis

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.UUID

/**
 * One arm's GATT connection: the Nordic UART service, a write characteristic
 * for requests and a notifying one for the glasses' responses.
 *
 * [send] suspends rather than blocking: Nordic's synchronous `await()` throws
 * when called on the main thread, which is where the glasses controller runs,
 * so every write used to fail without reaching the glasses.
 */
@SuppressLint("MissingPermission")
internal class G1BLEManager(
    context: Context,
    /** Every notification from the glasses, raw. Called on the main thread. */
    private val onIncoming: (ByteArray) -> Unit,
) : BleManager(context) {

    private val writableConnectionState = MutableStateFlow(G1.ConnectionState.CONNECTING)
    val connectionState = writableConnectionState.asStateFlow()

    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    init {
        // Set once, not in initialize(): that runs again on every reconnection.
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                writableConnectionState.value = G1.ConnectionState.CONNECTING
            }

            override fun onDeviceConnected(device: BluetoothDevice) = Unit

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                writableConnectionState.value = G1.ConnectionState.ERROR
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                writableConnectionState.value = G1.ConnectionState.CONNECTED
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                writableConnectionState.value = G1.ConnectionState.DISCONNECTING
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                // Connected with autoConnect, Android keeps trying after a link loss
                // (out of range, glasses folded for a moment): that's reconnecting,
                // not gone.
                writableConnectionState.value = if (reason == ConnectionObserver.REASON_LINK_LOSS) {
                    G1.ConnectionState.CONNECTING
                } else {
                    G1.ConnectionState.DISCONNECTED
                }
            }
        })
    }

    override fun initialize() {
        // Upstream called createBondInsecure() here without enqueuing it, so it
        // never ran; the glasses work unbonded and bonding would add a system
        // pairing prompt, so it's left out rather than "fixed".
        requestMtu(251).enqueue()
        setNotificationCallback(readCharacteristic).with { _, data ->
            val bytes = data.value ?: return@with
            Log.v(TAG, "received ${G1Protocol.hex(bytes)}")
            onIncoming(bytes)
        }
        enableNotifications(readCharacteristic).enqueue()
    }

    /** Writes [bytes], retrying twice; false if the write never went through. */
    suspend fun send(bytes: ByteArray): Boolean {
        val characteristic = writeCharacteristic ?: return false
        Log.v(TAG, "send ${G1Protocol.hex(bytes)}")
        repeat(3) { attempt ->
            try {
                writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "write failed (attempt ${attempt + 1}): $e")
            }
        }
        return false
    }

    override fun getMinLogPriority(): Int = Log.INFO

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(UUID.fromString(UART_SERVICE_UUID)) ?: return false
        val write = service.getCharacteristic(UUID.fromString(UART_WRITE_CHARACTERISTIC_UUID)) ?: return false
        val read = service.getCharacteristic(UUID.fromString(UART_READ_CHARACTERISTIC_UUID)) ?: return false
        writeCharacteristic = write
        readCharacteristic = read
        return true
    }

    override fun onServicesInvalidated() {
        writeCharacteristic = null
        readCharacteristic = null
    }

    private companion object {
        const val TAG = "G1BLEManager"
    }
}
