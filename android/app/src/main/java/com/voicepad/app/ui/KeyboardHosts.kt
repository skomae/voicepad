package com.voicepad.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepad.app.RecordingController
import com.voicepad.app.transmit.KeyboardHost
import com.voicepad.app.transmit.SinkStatus

/** What Bluetooth discovery needs: SCAN on Android 12+, location on Android 11. */
private val scanPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun Context.hasScanPermissions() = scanPermissions.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * Where to type: remembered computers first (tap to connect, × to forget),
 * then other paired devices, then a scan for new computers to pair with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSheet(
    controller: RecordingController,
    state: RecordingController.UiState,
    onMakeDiscoverable: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    val scan by controller.keyboardScan.collectAsStateWithLifecycle()
    // hosts() isn't observable; re-read it whenever something could have changed it.
    var refresh by remember { mutableIntStateOf(0) }
    val hosts = remember(refresh, scan.pairing, state.transmitStatus) { controller.keyboardHosts() }
    val remembered = hosts.filter { it.isKnown }
    val others = hosts.filter { !it.isKnown }

    fun scanNow() {
        if (!controller.startKeyboardScan()) toast("Couldn't start looking for devices. Is Bluetooth on?")
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) scanNow() else toast("VoicePad needs Nearby devices permission to find computers.")
    }
    fun startScan() = if (context.hasScanPermissions()) scanNow() else permissions.launch(scanPermissions)

    // Close once connected; never leave discovery running behind a closed sheet.
    LaunchedEffect(state.transmitStatus) {
        if (state.transmitStatus == SinkStatus.CONNECTED) onDismiss()
    }
    DisposableEffect(Unit) { onDispose { controller.stopKeyboardScan() } }

    fun connect(host: KeyboardHost) {
        when {
            !host.isBonded -> if (controller.pairKeyboardHost(host.address)) {
                toast("Pairing with ${host.name}: confirm the code on both devices.")
            } else {
                toast("Couldn't start pairing with ${host.name}.")
            }
            controller.connectKeyboard(host.address) -> toast("Connecting to ${host.name}…")
            else -> toast("Couldn't connect to ${host.name}.")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Type on which computer?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            if (remembered.isNotEmpty()) {
                SectionLabel("Your computers")
                remembered.forEach { host ->
                    HostRow(
                        host = host,
                        detail = when {
                            !host.isBonded -> "No longer paired: tap to pair again"
                            host.isLast -> "Last used"
                            else -> null
                        },
                        busy = scan.pairing == host.address,
                        onClick = { connect(host) },
                        onForget = {
                            controller.forgetKeyboardHost(host.address)
                            refresh++
                        },
                    )
                }
            }

            if (others.isNotEmpty()) {
                SectionLabel("Other paired devices")
                others.forEach { host ->
                    HostRow(host, detail = null, busy = false, onClick = { connect(host) }, onForget = null)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pair a new computer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Open Bluetooth settings on the computer so it's visible, then pick it below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (scan.scanning) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = ::startScan) {
                        Icon(Icons.Default.Refresh, contentDescription = "Look for computers")
                    }
                }
            }
            if (!scan.scanning && scan.found.isEmpty()) {
                OutlinedButton(onClick = ::startScan, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Look for nearby computers")
                }
            }
            scan.found.forEach { host ->
                HostRow(
                    host = host,
                    detail = if (scan.pairing == host.address) "Pairing: confirm the code on both devices" else "Tap to pair",
                    busy = scan.pairing == host.address,
                    onClick = { connect(host) },
                    onForget = null,
                )
            }
            if (scan.scanning && scan.found.isEmpty()) {
                Text(
                    "Looking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Computer not showing up?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSettings) { Text("Pair it from phone Bluetooth settings") }
            TextButton(onClick = onMakeDiscoverable) { Text("Let the computer find this phone instead") }
            TextButton(onClick = { controller.stopKeyboard(); onDismiss() }) { Text("Turn keyboard off") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun HostRow(
    host: KeyboardHost,
    detail: String?,
    busy: Boolean,
    onClick: () -> Unit,
    onForget: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Icon(if (host.isComputer) Icons.Default.Computer else Icons.Default.Bluetooth, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(host.name)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (host.isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (onForget != null) {
            IconButton(onClick = onForget) {
                Icon(Icons.Default.Close, contentDescription = "Forget ${host.name}")
            }
        }
    }
}
