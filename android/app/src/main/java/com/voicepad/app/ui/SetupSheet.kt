package com.voicepad.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicepad.app.RecordingController
import com.voicepad.app.transcription.EngineChoice

/**
 * First-run setup: before the app starts recording (and quietly downloading
 * its active model), offer to pre-download the speech models the user wants,
 * so recording works without a connection, and to keep a copy of the models
 * in a folder outside the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupSheet(
    controller: RecordingController,
    state: RecordingController.UiState,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(EngineChoice.entries.toSet()) }
    val preload = state.preload
    val running = preload is RecordingController.PreloadStatus.Running
    val missing = selected.filter { it !in state.installedEngines }
    val missingMb = missing.sumOf { it.sizeMb }
    val sizeLabel = if (missingMb >= 1000) {
        val tenths = (missingMb / 100.0).toInt()
        "${tenths / 10}.${tenths % 10} GB"
    } else {
        "$missingMb MB"
    }

    ModalBottomSheet(
        onDismissRequest = { if (!running) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Get ready to use VoicePad offline", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "VoicePad downloads each speech model the first time it's used. " +
                    "To record without a connection — on a plane, travelling, anywhere offline — " +
                    "pre-download the models you want now. Models run entirely on the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speech models", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = EngineChoice.entries.toSet() }, enabled = !running) { Text("Select all") }
                TextButton(onClick = { selected = emptySet() }, enabled = !running) { Text("Clear") }
            }

            EngineChoice.entries.forEach { choice ->
                val installed = choice in state.installedEngines
                val checked = choice in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = checked,
                            enabled = !running,
                            role = Role.Checkbox,
                            onClick = { selected = if (checked) selected - choice else selected + choice },
                        )
                        .padding(vertical = 6.dp),
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = !running)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(choice.displayName, fontWeight = FontWeight.SemiBold)
                        Text(choice.description, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${choice.languages} · ${choice.approximateSize}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (installed) {
                            Text(
                                "Already on this phone",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            ModelFolderSection()

            Spacer(Modifier.height(12.dp))
            when (preload) {
                is RecordingController.PreloadStatus.Running -> {
                    Text(
                        "Downloading ${preload.label}…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { preload.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
                RecordingController.PreloadStatus.Idle -> {}
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !running) { Text(if (missing.isEmpty()) "Done" else "Not now") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { controller.preloadModels(selected) },
                    enabled = !running && missing.isNotEmpty(),
                ) {
                    Text(
                        if (missing.isEmpty()) {
                            "All selected models are already downloaded"
                        } else {
                            "Pre-download ${missing.size} model${if (missing.size == 1) "" else "s"} · about $sizeLabel"
                        }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
