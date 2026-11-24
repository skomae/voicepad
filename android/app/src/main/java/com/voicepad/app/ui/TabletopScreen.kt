package com.voicepad.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepad.app.RecordingController
import com.voicepad.app.data.Note
import com.voicepad.app.llm.LlmPrompt
import com.voicepad.app.transmit.SinkStatus
import kotlinx.coroutines.delay

/** The hinge's top and bottom edge in window pixels while half-folded with a horizontal hinge. */
data class TabletopHinge(val top: Int, val bottom: Int)

/**
 * Tabletop (flex) mode: the upper half shows what's being said, the
 * recording status and the note you're on; the lower half is a control pad:
 * browse notes, send (typed on the connected computer, else copied), send
 * through the LLM prompt, pause, split, record/stop.
 */
@Composable
fun TabletopScreen(controller: RecordingController, hinge: TabletopHinge) {
    val state by controller.state.collectAsStateWithLifecycle()
    val notes by controller.store.notes.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val topHeight = with(density) { hinge.top.toDp() }
    val hingeHeight = with(density) { (hinge.bottom - hinge.top).coerceAtLeast(0).toDp() }

    // Finished notes, newest first; the one being recorded isn't sendable yet.
    val finished = notes
        .filter { !it.archived && it.id != state.recordingNoteId && it.currentText.isNotBlank() }
        .sortedByDescending { it.createdAt }
    // null = follow the newest note (and the live transcript while recording).
    var browsingId by rememberSaveable { mutableStateOf<String?>(null) }
    val browsing = browsingId?.let { id -> finished.firstOrNull { it.id == id } }
    val target = browsing ?: finished.firstOrNull()
    val index = target?.let { finished.indexOf(it) } ?: -1

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().height(topHeight)) {
            TopPane(controller, state, browsing, target, index, finished.size)
            // While paused the transcript half turns amber; the pad below keeps Resume handy.
            PausedOverlay(controller, state, compact = true)
        }
        Spacer(Modifier.height(hingeHeight))
        ControlPad(
            controller = controller,
            state = state,
            canOlder = index in 0 until finished.size - 1,
            canNewer = browsing != null,
            target = target,
            onOlder = { if (index + 1 < finished.size) browsingId = finished[index + 1].id },
            onNewer = { browsingId = if (index <= 1) null else finished[index - 1].id },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TopPane(
    controller: RecordingController,
    state: RecordingController.UiState,
    browsing: Note?,
    target: Note?,
    index: Int,
    count: Int,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(state)
            Spacer(Modifier.weight(1f))
            Text(
                when (state.transmitStatus) {
                    SinkStatus.CONNECTED -> if (state.isTyping) "Typing…" else state.transmitHost ?: "Connected"
                    SinkStatus.ADVERTISING, SinkStatus.REGISTERING -> "Keyboard waiting"
                    else -> "Keyboard off"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            KeyboardButton(controller, state, onTypeLatest = {
                target?.let { controller.sendText(it.currentText, rewrite = false) }
            })
        }

        val scroll = rememberScrollState()
        val live = state.isRecording && browsing == null
        val text = when {
            live -> state.liveText.ifBlank { if (state.pausedAt != null) "" else "Listening…" }
            else -> target?.currentText ?: "No notes yet. Tap ● below to start recording."
        }
        // Keep the newest words in view while the transcript grows.
        LaunchedEffect(text, live) { if (live) scroll.animateScrollTo(scroll.maxValue) else scroll.scrollTo(0) }
        Text(
            when {
                live -> "Live" + (if (state.partNumber > 1) " · part ${state.partNumber}" else "")
                target != null -> "Note ${index + 1} of $count · " +
                    DateUtils.getRelativeTimeSpanString(target.createdAt).toString() +
                    if (browsing == null) " · latest" else ""
                else -> ""
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(top = 4.dp),
        )
    }
}

@Composable
private fun StatusChip(state: RecordingController.UiState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAt, state.pausedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsed = state.startedAt?.let {
        DateUtils.formatElapsedTime((((state.pausedAt ?: now) - it) / 1000).coerceAtLeast(0))
    }
    val (label, color) = when {
        state.pausedAt != null -> "PAUSED $elapsed" to PausedAmber
        state.isRecording -> "REC $elapsed" to RecordingRed
        else -> "Not recording" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.isRecording) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ControlPad(
    controller: RecordingController,
    state: RecordingController.UiState,
    canOlder: Boolean,
    canNewer: Boolean,
    target: Note?,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    modifier: Modifier,
) {
    val connected = state.transmitStatus == SinkStatus.CONNECTED
    val prompt = LlmPrompt.presetFor(state.typePrompt)?.label ?: "your prompt"
    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton(Icons.Default.ChevronLeft, "Previous", enabled = canOlder, onClick = onOlder)
                PadButton(
                    Icons.Default.KeyboardReturn,
                    if (connected) "Send" else "Copy",
                    enabled = target != null && !state.isTyping,
                    weight = 1.4f,
                ) { target?.let { controller.sendText(it.currentText, rewrite = false) } }
                PadButton(Icons.Default.ChevronRight, "Next", enabled = canNewer, onClick = onNewer)
            }
            Row(Modifier.weight(1f)) {
                PadButton(
                    Icons.Default.AutoAwesome,
                    (if (connected) "Send" else "Copy") + " with LLM · $prompt",
                    enabled = target != null && !state.isTyping,
                ) { target?.let { controller.sendText(it.currentText, rewrite = true) } }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val paused = state.pausedAt != null
                PadButton(
                    if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    if (paused) "Resume" else "Pause",
                    enabled = state.isRecording,
                    onClick = controller::togglePause,
                )
                PadButton(Icons.Default.ContentCut, "Split", enabled = state.isRecording, onClick = controller::split)
                PadButton(
                    if (state.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    if (state.isRecording) "Stop" else "Record",
                    onClick = controller::toggle,
                )
            }
        }
    }
}

@Composable
private fun RowScope.PadButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(weight).fillMaxSize(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }
}
