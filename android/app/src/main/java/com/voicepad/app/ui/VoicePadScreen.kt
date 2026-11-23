package com.voicepad.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import com.voicepad.app.data.Note
import com.voicepad.app.data.NoteSearch
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = searchActive && query.isNotBlank()
    BackHandler(enabled = searchActive) { searchActive = false; query = "" }

    val visibleNotes = notes.filter { it.id != state.recordingNoteId }
    val pinnedNotes = visibleNotes.filter { it.pinned }.sortedByDescending { it.pinnedAt ?: 0L }
    val otherNotes = visibleNotes.filterNot { it.pinned }
    val results = remember(notes, query, searching) {
        if (searching) NoteSearch.search(visibleNotes, query) else emptyList()
    }
    val terms = remember(query) { NoteSearch.terms(query) }

    val showLive = state.isRecording || state.recordingNoteId != null
    val listState = rememberLazyListState()
    val needingText = visibleNotes.count { it.needsTranscription }
    val itemCount = (if (needingText > 0) 1 else 0) +
        (if (pinnedNotes.isNotEmpty()) 1 + pinnedNotes.size + (if (otherNotes.isNotEmpty() || showLive) 1 else 0) else 0) +
        otherNotes.size + (if (showLive) 1 else 0)

    // Follow new notes and live text to the bottom, except while searching.
    LaunchedEffect(visibleNotes.size, showLive, state.liveText, searching) {
        if (!searching && itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    fun copy(text: String) = copyToClipboard(context, text)

    @Composable
    fun Bubble(note: Note, highlight: List<String> = emptyList(), matchedEarlier: Boolean = false) {
        NoteBubble(
            note = note,
            engineStatus = state.engineStatus,
            isPlaying = player.playingId == note.id,
            highlightTerms = highlight,
            matchedInEarlierRevision = matchedEarlier,
            onCopy = { copy(note.currentText.ifEmpty { note.partialText.orEmpty() }) },
            onEdit = { editingId = note.id },
            onHistory = { historyId = note.id },
            onRetranscribe = { controller.retranscribe(note.id) },
            onTogglePin = { controller.store.setPinned(note.id, !note.pinned) },
            onPlay = { player.toggle(context, note.id, controller.store.audioFile(note)) },
            onShare = { shareRecording(context, controller.store.audioFile(note), note.currentText) },
            onDelete = { confirmDeleteId = note.id },
            onTranslate = { controller.translateNow(note.id) },
            onCopyText = ::copy,
        )
    }

    Scaffold(
        topBar = {
            if (searchActive) {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onClose = { searchActive = false; query = "" },
                )
            } else {
                TopAppBar(
                    title = { Text("VoicePad") },
                    navigationIcon = { EngineStatusBadge(state, Modifier.padding(start = 12.dp)) },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
        bottomBar = { RecordBar(controller, state) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!searching && visibleNotes.isEmpty() && !showLive) {
                EmptyState(Modifier.align(Alignment.Center))
            }
            if (searching && results.isEmpty()) {
                Text(
                    "No notes match \u201c${query.trim()}\u201d.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (searching) {
                    if (results.isNotEmpty()) {
                        item(key = "results-header") {
                            SectionHeader(if (results.size == 1) "1 result" else "${results.size} results")
                        }
                    }
                    items(results, key = { "result-" + it.note.id }) { result ->
                        Bubble(
                            result.note,
                            highlight = terms,
                            matchedEarlier = result.matchedIn == NoteSearch.MatchedIn.EARLIER_REVISION,
                        )
                    }
                    return@LazyColumn
                }
                if (needingText > 0) {
                    item(key = "retry-banner") {
                        RetryBanner(needingText, state.engineStatus, onRetryAll = controller::retryAll)
                    }
                }
                if (pinnedNotes.isNotEmpty()) {
                    item(key = "pinned-header") { SectionHeader("Pinned", Icons.Default.PushPin) }
                    items(pinnedNotes, key = { it.id }) { Bubble(it) }
                    if (otherNotes.isNotEmpty() || showLive) {
                        item(key = "all-header") { SectionHeader("All notes") }
                    }
                }
                items(otherNotes, key = { it.id }) { Bubble(it) }
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
    confirmDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete recording?") },
            text = { Text("This permanently deletes the audio and every revision of its transcript.") },
            confirmButton = {
                TextButton(onClick = {
                    if (player.playingId == id) player.stop()
                    controller.store.delete(id)
                    confirmDeleteId = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteId = null }) { Text("Cancel") } },
        )
    }
    if (showSettings) {
        SettingsSheet(controller, state, onDismiss = { showSettings = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search all notes and revisions") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
        }
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Shown above the list while any recording still needs text. */
@Composable
private fun RetryBanner(count: Int, engineStatus: EngineStatus, onRetryAll: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (count == 1) "1 recording is saved and waiting for text." else "$count recordings are saved and waiting for text.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetryAll, enabled = engineStatus == EngineStatus.Ready) { Text("Retry all") }
        }
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
