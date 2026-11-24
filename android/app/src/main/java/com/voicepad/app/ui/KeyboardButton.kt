package com.voicepad.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
 * - Idle: asks for Bluetooth permission if needed, starts advertising and
 *   makes the phone discoverable so the host can pair.
 * - Advertising: tap stops.
 * - Connected: tap types the latest note (tap again to stop mid-way);
 *   long-press disconnects and stops advertising.
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

    fun start() {
        controller.toggleKeyboard()
        // A host can only find the phone while it's discoverable; ask for
        // two minutes, which is plenty to pair from the host's settings.
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        runCatching { discoverable.launch(intent) }
        toast("Pair VoicePad as a keyboard from your computer's Bluetooth settings.", long = true)
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) start() else toast("VoicePad needs Nearby devices permission to act as a keyboard.", long = true)
    }

    LaunchedEffect(state.transmitError) {
        state.transmitError?.let { toast(it, long = true) }
    }

    val onClick = {
        when (state.transmitStatus) {
            SinkStatus.CONNECTED -> if (state.isTyping) {
                controller.stopTyping()
                toast("Stopped typing.")
            } else {
                onTypeLatest()
            }
            SinkStatus.REGISTERING, SinkStatus.ADVERTISING -> {
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
        SinkStatus.REGISTERING, SinkStatus.ADVERTISING -> "Stop keyboard mode"
        else -> "Type as keyboard"
    }

    // Sized and shaped like an IconButton, plus a long-press to disconnect.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
                onLongClickLabel = "Stop keyboard mode",
                onLongClick = {
                    if (state.transmitStatus == SinkStatus.CONNECTED || state.transmitStatus == SinkStatus.ADVERTISING) {
                        controller.stopKeyboard()
                        toast("Keyboard disconnected.")
                    }
                },
            ),
    ) {
        Icon(
            Icons.Default.Keyboard,
            contentDescription = label,
            tint = when {
                state.transmitStatus == SinkStatus.CONNECTED && state.isTyping -> MaterialTheme.colorScheme.tertiary
                state.transmitStatus == SinkStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                state.transmitStatus == SinkStatus.ADVERTISING || state.transmitStatus == SinkStatus.REGISTERING -> RecordingRed
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
