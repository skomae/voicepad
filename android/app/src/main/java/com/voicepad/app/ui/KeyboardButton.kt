package com.voicepad.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voicepad.app.RecordingController
import com.voicepad.app.transmit.SinkStatus
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.voicepad.app.transmit.KeyboardHost

/** Android 12+ needs these to register as a keyboard and to become discoverable. */
private val bluetoothPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        emptyArray()
    }

private fun Context.hasBluetoothPermissions() = bluetoothPermissions.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * Top-bar "type as keyboard" control.
 *
 * - Idle: asks for Bluetooth permission if needed and registers as a
 *   keyboard, then reconnects the last host or offers the paired devices.
 * - Advertising: tap picks a paired device to connect to, pairs a new one
 *   (the only case that asks to make the phone discoverable), or stops.
 * - Connected: tap types the latest note (tap again to stop mid-way).
 * - Drag down or long-press: the keyboard menu (connection, LLM rewrite
 *   toggle and prompt).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardButton(
    controller: RecordingController,
    state: RecordingController.UiState,
    onTypeLatest: () -> Unit,
) {
    val context = LocalContext.current
    fun toast(text: String, long: Boolean = false) =
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    var showHosts by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // Open the host picker once registration finishes after a tap to start.
    var pickAfterStart by remember { mutableStateOf(false) }

    fun start() {
        pickAfterStart = true
        controller.toggleKeyboard()
    }

    LaunchedEffect(state.transmitStatus) {
        when (state.transmitStatus) {
            SinkStatus.ADVERTISING -> {
                if (pickAfterStart) {
                    pickAfterStart = false
                    // A remembered host is being reconnected automatically;
                    // only ask when there's nothing to reconnect to.
                    if (controller.keyboardHosts().none { it.isKnown && it.isBonded }) showHosts = true
                }
            }
            SinkStatus.CONNECTED -> {
                showHosts = false
            }
            SinkStatus.REGISTERING -> {}
            else -> {
                pickAfterStart = false
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) start() else toast("VoicePad needs Nearby devices permission to act as a keyboard.", long = true)
    }

    if (showHosts) {
        HostSheet(
            controller = controller,
            state = state,
            onMakeDiscoverable = {
                showHosts = false
                // Fallback: let the computer find the phone (some phones ignore this prompt).
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                runCatching { discoverable.launch(intent) }
                toast("On your computer, open Bluetooth settings and connect VoicePad.", long = true)
            },
            onOpenSettings = {
                showHosts = false
                // Pairing from the phone side: the computer only needs its own
                // Bluetooth settings open, and no discoverable prompt is needed.
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                toast("Pair your computer here, then come back and pick it from the list.", long = true)
            },
            onDismiss = { showHosts = false },
        )
    }

    LaunchedEffect(state.transmitError) {
        state.transmitError?.let { toast(it, long = true) }
    }

    val onClick: () -> Unit = {
        when (state.transmitStatus) {
            SinkStatus.CONNECTED -> if (state.isTyping) {
                controller.stopTyping()
                toast("Stopped typing.")
            } else {
                onTypeLatest()
            }
            SinkStatus.ADVERTISING -> {
                showHosts = true
            }
            SinkStatus.REGISTERING -> {
                controller.stopKeyboard()
                toast("Keyboard stopped.")
            }
            SinkStatus.UNAVAILABLE -> toast("This phone can't act as a Bluetooth keyboard.", long = true)
            SinkStatus.IDLE, SinkStatus.FAILED ->
                if (context.hasBluetoothPermissions()) start() else permissions.launch(bluetoothPermissions)
        }
    }

    val label = when (state.transmitStatus) {
        SinkStatus.CONNECTED -> if (state.isTyping) "Stop typing" else "Type latest note on the paired device"
        SinkStatus.ADVERTISING -> "Connect keyboard to a device"
        SinkStatus.REGISTERING -> "Stop keyboard mode"
        else -> "Type as keyboard"
    }

    if (showMenu) {
        KeyboardMenu(
            controller = controller,
            state = state,
            onTurnOn = { if (context.hasBluetoothPermissions()) start() else permissions.launch(bluetoothPermissions) },
            onPickHost = { showHosts = true },
            onTypeLatest = onTypeLatest,
            onDismiss = { showMenu = false },
        )
    }

    val colors = MaterialTheme.colorScheme
    val typing = state.transmitStatus == SinkStatus.CONNECTED && state.isTyping
    // Sized and shaped like an IconButton. Drag down (or long-press) opens the
    // keyboard menu. Deliberately never red: that's the recording colour.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (typing) colors.primaryContainer else Color.Transparent, CircleShape)
            .pointerInput(Unit) {
                val threshold = 24.dp.toPx()
                var pulled = 0f
                var opened = false
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f; opened = false },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        pulled += dy
                        if (!opened && pulled > threshold) {
                            opened = true
                            showMenu = true
                        }
                    },
                )
            }
            .combinedClickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
                onLongClickLabel = "Keyboard options",
                onLongClick = { showMenu = true },
            ),
    ) {
        Icon(
            Icons.Default.Keyboard,
            contentDescription = label,
            tint = when (state.transmitStatus) {
                SinkStatus.CONNECTED -> if (typing) colors.onPrimaryContainer else colors.primary
                SinkStatus.ADVERTISING, SinkStatus.REGISTERING -> colors.tertiary
                else -> colors.onSurface
            },
        )
        if (state.transmitStatus == SinkStatus.CONNECTED && !typing) {
            // A small dot so "connected" reads at a glance, even in monochrome themes.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp)
                    .size(7.dp)
                    .background(colors.primary, CircleShape),
            )
        }
    }
}
