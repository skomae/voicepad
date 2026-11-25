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
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.voicepad.app.transmit.KeyboardHost
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import kotlin.math.floor
import androidx.compose.runtime.rememberUpdatedState

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

    val handleClick: () -> Unit = {
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
    val view = LocalView.current
    val stepPx = with(LocalDensity.current) { QUICK_STEP.toPx() }

    // Pull down: quick-pick list of computers below the icon (like the split
    // button's pull-up). Release on one to connect or switch to it.
    var pulling by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf(emptyList<QuickItem>()) }
    var hover by remember { mutableIntStateOf(-1) }

    fun pick(item: QuickItem) {
        when (item) {
            is QuickItem.Host -> {
                if (!context.hasBluetoothPermissions()) {
                    permissions.launch(bluetoothPermissions)
                } else if (item.host.name == state.transmitHost && state.transmitStatus == SinkStatus.CONNECTED) {
                    toast("Already typing on ${item.host.name}.")
                } else if (controller.connectKeyboard(item.host.address)) {
                    toast("Connecting to ${item.host.name}\u2026")
                } else {
                    toast("Couldn't connect to ${item.host.name}.", long = true)
                }
            }
            QuickItem.More -> {
                if (state.transmitStatus == SinkStatus.IDLE || state.transmitStatus == SinkStatus.FAILED) {
                    if (context.hasBluetoothPermissions()) start() else permissions.launch(bluetoothPermissions)
                }
                showHosts = true
            }
            QuickItem.Menu -> showMenu = true
        }
    }

    // The gesture loop below is set up once; read the latest handlers through these.
    val latestClick by rememberUpdatedState(handleClick)
    val latestPick by rememberUpdatedState<(QuickItem) -> Unit> { pick(it) }

    // Sized and shaped like an IconButton. Tap: see [handleClick]; long-press:
    // keyboard menu; pull down: quick-pick a computer. Deliberately never
    // red: that's the recording colour.
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (typing) colors.primaryContainer else Color.Transparent, CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    onClick(label = label) { handleClick(); true }
                    onLongClick("Keyboard options") { showMenu = true; true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            val drop = change.position.y - down.position.y
                            if (!change.pressed) {
                                when {
                                    moved -> {
                                        quick.getOrNull(hover)?.let {
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                            latestPick(it)
                                        }
                                        pulling = false
                                    }
                                    change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis ->
                                        showMenu = true
                                    else -> latestClick()
                                }
                                break
                            }
                            if (!moved && drop > viewConfiguration.touchSlop) {
                                moved = true
                                quick = quickItems(controller)
                                hover = -1
                                pulling = true
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            if (moved) {
                                // Item i sits (i + 1) steps below the icon; above that cancels.
                                val next = floor(drop / stepPx - 0.5f).toInt().coerceIn(-1, quick.lastIndex)
                                if (next != hover) {
                                    hover = next
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            Icon(
                Icons.Default.Keyboard,
                contentDescription = null,
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

        if (pulling) {
            // Line item i up with a finger dragged (i + 0.5 .. i + 1.5) steps below the icon's centre.
            val drop = with(LocalDensity.current) { (24.dp + QUICK_STEP * 0.5f - 4.dp).roundToPx() }
            Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, drop)) {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 8.dp) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        quick.forEachIndexed { i, item ->
                            val current = item is QuickItem.Host &&
                                state.transmitStatus == SinkStatus.CONNECTED && item.host.name == state.transmitHost
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (i == hover) colors.primaryContainer else Color.Transparent)
                                    .padding(horizontal = 10.dp)
                                    .size(width = 240.dp, height = QUICK_STEP - 4.dp),
                            ) {
                                Icon(
                                    when (item) {
                                        is QuickItem.Host -> if (item.host.isComputer) Icons.Default.Computer else Icons.Default.Bluetooth
                                        QuickItem.More -> Icons.Default.Add
                                        QuickItem.Menu -> Icons.Default.Tune
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    when (item) {
                                        is QuickItem.Host -> item.host.name
                                        QuickItem.More -> "Pair or pick another…"
                                        QuickItem.Menu -> "Keyboard settings…"
                                    },
                                    fontWeight = if (i == hover) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (current) Icon(Icons.Default.Check, contentDescription = "Connected", modifier = Modifier.size(18.dp))
                            }
                        }
                        Text(
                            if (hover < 0) "Release here to cancel" else "Release to choose",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Height of one row in the pull-down quick-pick list. */
private val QUICK_STEP = 44.dp

/** A row in the pull-down quick-pick list. */
private sealed interface QuickItem {
    data class Host(val host: KeyboardHost) : QuickItem
    data object More : QuickItem
    data object Menu : QuickItem
}

/** Up to 6 paired computers (remembered ones first), then "more" and "settings". */
private fun quickItems(controller: RecordingController): List<QuickItem> =
    controller.keyboardHosts()
        .filter { it.isBonded && (it.isKnown || it.isComputer) }
        .take(6)
        .map { QuickItem.Host(it) } + QuickItem.More + QuickItem.Menu
