package com.voicepad.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepad.app.RecordingController
import com.voicepad.app.RecordingController.LlmStatus
import com.voicepad.app.data.Note
import com.voicepad.app.transmit.AutoSend
import com.voicepad.app.transmit.SinkStatus
import kotlinx.coroutines.delay

/** The hinge's top and bottom edge in window pixels while half-folded with a horizontal hinge. */
data class TabletopHinge(val top: Int, val bottom: Int)

/** Not recording in laptop mode reads as "paused": amber, nothing like recording red or the theme. */
private val PausedAmber = Color(0xFFFFB300)
private val OnPausedAmber = Color(0xFF2B1D00)

/** The Stop button: bright red, unmistakable. */
private val StopRed = Color(0xFFFF1744)

/**
 * Laptop (tabletop) mode: the upper half shows what's being said next to the
 * LLM's interpretation of it, the lower half is a control pad: browse notes,
 * send the original or either prompt's rewrite (typed on the connected
 * computer, else copied), auto send mode, split and record / stop. While not
 * recording the upper half turns amber: stopped is "paused" here.
 */
@Composable
fun TabletopScreen(controller: RecordingController, hinge: TabletopHinge) {
    val state by controller.state.collectAsStateWithLifecycle()
    val notes by controller.store.notes.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val topHeight = with(density) { hinge.top.toDp() }
    val hingeHeight = with(density) { (hinge.bottom - hinge.top).coerceAtLeast(0).toDp() }

    // Finished notes, newest first; the one being recorded shows as the live transcript.
    val finished = notes
        .filter { !it.archived && it.id != state.recordingNoteId && it.currentText.isNotBlank() }
        .sortedByDescending { it.createdAt }
    // null = follow the newest (the live transcript while recording).
    var browsingId by rememberSaveable { mutableStateOf<String?>(null) }
    val browsing = browsingId?.let { id -> finished.firstOrNull { it.id == id } }
    val note = browsing ?: finished.firstOrNull()
    val index = note?.let { finished.indexOf(it) } ?: -1
    val live = state.isRecording && browsing == null
    // What's shown, interpreted and sent.
    val text = if (live) state.liveText else note?.currentText.orEmpty()

    // Which prompt's interpretation is shown (0 = prompt 1, 1 = prompt 2).
    var viewSlot by rememberSaveable { mutableIntStateOf(0) }
    val prompts = listOf(state.typePrompt, state.typePrompt2)
    val viewPrompt = prompts[viewSlot]
    var interpretation by remember { mutableStateOf<String?>(null) }
    var interpreting by remember { mutableStateOf(false) }
    val llmReady = state.llmStatus is LlmStatus.Ready
    LaunchedEffect(text, viewPrompt, llmReady) {
        interpretation = null
        if (text.isBlank() || !llmReady) return@LaunchedEffect
        // Live text changes with every word: wait for a pause in speech first.
        if (live) delay(1500)
        interpreting = true
        try {
            interpretation = controller.interpret(text, viewPrompt)
        } finally {
            interpreting = false
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopPane(
            controller = controller,
            state = state,
            live = live,
            note = note,
            index = index,
            count = finished.size,
            text = text,
            prompts = prompts,
            viewSlot = viewSlot,
            onViewSlot = { viewSlot = it },
            interpretation = interpretation,
            interpreting = interpreting,
            modifier = Modifier.fillMaxWidth().height(topHeight),
        )
        Spacer(Modifier.height(hingeHeight))
        ControlPad(
            controller = controller,
            state = state,
            text = text,
            prompts = prompts,
            canOlder = index in 0 until finished.size - 1 || (live && finished.isNotEmpty()),
            canNewer = browsing != null,
            onOlder = {
                when {
                    live -> finished.firstOrNull()?.let { browsingId = it.id }
                    index + 1 < finished.size -> browsingId = finished[index + 1].id
                }
            },
            onNewer = {
                // Past the newest note: back to following the latest (live while recording).
                browsingId = if (index <= 0 || (index == 1 && !state.isRecording)) null else finished[index - 1].id
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TopPane(
    controller: RecordingController,
    state: RecordingController.UiState,
    live: Boolean,
    note: Note?,
    index: Int,
    count: Int,
    text: String,
    prompts: List<String>,
    viewSlot: Int,
    onViewSlot: (Int) -> Unit,
    interpretation: String?,
    interpreting: Boolean,
    modifier: Modifier,
) {
    val paused = !state.isRecording
    val onPane = if (paused) OnPausedAmber else MaterialTheme.colorScheme.onBackground
    val subtle = if (paused) OnPausedAmber.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .background(if (paused) PausedAmber else MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(state)
            Spacer(Modifier.width(16.dp))
            Text(
                when {
                    live -> "Live" + (if (state.partNumber > 1) " · part ${state.partNumber}" else "")
                    note != null -> "Note ${index + 1} of $count · " +
                        DateUtils.getRelativeTimeSpanString(note.createdAt).toString()
                    else -> ""
                },
                style = MaterialTheme.typography.labelLarge,
                color = subtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                when (state.transmitStatus) {
                    SinkStatus.CONNECTED -> if (state.isTyping) "Typing…" else state.transmitHost ?: "Connected"
                    SinkStatus.ADVERTISING, SinkStatus.REGISTERING -> "Keyboard waiting"
                    else -> "Keyboard off"
                },
                style = MaterialTheme.typography.labelLarge,
                color = subtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            KeyboardButton(controller, state, onTypeLatest = { controller.sendText(text, prompt = null) })
        }

        Row(Modifier.weight(1f).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Left: what was said.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("Transcript", style = MaterialTheme.typography.labelLarge, color = subtle)
                ScrollingText(
                    text.ifBlank {
                        when {
                            live -> "Listening…"
                            paused && note == null -> "Nothing recorded yet. Tap Record below."
                            else -> ""
                        }
                    },
                    follow = live,
                    color = onPane,
                    modifier = Modifier.weight(1f),
                )
            }
            // Right: the model's interpretation with the chosen prompt.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    prompts.forEachIndexed { i, prompt ->
                        FilterChip(
                            selected = viewSlot == i,
                            onClick = { onViewSlot(i) },
                            label = { Text("✨${i + 1} ${promptLabel(prompt)}", maxLines = 1) },
                        )
                    }
                }
                val llm = state.llmStatus
                when {
                    interpretation != null ->
                        ScrollingText(interpretation, follow = false, color = onPane, modifier = Modifier.weight(1f))
                    text.isBlank() -> Spacer(Modifier.weight(1f))
                    llm is LlmStatus.Loading -> Hint(
                        if (llm.downloading) "Downloading the model… ${(llm.progress * 100).toInt()}%" else "Loading the model…",
                        subtle,
                    )
                    llm is LlmStatus.Failed -> Column(Modifier.weight(1f)) {
                        Hint("Model failed: ${llm.message}", subtle)
                        TextButton(onClick = controller::ensureLlm) { Text("Retry", color = onPane) }
                    }
                    llm !is LlmStatus.Ready -> Column(Modifier.weight(1f)) {
                        Hint("The LLM isn't loaded.", subtle)
                        TextButton(onClick = controller::ensureLlm) { Text("Load model", color = onPane) }
                    }
                    interpreting || live -> Hint("Thinking…", subtle)
                    else -> Hint("No rewrite.", subtle)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, color: Color) {
    Text(text, color = color, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 8.dp))
}

/** Large text that scrolls; with [follow] it keeps the newest words in view. */
@Composable
private fun ScrollingText(text: String, follow: Boolean, color: Color, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(text, follow) { if (follow) scroll.animateScrollTo(scroll.maxValue) else scroll.scrollTo(0) }
    Text(
        text,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        color = color,
        modifier = modifier.fillMaxWidth().verticalScroll(scroll).padding(top = 4.dp),
    )
}

@Composable
private fun StatusChip(state: RecordingController.UiState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAt) {
        while (state.startedAt != null) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    if (state.isRecording) {
        val elapsed = state.startedAt?.let { DateUtils.formatElapsedTime(((now - it) / 1000).coerceAtLeast(0)) }.orEmpty()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(RecordingRed, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("REC $elapsed", color = RecordingRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    } else {
        // Stopped is paused in laptop mode: say so, big.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Pause, contentDescription = null, tint = OnPausedAmber, modifier = Modifier.size(32.dp))
            Text("PAUSED", color = OnPausedAmber, fontWeight = FontWeight.Black, fontSize = 28.sp, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun ControlPad(
    controller: RecordingController,
    state: RecordingController.UiState,
    text: String,
    prompts: List<String>,
    canOlder: Boolean,
    canNewer: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    modifier: Modifier,
) {
    val connected = state.transmitStatus == SinkStatus.CONNECTED
    val canSend = text.isNotBlank() && !state.isTyping
    val verb = if (connected) "Send" else "Copy"
    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.weight(0.8f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton(Icons.Default.ChevronLeft, "Previous", enabled = canOlder, onClick = onOlder)
                PadButton(Icons.Default.ChevronRight, "Next", enabled = canNewer, onClick = onNewer)
                // Small: tap cycles no auto send → on copy → after LLM.
                PadButton(
                    Icons.Default.AutoAwesome,
                    "Auto: ${state.autoSend.short}",
                    weight = 0.7f,
                    onClick = {
                        val modes = AutoSend.entries
                        controller.setAutoSend(modes[(state.autoSend.ordinal + 1) % modes.size])
                    },
                )
                // Tap to split; drag up for auto-split, as on the main screen.
                SplitPad(controller, state)
            }
            Row(Modifier.weight(1.2f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton(Icons.Default.KeyboardReturn, "$verb original", enabled = canSend) {
                    controller.sendText(text, prompt = null)
                }
                prompts.forEachIndexed { i, prompt ->
                    PadButton(Icons.Default.AutoAwesome, "$verb ✨${i + 1} ${promptLabel(prompt)}", enabled = canSend) {
                        controller.sendText(text, prompt)
                    }
                }
            }
            Row(Modifier.weight(0.9f)) {
                if (state.isRecording) {
                    PadButton(
                        Icons.Default.Stop,
                        "Stop",
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = StopRed, contentColor = Color.White),
                        onClick = controller::stop,
                    )
                } else {
                    PadButton(Icons.Default.FiberManualRecord, "Record", onClick = controller::start)
                }
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
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.weight(weight).fillMaxSize(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }
}
