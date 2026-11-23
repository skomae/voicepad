package com.voicepad.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.voicepad.app.RecordingController
import com.voicepad.app.RecordingController.EngineStatus
import com.voicepad.app.data.Note
import com.voicepad.app.data.RevisionSource

private val BubbleShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)

/** A finished note: transcript bubble with copy / edit / history actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteBubble(
    note: Note,
    isBusy: Boolean,
    canRetranscribe: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onRetranscribe: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(BubbleStartInset, horizontalAlignment = Alignment.End) {
        Box {
            Box(
                Modifier
                    .background(colors.primary, BubbleShape)
                    .combinedClickable(onClick = onEdit, onLongClick = { menuOpen = true })
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (note.currentText.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isBusy) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = colors.onPrimary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (isBusy) "Transcribing…" else "No speech detected",
                            color = colors.onPrimary.copy(alpha = 0.8f),
                            fontStyle = FontStyle.Italic,
                        )
                    }
                } else {
                    Text(note.currentText, color = colors.onPrimary)
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MenuItem("Copy", Icons.Default.ContentCopy) { menuOpen = false; onCopy() }
                MenuItem("Edit", Icons.Default.Edit) { menuOpen = false; onEdit() }
                MenuItem("Revision history", Icons.Default.History) { menuOpen = false; onHistory() }
                MenuItem("Re-transcribe", Icons.Default.Refresh, enabled = canRetranscribe && !isBusy) {
                    menuOpen = false; onRetranscribe()
                }
                MenuItem("Delete", Icons.Default.Delete, tint = colors.error) { menuOpen = false; onDelete() }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(metadata(note), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            SmallAction(Icons.Default.ContentCopy, "Copy", onCopy)
            SmallAction(Icons.Default.Edit, "Edit", onEdit)
            SmallAction(Icons.Default.History, "Revision history", onHistory)
            if (note.revisions.size > 1) {
                Text("${note.revisions.size}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, color = if (tint != Color.Unspecified) tint else Color.Unspecified) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = if (tint != Color.Unspecified) tint else MaterialTheme.colorScheme.onSurface) },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun metadata(note: Note): String {
    val parts = mutableListOf(
        DateUtils.getRelativeTimeSpanString(note.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    )
    if (note.durationSeconds > 0) parts += DateUtils.formatElapsedTime(note.durationSeconds.toLong())
    note.revisions.lastOrNull()?.source?.takeIf { it != RevisionSource.TRANSCRIPTION }?.let { parts += it.label.lowercase() }
    return parts.joinToString(" · ")
}

/** The bubble for the recording in progress; text streams in live. */
@Composable
fun LiveBubble(state: RecordingController.UiState) {
    val colors = MaterialTheme.colorScheme
    val text = state.liveText.ifEmpty {
        when (val s = state.engineStatus) {
            EngineStatus.Ready -> "Listening…"
            is EngineStatus.Loading ->
                if (s.downloading) "Recording… downloading model ${(s.progress * 100).toInt()}%" else "Recording… loading model"
            is EngineStatus.Failed -> "Recording… (model unavailable)"
            EngineStatus.Idle -> "Recording…"
        }
    }
    Column(BubbleStartInset, horizontalAlignment = Alignment.End) {
        Box(
            Modifier
                .background(colors.surfaceVariant, BubbleShape)
                .border(1.5.dp, colors.primary.copy(alpha = 0.5f), BubbleShape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text,
                color = if (state.liveText.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
                fontStyle = if (state.liveText.isEmpty()) FontStyle.Italic else FontStyle.Normal,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            if (state.isRecording) {
                Box(Modifier.size(7.dp).background(Color.Red, CircleShape))
                Text("Live", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            } else {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Text("Finishing transcript…", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
