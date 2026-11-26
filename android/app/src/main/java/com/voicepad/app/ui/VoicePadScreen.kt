package com.voicepad.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.text.format.DateUtils
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.mutableIntStateOf
import com.voicepad.app.data.NoteCleanup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.outlined.HearingDisabled
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.voicepad.app.transcription.EngineChoice
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepad.app.RecordingController
import com.voicepad.app.llm.LlmPrompt
import com.voicepad.app.RecordingController.EngineStatus
import com.voicepad.app.conversation.ConversationController
import com.voicepad.app.transmit.SinkStatus
import androidx.compose.material.icons.filled.Translate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePadScreen(controller: RecordingController, conversation: ConversationController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val conversationState by conversation.state.collectAsStateWithLifecycle()
    val notes by controller.store.notes.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var editingId by remember { mutableStateOf<String?>(null) }
    /** Words an edit just introduced, offered for a dictionary. */
    var newWords by remember { mutableStateOf<List<String>?>(null) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var showArchive by remember { mutableStateOf(false) }
    var showCleanup by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    // Pop-up / split-screen / small windows get a tighter layout. Measured from the
    // window rather than the screen, so a freeform window is sized as it appears.
    val container = LocalWindowInfo.current.containerSize
    val compact = with(LocalDensity.current) {
        container.width.toDp() < 420.dp || container.height.toDp() < 560.dp
    }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = searchActive && query.isNotBlank()
    BackHandler(enabled = searchActive) { searchActive = false; query = "" }

    val visibleNotes = notes.filter { it.id != state.recordingNoteId && !it.archived }
    val archivedNotes = notes.filter { it.archived }.sortedByDescending { it.archivedAt ?: it.createdAt }
    val pinnedNotes = visibleNotes.filter { it.pinned }.sortedByDescending { it.pinnedAt ?: 0L }
    val otherNotes = visibleNotes.filterNot { it.pinned }
    val results = remember(notes, query, searching) {
        // Search also finds archived notes.
        if (searching) NoteSearch.search(notes.filter { it.id != state.recordingNoteId }, query) else emptyList()
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

    // Copying also types the text when a Bluetooth keyboard host is connected.
    fun copy(text: String) {
        copyToClipboard(context, text)
        if (controller.sendToKeyboardIfConnected(text)) {
            Toast.makeText(context, "Copied and typing on ${state.transmitHost ?: "the paired device"}", Toast.LENGTH_SHORT).show()
        }
    }

    // Keyboard mode's hold-a-note rewrites: the two active prompts, the other presets, then the user's recent ones.
    val typePrompts = remember(state.typePrompt, state.typePrompt2, state.recentPrompts) {
        (listOf(state.typePrompt, state.typePrompt2) + LlmPrompt.PRESETS.map { it.instruction } + state.recentPrompts)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { promptLabel(it) to it }
    }

    @Composable
    fun Bubble(note: Note, highlight: List<String> = emptyList(), matchedEarlier: Boolean = false) {
        NoteBubble(
            compact = compact,
            tapAction = state.noteTapAction,
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
            onArchive = { if (note.archived) controller.unarchive(note.id) else controller.archive(listOf(note.id)) },
            onCopyText = ::copy,
            onType = if (state.transmitStatus == SinkStatus.CONNECTED) {
                { controller.typeNote(note.id) }
            } else {
                null
            },
            keyboardMode = state.keyboardMode,
            typePrompts = typePrompts,
            onTypeWith = { prompt -> controller.typeNoteWith(note.id, prompt) },
            onKeyboardTap = { controller.retypeNote(note.id) },
        )
    }

    Scaffold(
        topBar = {
          Column {
            if (searchActive) {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onClose = { searchActive = false; query = "" },
                )
            } else {
                val recording = state.isRecording
                val onBar = if (recording) Color.White else MaterialTheme.colorScheme.onSurface
                TopAppBar(
                    // Unmistakable: the whole bar turns red while the mic is on (blue in keyboard mode).
                    colors = if (recording) {
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = recordingColor(state),
                            titleContentColor = Color.White,
                            actionIconContentColor = Color.White,
                            navigationIconContentColor = Color.White,
                        )
                    } else {
                        TopAppBarDefaults.topAppBarColors()
                    },
                    title = {
                        if (recording) RecordingTitle(state, compact) else IdleTitle(compact)
                    },
                    navigationIcon = {
                        ModelSwitcher(controller, state, onBar, compact, onMoreModels = { showSettings = true })
                    },
                    actions = {
                        if (!compact) {
                            IconButton(onClick = { controller.setBackgroundVoices(!state.backgroundVoices) }) {
                                Icon(
                                    if (state.backgroundVoices) Icons.Default.Hearing else Icons.Outlined.HearingDisabled,
                                    contentDescription = if (state.backgroundVoices) "Background voices on" else "Background voices off",
                                    tint = if (state.backgroundVoices && !recording) MaterialTheme.colorScheme.primary else onBar,
                                )
                            }
                        }
                        IconButton(onClick = { conversation.open() }) {
                            Icon(Icons.Default.Translate, contentDescription = "Conversation mode")
                        }
                        KeyboardButton(controller, state, onRecordingBar = recording, onTypeLatest = {
                            val latest = notes.filter { !it.archived && it.currentText.isNotBlank() }
                                .maxByOrNull { it.createdAt }
                            if (latest != null) {
                                controller.typeNote(latest.id)
                            } else {
                                Toast.makeText(context, "No note to type yet.", Toast.LENGTH_SHORT).show()
                            }
                        })
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        if (!compact) {
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                if (compact) {
                                    // In a small window these live here instead of on the bar.
                                    DropdownMenuItem(
                                        text = { Text("Background voices") },
                                        leadingIcon = { Icon(Icons.Default.Hearing, contentDescription = null) },
                                        trailingIcon = { Switch(checked = state.backgroundVoices, onCheckedChange = null) },
                                        onClick = { controller.setBackgroundVoices(!state.backgroundVoices) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        onClick = { overflowOpen = false; showSettings = true },
                                    )
                                    HorizontalDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text("Archived notes (${archivedNotes.size})") },
                                    leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                                    onClick = { overflowOpen = false; showArchive = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Clean up…") },
                                    leadingIcon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                                    onClick = { overflowOpen = false; showCleanup = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export all notes…") },
                                    leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                                    onClick = { overflowOpen = false; showExport = true },
                                )
                            }
                        }
                    },
                )
            }
            KeyboardModeStrip(state)
          }
        },
        bottomBar = { RecordBar(controller, state, compact) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .then(if (state.isRecording) Modifier.border(3.dp, recordingColor(state).copy(alpha = 0.6f)) else Modifier)
        ) {
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
                    val before = note.currentText
                    controller.store.addRevision(id, text, com.voicepad.app.data.RevisionSource.EDIT)
                    editingId = null
                    // Offer the words this edit brought in for a dictionary.
                    newWords = com.voicepad.app.data.DictionaryWords
                        .newWords(before, text, known = controller.dictionaries.allWords())
                        .takeIf { it.isNotEmpty() }
                },
            )
        }
    }
    newWords?.let { words ->
        SaveWordsDialog(words, controller.dictionaries, onDismiss = { newWords = null })
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
                    controller.delete(listOf(id))
                    confirmDeleteId = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteId = null }) { Text("Cancel") } },
        )
    }
    if (showSettings) {
        SettingsSheet(
            controller, state,
            openOnUnfold = conversationState.settings.openOnUnfold,
            onOpenOnUnfoldChange = conversation::setOpenOnUnfold,
            onDismiss = { showSettings = false },
        )
    }
    if (state.showSetup) {
        SetupSheet(
            controller = controller,
            state = state,
            onDismiss = { controller.dismissSetup() },
        )
    }
    if (showArchive) {
        ModalBottomSheet(onDismissRequest = { showArchive = false }) {
            Text(
                "Archived notes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                "Audio is kept compressed. Long-press a note to unarchive or delete it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (archivedNotes.isEmpty()) {
                Text("Nothing archived yet.", modifier = Modifier.padding(20.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(archivedNotes, key = { "archived-" + it.id }) { Bubble(it) }
            }
        }
    }
    if (showExport) {
        ExportDialog(controller, onDismiss = { showExport = false })
    }
    if (showCleanup) {
        CleanupDialog(controller, onDismiss = { showCleanup = false })
    }
}

/** Archive, compress, delete empty, or delete notes older than 1, 7 or 30 days (or any age). */
@Composable
private fun CleanupDialog(controller: RecordingController, onDismiss: () -> Unit) {
    var action by remember { mutableStateOf(NoteCleanup.Action.ARCHIVE) }
    var days by remember { mutableIntStateOf(7) }
    val matches = remember(action, days) { controller.cleanupCandidates(action, days) }
    val count = matches.size
    val sizes = remember(matches, action) {
        if (action == NoteCleanup.Action.COMPRESS || action == NoteCleanup.Action.ARCHIVE) controller.audioSizes(matches) else null
    }
    val destructive = action == NoteCleanup.Action.DELETE || action == NoteCleanup.Action.DELETE_EMPTY
    val notes = "$count note${if (count == 1) "" else "s"}"
    fun mb(bytes: Long) = if (bytes >= 10_000_000) "${bytes / 1_000_000} MB" else "%.1f MB".format(bytes / 1_000_000.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clean up notes") },
        text = {
            Column {
                Text("Notes older than:", style = MaterialTheme.typography.bodyMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
                ) {
                    listOf(0 to "Any age", 1 to "1 day", 7 to "7 days", 30 to "30 days").forEach { (d, label) ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
                    }
                }
                listOf(
                    listOf(NoteCleanup.Action.ARCHIVE to Icons.Default.Archive, NoteCleanup.Action.COMPRESS to Icons.Default.Compress),
                    listOf(NoteCleanup.Action.DELETE_EMPTY to Icons.Default.DeleteSweep, NoteCleanup.Action.DELETE to Icons.Default.Delete),
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (a, icon) ->
                            FilterChip(
                                selected = action == a,
                                onClick = { action = a },
                                label = { Text(a.label) },
                                leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        count == 0 -> "No notes match."
                        action == NoteCleanup.Action.ARCHIVE ->
                            "$notes will be archived: hidden from the list, audio compressed to about a tenth of its size."
                        action == NoteCleanup.Action.COMPRESS ->
                            "$notes will have their audio compressed and stay in the list."
                        action == NoteCleanup.Action.DELETE_EMPTY ->
                            "$notes with no speech (\u201cNo speech detected\u201d) and their audio will be permanently deleted."
                        else -> "$notes and their audio will be permanently deleted."
                    },
                    color = if (destructive && count > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (sizes != null && count > 0 && sizes.first > sizes.second) {
                    Text(
                        "Audio: ${mb(sizes.first)} \u2192 about ${mb(sizes.second)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    when (action) {
                        NoteCleanup.Action.COMPRESS -> "Includes pinned notes. Each file is checked before the original is removed."
                        NoteCleanup.Action.DELETE_EMPTY -> "Pinned notes and recordings still waiting for transcription are kept."
                        else -> "Pinned notes are never touched."
                    } + if (action == NoteCleanup.Action.COMPRESS || action == NoteCleanup.Action.DELETE_EMPTY) {
                        " To do this automatically, see Settings \u2192 Automatic clean-up."
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = count > 0,
                onClick = {
                    controller.cleanUp(action, days)
                    onDismiss()
                },
            ) {
                Text(
                    when (action) {
                        NoteCleanup.Action.ARCHIVE -> "Archive $count"
                        NoteCleanup.Action.COMPRESS -> "Compress $count"
                        else -> "Delete $count"
                    },
                    color = if (destructive && count > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModelSwitcher(
    controller: RecordingController,
    state: RecordingController.UiState,
    color: Color,
    compact: Boolean,
    onMoreModels: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .semantics { contentDescription = "Model: ${state.activeEngine.shortName}. Tap to switch." },
        ) {
            // Small windows show just the model's status; the name is in the menu.
            EngineStatusBadge(state, color = color, showName = !compact)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Downloaded models",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            val installed = EngineChoice.entries.filter { it in state.installedEngines }
            if (installed.isEmpty()) {
                Text(
                    "None yet — the first one is downloading.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            installed.forEach { choice ->
                val active = choice == state.activeEngine
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(choice.displayName, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                            if (choice == RecordingController.BACKGROUND_ENGINE) {
                                Text("Background voices", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    leadingIcon = {
                        if (active) Icon(Icons.Default.Check, contentDescription = "In use")
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        open = false
                        controller.selectModel(choice)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Background voices")
                        Text(
                            "Announcements, TV across the room" +
                                if (RecordingController.BACKGROUND_ENGINE !in state.installedEngines) " · ~560 MB download" else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                leadingIcon = { Icon(Icons.Default.Hearing, contentDescription = null) },
                trailingIcon = {
                    Switch(checked = state.backgroundVoices, onCheckedChange = null)
                },
                onClick = { controller.setBackgroundVoices(!state.backgroundVoices) },
            )
            DropdownMenuItem(
                text = { Text("More models…") },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = {
                    open = false
                    onMoreModels()
                },
            )
        }
    }
}

/** Top bar title while recording: pulsing dot, REC, elapsed time, part number. */
@Composable
private fun RecordingTitle(state: RecordingController.UiState, compact: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsed = state.startedAt?.let {
        DateUtils.formatElapsedTime(((now - it) / 1000).coerceAtLeast(0))
    } ?: ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulsingDot(color = Color.White, size = 12.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            if (compact) elapsed else "REC  $elapsed",
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
        if (state.partNumber > 1) {
            Text(
                if (compact) " · ${state.partNumber}" else "  · part ${state.partNumber}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Top bar title when the mic is off. */
@Composable
private fun IdleTitle(compact: Boolean) {
    if (compact) {
        Text("VoicePad", maxLines = 1, softWrap = false)
        return
    }
    Column {
        Text("VoicePad", maxLines = 1, softWrap = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MicOff, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text("Not recording", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
fun EngineStatusBadge(
    state: RecordingController.UiState,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showName: Boolean = true,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when (val status = state.engineStatus) {
            EngineStatus.Idle -> {}
            is EngineStatus.Loading -> {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
                Spacer(Modifier.width(4.dp))
                if (status.downloading) Text("${(status.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = color)
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
        if (showName) {
            Spacer(Modifier.width(4.dp))
            Text(
                state.activeEngine.shortName,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
            )
        }
    }
}

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("VoicePad", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/** Used to keep bubbles from spanning the full width. */
internal val BubbleStartInset = Modifier.fillMaxWidth().padding(start = 40.dp)
