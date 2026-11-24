package com.voicepad.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.voicepad.app.RecordingController
import com.voicepad.app.RecordingController.EngineStatus
import com.voicepad.app.RecordingController.LlmStatus
import com.voicepad.app.data.Note
import com.voicepad.app.data.Revision
import com.voicepad.app.llm.Guidance
import com.voicepad.app.llm.LlmChoice
import com.voicepad.app.llm.LlmPrompt.TypeStyle
import com.voicepad.app.transcription.EngineChoice
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditNoteDialog(initialText: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Edit") },
                        navigationIcon = { TextButton(onClick = onDismiss) { Text("Cancel") } },
                        actions = {
                            TextButton(
                                onClick = { onSave(value.text) },
                                enabled = value.text.trim() != initialText,
                            ) { Text("Save") }
                        },
                    )
                },
            ) { padding ->
                Column(Modifier.padding(padding).padding(16.dp).imePadding()) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focus),
                    )
                    Text(
                        "Saving keeps the previous version in history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    note: Note,
    onCopy: (String) -> Unit,
    onRestore: (Revision) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getMediumDateFormat(context) }
    val timeFormat = remember { DateFormat.getTimeFormat(context) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            "Revision history",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (note.revisions.isEmpty()) {
            Text("No revisions yet.", modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            val newestFirst = note.revisions.withIndex().reversed()
            itemsIndexed(newestFirst, key = { _, r -> r.value.id }) { _, (index, revision) ->
                val isCurrent = index == note.revisions.lastIndex
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("v${index + 1} · ${revision.source.label}", fontWeight = FontWeight.SemiBold)
                        if (isCurrent) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Current",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    val date = Date(revision.timestamp)
                    Text(
                        "${dateFormat.format(date)} ${timeFormat.format(date)}" + (revision.engine?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(revision.text.ifEmpty { "(empty)" }, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { onCopy(revision.text) },
                            label = { Text("Copy") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(AssistChipDefaults.IconSize)) },
                        )
                        if (!isCurrent) {
                            AssistChip(
                                onClick = { onRestore(revision) },
                                label = { Text("Restore") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(AssistChipDefaults.IconSize)) },
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    controller: RecordingController,
    state: RecordingController.UiState,
    openOnUnfold: Boolean,
    onOpenOnUnfoldChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Record when app opens", Modifier.weight(1f))
                Switch(checked = state.autoRecordOnOpen, onCheckedChange = controller::setAutoRecord)
            }
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp).horizontalScroll(rememberScrollState()),
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { controller.setThemeMode(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Copy transcript when done")
                    Text(
                        "When a recording finishes transcribing (after Stop, a split or an auto-split), it's copied to the clipboard.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = state.copyWhenDone, onCheckedChange = controller::setCopyWhenDone)
            }
            Spacer(Modifier.height(16.dp))
            MicrophoneSection(state, onSelect = controller::setInputDevice)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Capture background voices")
                    Text(
                        "For announcements, a TV or someone across the room. Records the whole room instead of " +
                            "just your voice, evens out the loudness, and uses Whisper turbo (~560 MB download, " +
                            "slower), which in testing made a quarter of the errors on distant, noisy speech. " +
                            "Also picks up nearby chatter, so leave it off for normal notes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = state.backgroundVoices, onCheckedChange = controller::setBackgroundVoices)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Translate to English automatically")
                    Text(
                        "Detects the spoken language (e.g. Mandarin, French, Spanish) and shows the English " +
                            "translation, plus pinyin for Mandarin. Runs on the phone; each language's " +
                            "translation pack (~30 MB) downloads once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = state.autoTranslate, onCheckedChange = controller::setAutoTranslate)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Conversation mode when unfolded")
                    Text(
                        "On a foldable phone, unfolding it opens conversation mode (live two-way " +
                            "translation in big text); folding it again goes back to notes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = openOnUnfold, onCheckedChange = onOpenOnUnfoldChange)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Transcription model", style = MaterialTheme.typography.titleSmall)
            EngineChoice.entries.forEach { choice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = choice == state.engineChoice,
                            role = Role.RadioButton,
                            onClick = { controller.setEngineChoice(choice) },
                        )
                        .padding(vertical = 6.dp),
                ) {
                    RadioButton(selected = choice == state.engineChoice, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(choice.displayName)
                        Text(
                            "${choice.languages} · ${choice.approximateSize}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "Use Auto: Western for English and European languages, Auto: Asian + English when " +
                    "you speak Mandarin, Cantonese, Japanese or Korean (and English). Both pick the " +
                    "model per sentence. " +
                    "Models run entirely on the device and are downloaded once on first use. " +
                    "Recording starts immediately; text catches up once the model is ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            when (val status = state.engineStatus) {
                EngineStatus.Idle -> Text("Model not loaded")
                is EngineStatus.Loading -> Column {
                    Text(if (status.downloading) "Downloading model…" else "Loading model…")
                    Spacer(Modifier.height(6.dp))
                    if (status.downloading) {
                        LinearProgressIndicator(progress = { status.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                EngineStatus.Ready -> Text("Model ready", color = MaterialTheme.colorScheme.primary)
                is EngineStatus.Failed -> Column {
                    Text("Failed to load model", color = MaterialTheme.colorScheme.error)
                    Text(status.message, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = controller::loadEngine) { Text("Retry") }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Clean up with a small LLM")
                    Text(
                        "Optionally run a tiny on-device model over the text before it's typed as a keyboard. " +
                            "Runs alongside the speech model; the picker recommends a size for this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = state.llmEnabled, onCheckedChange = controller::setLlmEnabled)
            }
            if (state.llmEnabled) {
                Text("Style", style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                ) {
                    TypeStyle.entries.forEach { style ->
                        FilterChip(
                            selected = state.typeStyle == style,
                            onClick = { controller.setTypeStyle(style) },
                            label = { Text(style.label) },
                        )
                    }
                }
                Text("Model", style = MaterialTheme.typography.titleSmall)
                LlmChoice.entries.forEach { choice ->
                    val advice = controller.llmAdvice(choice)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = choice == state.llmChoice,
                                role = Role.RadioButton,
                                onClick = { controller.setLlmChoice(choice) },
                            )
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(selected = choice == state.llmChoice, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(choice.displayName)
                                if (advice.verdict == Guidance.RECOMMENDED) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Recommended",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                "${choice.approximateSize} · ${advice.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                when (val status = state.llmStatus) {
                    LlmStatus.Idle -> Text("Model not loaded")
                    is LlmStatus.Loading -> Column {
                        Text(if (status.downloading) "Downloading model…" else "Loading model…")
                        Spacer(Modifier.height(6.dp))
                        if (status.downloading) {
                            LinearProgressIndicator(progress = { status.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    LlmStatus.Ready -> Text("Model ready", color = MaterialTheme.colorScheme.primary)
                    is LlmStatus.Failed -> Column {
                        Text("Failed to load model", color = MaterialTheme.colorScheme.error)
                        Text(status.message, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = controller::loadLlm) { Text("Retry") }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Lists connected microphones, shows which one is live, and lets you pick one. */
@Composable
private fun MicrophoneSection(state: RecordingController.UiState, onSelect: (String?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selected = state.selectedInputKey
    val selectedDevice = state.inputDevices.firstOrNull { it.key == selected }

    Text("Microphone", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Icon(
            if (state.isRecording) Icons.Default.Mic else Icons.Default.MicNone,
            contentDescription = null,
            tint = if (state.isRecording) Color(0xFFE53935) else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                state.isRecording -> "Recording from: ${state.activeInputName ?: "detecting…"}"
                selectedDevice != null -> "Will record from: ${selectedDevice.name}"
                selected != null -> "Chosen mic isn't connected — the phone's default will be used"
                else -> "The phone picks the microphone"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    MicOption(
        title = "Automatic",
        subtitle = "Let the phone choose (usually the newest headset, else the built-in mic)",
        selected = selected == null,
        onClick = { onSelect(null) },
    )
    state.inputDevices.forEach { device ->
        MicOption(
            title = device.name,
            subtitle = device.kind + if (device.isBluetooth) " · uses the headset's call audio" else "",
            selected = device.key == selected,
            onClick = { onSelect(device.key) },
        )
    }
    if (selected != null && selectedDevice == null) {
        MicOption(
            title = "Previously chosen mic",
            subtitle = "Not connected — reconnect it and VoicePad switches back automatically",
            selected = true,
            enabled = false,
            onClick = {},
        )
    }
    if (state.inputDevices.isEmpty()) {
        Text("No microphones detected.", color = colors.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MicOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
