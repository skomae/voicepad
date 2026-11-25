package com.voicepad.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicepad.app.RecordingController
import com.voicepad.app.RecordingController.LlmStatus
import com.voicepad.app.llm.LlmPrompt
import com.voicepad.app.transmit.AutoSend
import com.voicepad.app.transmit.SinkStatus

/**
 * Everything about keyboard mode in one sheet, opened by long-pressing the
 * keyboard icon (or "Keyboard settings…" in its pull-down list): connection, the LLM rewrite
 * toggle, and the prompt it follows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardMenu(
    controller: RecordingController,
    state: RecordingController.UiState,
    onTurnOn: () -> Unit,
    onPickHost: () -> Unit,
    onTypeLatest: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Keyboard", style = MaterialTheme.typography.titleLarge)
            Text(
                when (state.transmitStatus) {
                    SinkStatus.CONNECTED -> if (state.isTyping) "Typing on ${state.transmitHost ?: "the paired device"}…"
                        else "Connected to ${state.transmitHost ?: "a device"}"
                    SinkStatus.ADVERTISING -> "On, waiting for a device to connect"
                    SinkStatus.REGISTERING -> "Turning on…"
                    SinkStatus.FAILED -> state.transmitError ?: "Couldn't turn on"
                    SinkStatus.UNAVAILABLE -> "This phone can't act as a Bluetooth keyboard"
                    SinkStatus.IDLE -> "Off"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.transmitStatus) {
                    SinkStatus.CONNECTED -> {
                        if (state.isTyping) {
                            Button(onClick = controller::stopTyping) { Text("Stop typing") }
                        } else {
                            Button(onClick = { onTypeLatest(); onDismiss() }) { Text("Type latest note") }
                        }
                        OutlinedButton(onClick = controller::stopKeyboard) { Text("Disconnect") }
                    }
                    SinkStatus.ADVERTISING -> {
                        Button(onClick = { onDismiss(); onPickHost() }) { Text("Connect to a device") }
                        OutlinedButton(onClick = controller::stopKeyboard) { Text("Turn off") }
                    }
                    SinkStatus.REGISTERING -> OutlinedButton(onClick = controller::stopKeyboard) { Text("Cancel") }
                    SinkStatus.IDLE, SinkStatus.FAILED -> Button(onClick = onTurnOn) { Text("Turn on") }
                    SinkStatus.UNAVAILABLE -> {}
                }
            }
            Text(
                "Double-tap a note (or copy it) to type it while connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep the on-device LLM loaded")
                    Text(
                        when (val llm = state.llmStatus) {
                            LlmStatus.Idle -> if (state.llmEnabled) "Model not loaded" else "Off: loads when a rewrite is asked for"
                            is LlmStatus.Loading ->
                                if (llm.downloading) "Downloading ${state.llmChoice.shortName}… ${(llm.progress * 100).toInt()}%"
                                else "Loading ${state.llmChoice.shortName}…"
                            LlmStatus.Ready -> "${state.llmChoice.shortName} ready"
                            is LlmStatus.Failed -> "Failed: ${llm.message}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.llmStatus is LlmStatus.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = state.llmEnabled, onCheckedChange = controller::setLlmEnabled)
            }
            if (state.llmStatus is LlmStatus.Failed) {
                TextButton(onClick = { controller.loadLlm() }) { Text("Retry") }
            }

            Spacer(Modifier.height(16.dp))
            Text("Auto send", style = MaterialTheme.typography.titleSmall)
            AutoSendChooser(state.autoSend, onSelect = controller::setAutoSend)

            Spacer(Modifier.height(16.dp))
            Text("Prompts", style = MaterialTheme.typography.titleSmall)
            PromptSlots(controller, state)
        }
    }
}

/**
 * The LLM prompt: two presets, an editable field for your own, and the
 * prompts you've used before. Shared by the keyboard menu and Settings.
 */
@Composable
fun PromptChooser(
    current: String,
    recent: List<String>,
    onUse: (String) -> Unit,
    onForget: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
    ) {
        LlmPrompt.PRESETS.forEach { preset ->
            FilterChip(
                selected = current == preset.instruction,
                onClick = { onUse(preset.instruction) },
                label = { Text(preset.label) },
            )
        }
    }

    // Reset the draft whenever the prompt in use changes from elsewhere.
    var draft by remember(current) { mutableStateOf(current) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(if (LlmPrompt.presetFor(current) != null) "Prompt (edit to make your own)" else "Your prompt") },
        minLines = 2,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    val changed = draft.trim() != current && draft.isNotBlank()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (changed) TextButton(onClick = { draft = current }) { Text("Revert") }
        TextButton(onClick = { onUse(draft) }, enabled = changed) { Text("Use this prompt") }
    }

    if (recent.isNotEmpty()) {
        Text(
            "Recent prompts",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        recent.forEach { prompt ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUse(prompt) }
                    .padding(vertical = 4.dp),
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = if (prompt == current) "In use" else null,
                    tint = if (prompt == current) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    prompt,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onForget(prompt) }) {
                    Icon(Icons.Default.Close, contentDescription = "Forget this prompt")
                }
            }
        }
    }
}

/**
 * Types text on the connected Bluetooth keyboard host, or null when none is
 * connected. Provided at the top of the UI so any text (notes, conversation
 * turns) can offer double-tap-to-type without threading callbacks through.
 */
val LocalTypeOnKeyboard = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Double-tap types [text] on the connected keyboard host; no-op when none is. */
@Composable
fun Modifier.doubleTapToType(text: String): Modifier {
    val type = LocalTypeOnKeyboard.current ?: return this
    if (text.isBlank()) return this
    return this.pointerInput(text, type) { detectTapGestures(onDoubleTap = { type(text) }) }
}

/** No auto send / on copy / after the LLM prompt. */
@Composable
fun AutoSendChooser(current: AutoSend, onSelect: (AutoSend) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
    ) {
        AutoSend.entries.forEach { mode ->
            FilterChip(selected = current == mode, onClick = { onSelect(mode) }, label = { Text(mode.label) })
        }
    }
    Text(
        when (current) {
            AutoSend.OFF -> "Nothing is typed unless you send it."
            AutoSend.ON_COPY -> "Copying a note, or a recording finishing, also types it on the computer."
            AutoSend.AFTER_LLM -> "Copying a note, or a recording finishing, types prompt 1's rewrite of it."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Short name for a prompt: the preset's label, or the start of your own. */
fun promptLabel(prompt: String): String =
    LlmPrompt.presetFor(prompt)?.label ?: prompt.trim().let { if (it.length > 18) it.take(17).trimEnd() + "…" else it }

/** The two active prompts (the laptop pad's two rewrite buttons); pick one to change it. */
@Composable
fun PromptSlots(controller: RecordingController, state: RecordingController.UiState) {
    var slot by remember { mutableStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        listOf(state.typePrompt, state.typePrompt2).forEachIndexed { i, prompt ->
            FilterChip(
                selected = slot == i,
                onClick = { slot = i },
                label = { Text("${i + 1} · ${promptLabel(prompt)}") },
            )
        }
    }
    PromptChooser(
        current = if (slot == 0) state.typePrompt else state.typePrompt2,
        recent = state.recentPrompts,
        onUse = { controller.setTypePrompt(it, slot) },
        onForget = controller::forgetPrompt,
    )
}
