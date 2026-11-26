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
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
 * - Tap toggles keyboard mode. Turning it on asks for Bluetooth permission if
 *   needed, registers as a keyboard and reconnects the last computer it typed
 *   on (with a toast), or offers the paired devices when there's none. Turning
 *   it off disconnects. While text is being typed, a tap stops the typing first.
 * - Drag down: quick-pick a computer. Long-press: the keyboard menu
 *   (connection, type latest note, LLM rewrite toggle and prompt).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardButton(
    controller: RecordingController,
    state: RecordingController.UiState,
    onTypeLatest: () -> Unit,
    /** Sits on the recording top bar, which is already blue in keyboard mode: draw the disc white. */
    onRecordingBar: Boolean = false,
) {
    val context = LocalContext.current
    fun toast(text: String, long: Boolean = false) =
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    var showHosts by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // Open the host picker once registration finishes after a tap to start.
    var pickAfterStart by remember { mutableStateOf(false) }
    // Turned on from here: say so once it connects.
    var announceConnect by remember { mutableStateOf(false) }

    fun start() {
        pickAfterStart = true
        announceConnect = true
        controller.toggleKeyboard()
    }

    /** Tap on an idle keyboard: turn it on, saying where it will reconnect. */
    fun turnOn() {
        // The computer the sink reconnects to on registration: the most recent remembered one that's still paired.
        val last = controller.keyboardHosts().firstOrNull { it.isKnown && it.isBonded }
        start()
        toast(if (last != null) "Keyboard on \u2014 reconnecting to ${last.name}\u2026" else "Keyboard on \u2014 pick a computer to type on.")
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
                // The host sheet closes itself once the computer picked in it connects.
                if (announceConnect) {
                    announceConnect = false
                    toast(state.transmitHost?.let { "Keyboard connected to $it." } ?: "Keyboard connected.")
                }
            }
            SinkStatus.REGISTERING -> {}
            else -> {
                pickAfterStart = false
                announceConnect = false
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) turnOn() else toast("VoicePad needs Nearby devices permission to act as a keyboard.", long = true)
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

    // Tap is an on/off switch for keyboard mode.
    val handleClick: () -> Unit = {
        when (state.transmitStatus) {
            SinkStatus.CONNECTED, SinkStatus.ADVERTISING, SinkStatus.REGISTERING -> if (state.isTyping) {
                // Stop the runaway typing first; a second tap turns the keyboard off.
                controller.stopTyping()
                toast("Stopped typing.")
            } else {
                showHosts = false
                announceConnect = false
                controller.stopKeyboard()
                toast("Keyboard off.")
            }
            SinkStatus.UNAVAILABLE -> toast("This phone can't act as a Bluetooth keyboard.", long = true)
            SinkStatus.IDLE, SinkStatus.FAILED ->
                if (context.hasBluetoothPermissions()) turnOn() else permissions.launch(bluetoothPermissions)
        }
    }

    val label = when (state.transmitStatus) {
        SinkStatus.CONNECTED -> if (state.isTyping) "Stop typing" else "Turn keyboard off (connected to ${state.transmitHost ?: "a device"})"
        SinkStatus.ADVERTISING, SinkStatus.REGISTERING -> "Turn keyboard off"
        else -> "Turn keyboard on"
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
    // Keyboard mode is its own mode: a solid disc you can't miss. On the recording
    // bar (already blue in keyboard mode) the disc flips to white.
    val disc = when {
        !state.keyboardMode -> Color.Transparent
        onRecordingBar -> Color.White
        else -> KeyboardBlue
    }
    val onDisc = if (onRecordingBar) KeyboardBlue else Color.White
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

    // Sized and shaped like an IconButton. Tap: toggle (see [handleClick]); long-press:
    // keyboard menu; pull down: quick-pick a computer. Deliberately never
    // red: that's the recording colour.
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(disc, CircleShape)
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
                // Off: the bar's own icon colour (white on the recording bar, as before).
                tint = if (state.keyboardMode) onDisc else LocalContentColor.current,
            )
            when {
                // Typing right now: a pulsing dot.
                typing -> PulsingDot(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp), color = onDisc, size = 8.dp)
                // Connected: a steady dot, so it reads at a glance even in monochrome themes.
                state.transmitStatus == SinkStatus.CONNECTED -> Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .size(8.dp)
                        .background(onDisc, CircleShape),
                )
                // On but not connected yet: a ring instead of a dot.
                state.keyboardMode -> Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .size(8.dp)
                        .border(1.5.dp, onDisc, CircleShape),
                )
                else -> {}
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

/**
 * A full-width band under the top bar while keyboard mode is on, so it's never
 * mistaken for normal note-taking: says where the text goes and what a tap on a
 * note does now.
 */
@Composable
fun KeyboardModeStrip(state: RecordingController.UiState) {
    if (!state.keyboardMode) return
    val text = when {
        state.isTyping -> "Typing on ${state.transmitHost ?: "the computer"}\u2026"
        state.transmitStatus == SinkStatus.CONNECTED ->
            "Keyboard mode \u00b7 ${state.transmitHost ?: "connected"} \u00b7 tap a note to type it, hold for rewrites"
        state.transmitStatus == SinkStatus.REGISTERING -> "Keyboard mode \u00b7 starting\u2026"
        else -> "Keyboard mode \u00b7 connecting \u2014 drag the keyboard icon down to pick a computer"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(KeyboardBlue)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = text },
    ) {
        Icon(Icons.Default.Keyboard, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
