package com.voicepad.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepad.app.RecordingController
import com.voicepad.app.RecordingController.EngineStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePadScreen(controller: RecordingController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val notes by controller.store.notes.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var editingId by remember { mutableStateOf<String?>(null) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val visibleNotes = notes.filter { it.id != state.recordingNoteId }
    val showLive = state.isRecording || state.recordingNoteId != null
    val listState = rememberLazyListState()
    val itemCount = visibleNotes.size + if (showLive) 1 else 0

    LaunchedEffect(itemCount, state.liveText) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    fun copy(text: String) = copyToClipboard(context, text)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VoicePad") },
                navigationIcon = { EngineStatusBadge(state, Modifier.padding(start = 12.dp)) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = { RecordBar(controller, state) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (visibleNotes.isEmpty() && !showLive) {
                EmptyState(Modifier.align(Alignment.Center))
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visibleNotes, key = { it.id }) { note ->
                    NoteBubble(
                        note = note,
                        isBusy = note.id in state.busyNoteIds,
                        canRetranscribe = state.engineStatus == EngineStatus.Ready,
                        onCopy = { copy(note.currentText) },
                        onEdit = { editingId = note.id },
                        onHistory = { historyId = note.id },
                        onRetranscribe = { controller.retranscribe(note.id) },
                        onDelete = { controller.store.delete(note.id) },
                    )
                }
                if (showLive) {
                    item(key = "live") { LiveBubble(state) }
                }
            }
        }
    }

    editingId?.let { id ->
        controller.store.note(id)?.let { note ->
            EditNoteDialog(
                initialText = note.currentText,
                onDismiss = { editingId = null },
                onSave = { text ->
                    controller.store.addRevision(id, text, com.voicepad.app.data.RevisionSource.EDIT)
                    editingId = null
                },
            )
        }
    }
    historyId?.let { id ->
        notes.firstOrNull { it.id == id }?.let { note ->
            HistorySheet(
                note = note,
                onCopy = ::copy,
                onRestore = { controller.store.restore(id, it) },
                onDismiss = { historyId = null },
            )
        }
    }
    if (showSettings) {
        SettingsSheet(controller, state, onDismiss = { showSettings = false })
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.Mic, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Opening VoicePad starts recording.\nYour words appear here as you speak.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EngineStatusBadge(state: RecordingController.UiState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when (val status = state.engineStatus) {
            EngineStatus.Idle -> {}
            is EngineStatus.Loading -> {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(4.dp))
                if (status.downloading) Text("${(status.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            EngineStatus.Ready -> Icon(
                Icons.Default.CheckCircle, contentDescription = "Model ready",
                tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp),
            )
            is EngineStatus.Failed -> Icon(
                Icons.Default.Warning, contentDescription = "Model failed",
                tint = Color(0xFFEF6C00), modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            state.engineChoice.shortName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("VoicePad", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/** Used to keep bubbles from spanning the full width. */
internal val BubbleStartInset = Modifier.fillMaxWidth().padding(start = 40.dp)
