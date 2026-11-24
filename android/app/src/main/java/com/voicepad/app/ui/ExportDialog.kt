package com.voicepad.app.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicepad.app.RecordingController
import com.voicepad.app.data.NoteExport

/**
 * Export every note as a ZIP of open formats (JSON, Markdown, and the
 * recordings if wanted), saved wherever the user picks: the phone, Drive…
 */
@Composable
fun ExportDialog(controller: RecordingController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var includeAudio by remember { mutableStateOf(false) }
    var includeArchived by remember { mutableStateOf(true) }
    val count = remember(includeArchived) { controller.exportCandidates(includeArchived).size }
    val audioBytes = remember(includeArchived) { controller.exportAudioBytes(includeArchived) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Exporting…", Toast.LENGTH_SHORT).show()
            controller.exportNotes(uri, includeAudio, includeArchived) { message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export all notes") },
        text = {
            Column {
                Text(
                    "Saves a ZIP with notes.json (every note with its transcript history, translations and " +
                        "dates) and notes.md (readable text). Both are open formats any app can read.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.padding(top = 8.dp))
                ExportToggle("Include recordings", "WAV / M4A · ${Formatter.formatShortFileSize(context, audioBytes)}", includeAudio) { includeAudio = it }
                ExportToggle("Include archived notes", null, includeArchived) { includeArchived = it }
                Text(
                    "$count note${if (count == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = count > 0, onClick = { save.launch(NoteExport.fileName()) }) { Text("Choose where to save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExportToggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
