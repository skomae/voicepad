package com.voicepad.app.transmit

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the "type as keyboard" sink is doing. */
enum class SinkStatus { UNAVAILABLE, IDLE, REGISTERING, ADVERTISING, CONNECTED, FAILED }

data class SinkState(val status: SinkStatus, val error: String? = null)

/** Output side of "type as keyboard": advertises a HID keyboard and sends text. */
interface KeyboardSink {
    val state: StateFlow<SinkState>
    fun start()
    fun stop()
    suspend fun type(text: String)
}

/** Creates the only supported sink (Bluetooth HID device), or an inert one below API 30. */
fun createKeyboardSink(context: Context): KeyboardSink =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BluetoothKeyboardSink(context) else UnavailableSink

private object UnavailableSink : KeyboardSink {
    override val state = MutableStateFlow(SinkState(SinkStatus.UNAVAILABLE))
    override fun start() {}
    override fun stop() {}
    override suspend fun type(text: String) {}
}
