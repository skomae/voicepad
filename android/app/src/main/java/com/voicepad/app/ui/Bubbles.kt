package com.voicepad.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicepad.app.RecordingController
import com.voicepad.app.RecordingController.EngineStatus
import com.voicepad.app.data.Note
import com.voicepad.app.data.NoteSearch
import com.voicepad.app.data.RevisionSource
import com.voicepad.app.data.TranscriptionStatus
import com.voicepad.app.data.NoteTranslation
import com.voicepad.app.data.TranslationStatus
import com.voicepad.app.transcription.LanguageTags

private val BubbleShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)

/**
 * A saved note. Shows its transcript, or where its audio is in the
 * transcription pipeline, with copy / edit / history / play / share actions
 * and a Retry button when a model run failed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteBubble(
    note: Note,
    engineStatus: EngineStatus,
    isPlaying: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onRetranscribe: () -> Unit,
    onTogglePin: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    /** Search terms to highlight (already folded by NoteSearch). */
    highlightTerms: List<String> = emptyList(),
    /** The search matched an earlier version, not the current text. */
    matchedInEarlierRevision: Boolean = false,
    onTranslate: () -> Unit = {},
    onCopyText: (String) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val failed = note.status == TranscriptionStatus.FAILED
    val working = note.status == TranscriptionStatus.IN_PROGRESS
    val hasText = note.currentText.isNotEmpty()
    // Text in a failed or waiting note is only what was recovered so far.
    val bodyText = if (hasText) note.currentText else note.partialText.orEmpty()
    val solid = hasText || note.status == TranscriptionStatus.DONE

    Column(BubbleStartInset, horizontalAlignment = Alignment.End) {
        Box {
            Column(
                Modifier
                    .background(if (solid) colors.primary else colors.surfaceVariant, BubbleShape)
                    .then(if (failed) Modifier.border(1.5.dp, colors.error, BubbleShape) else Modifier)
                    .combinedClickable(onClick = onEdit, onLongClick = { menuOpen = true })
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                val textColor = if (solid) colors.onPrimary else colors.onSurfaceVariant
                val translation = note.currentTranslation.takeIf { hasText }
                if (bodyText.isNotEmpty()) {
                    TextWithTranslation(bodyText, translation, textColor, highlightTerms, onTranslate)
                } else if (note.status == TranscriptionStatus.DONE) {
                    Text("No speech detected", color = textColor.copy(alpha = 0.8f), fontStyle = FontStyle.Italic)
                }
                if (!hasText || working) {
                    StatusLine(note, engineStatus, textColor, onRetranscribe)
                }
                if (matchedInEarlierRevision) {
                    Text(
                        "Matched an earlier version · open history",
                        color = textColor.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(top = 4.dp).clickable(onClick = onHistory),
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MenuItem("Copy", Icons.Default.ContentCopy) { menuOpen = false; onCopy() }
                val translated = note.currentTranslation?.takeIf { it.status == TranslationStatus.DONE }
                if (translated != null) {
                    MenuItem("Copy translation", Icons.Default.Translate) {
                        menuOpen = false
                        onCopyText(listOfNotNull(translated.romanization, translated.english).joinToString("\n"))
                    }
                } else if (hasText) {
                    MenuItem("Translate to English", Icons.Default.Translate) { menuOpen = false; onTranslate() }
                }
                MenuItem(if (note.pinned) "Unpin" else "Pin", Icons.Default.PushPin) { menuOpen = false; onTogglePin() }
                MenuItem("Edit", Icons.Default.Edit) { menuOpen = false; onEdit() }
                MenuItem("Revision history", Icons.Default.History) { menuOpen = false; onHistory() }
                MenuItem(if (isPlaying) "Stop playback" else "Play recording", if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow) {
                    menuOpen = false; onPlay()
                }
                MenuItem("Share recording", Icons.Default.Share) { menuOpen = false; onShare() }
                MenuItem(
                    if (hasText) "Re-transcribe" else "Retry transcription",
                    Icons.Default.Refresh,
                    enabled = engineStatus == EngineStatus.Ready && !working,
                ) { menuOpen = false; onRetranscribe() }
                MenuItem("Delete", Icons.Default.Delete, tint = colors.error) { menuOpen = false; onDelete() }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(metadata(note), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (note.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (note.pinned) "Unpin" else "Pin",
                    modifier = Modifier.size(16.dp),
                    tint = if (note.pinned) colors.primary else colors.onSurfaceVariant,
                )
            }
            SmallAction(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, if (isPlaying) "Stop" else "Play", onPlay)
            SmallAction(Icons.Default.ContentCopy, "Copy", onCopy)
            SmallAction(Icons.Default.Edit, "Edit", onEdit)
            SmallAction(Icons.Default.History, "Revision history", onHistory)
            if (note.revisions.size > 1) {
                Text("${note.revisions.size}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * The transcript and, for speech that wasn't English, its romanisation
 * (pinyin for Mandarin) and English translation. Side by side when there's
 * room (landscape, tablets), stacked on a phone held upright.
 */
@Composable
internal fun TextWithTranslation(
    text: String,
    translation: NoteTranslation?,
    color: Color,
    highlightTerms: List<String>,
    onRetry: () -> Unit,
) {
    if (translation == null || translation.status == TranslationStatus.NOT_NEEDED) {
        Text(highlighted(text, highlightTerms), color = color)
        return
    }
    val original: @Composable () -> Unit = {
        Column {
            translation.language?.let { LanguageLabel(LanguageTags.displayName(it), color) }
            Text(highlighted(text, highlightTerms), color = color)
            translation.romanization?.let {
                Text(
                    highlighted(it, highlightTerms),
                    color = color.copy(alpha = 0.85f),
                    fontStyle = FontStyle.Italic,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    val english: @Composable () -> Unit = {
        Column {
            LanguageLabel("English", color)
            when (translation.status) {
                TranslationStatus.DONE -> Text(highlighted(translation.english.orEmpty(), highlightTerms), color = color)
                TranslationStatus.PENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Translating…", color = color, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
                }
                TranslationStatus.FAILED -> Column {
                    Text(
                        translation.error ?: "Translation failed.",
                        color = color.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp), tint = color)
                        Spacer(Modifier.width(4.dp))
                        Text("Retry translation", color = color)
                    }
                }
                TranslationStatus.NOT_NEEDED -> {}
            }
        }
    }
    BoxWithConstraints {
        if (maxWidth >= 480.dp) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.weight(1f)) { original() }
                VerticalDivider(Modifier.padding(horizontal = 12.dp), color = color.copy(alpha = 0.3f))
                Box(Modifier.weight(1f)) { english() }
            }
        } else {
            Column {
                original()
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = color.copy(alpha = 0.3f))
                english()
            }
        }
    }
}

@Composable
private fun LanguageLabel(name: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Icon(Icons.Default.Translate, null, Modifier.size(12.dp), tint = color.copy(alpha = 0.7f))
        Spacer(Modifier.width(4.dp))
        Text(name.uppercase(), color = color.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
    }
}

/** [text] with every search-term match emphasised. */
private fun highlighted(text: String, terms: List<String>): AnnotatedString {
    val ranges = NoteSearch.matchRanges(text, terms)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(
        background = Color(0xFFFFEB3B).copy(alpha = 0.55f),
        color = Color.Black,
        fontWeight = FontWeight.SemiBold,
    )
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}

/** "Waiting for model", "Transcribing…" or "Failed · Retry" under a bubble's text. */
@Composable
private fun StatusLine(note: Note, engineStatus: EngineStatus, color: Color, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        when (note.status) {
            TranscriptionStatus.IN_PROGRESS -> {
                CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("Transcribing…", color = color, style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic)
            }
            TranscriptionStatus.PENDING, TranscriptionStatus.RECORDING -> {
                Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = color)
                Spacer(Modifier.width(6.dp))
                val why = when (engineStatus) {
                    is EngineStatus.Loading -> if (engineStatus.downloading) "model downloading ${(engineStatus.progress * 100).toInt()}%" else "model loading"
                    is EngineStatus.Failed -> "model failed to load"
                    else -> "queued"
                }
                Text("Audio saved · waiting ($why)", color = color, style = MaterialTheme.typography.labelMedium)
            }
            TranscriptionStatus.FAILED -> {
                Column {
                    Text(
                        "Transcription failed · audio saved",
                        color = colors.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    note.lastError?.let {
                        Text(it, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                    TextButton(
                        onClick = onRetry,
                        enabled = engineStatus == EngineStatus.Ready,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (engineStatus == EngineStatus.Ready) "Retry" else "Retry when model is ready")
                    }
                }
            }
            TranscriptionStatus.DONE -> {}
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
                .border(1.5.dp, RecordingRed.copy(alpha = 0.6f), BubbleShape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (state.liveText.isEmpty()) {
                Text(text, color = colors.onSurfaceVariant, fontStyle = FontStyle.Italic)
            } else {
                // Original, pinyin and English update together as you speak.
                TextWithTranslation(state.liveText, state.liveTranslation, colors.onSurface, emptyList(), onRetry = {})
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            if (state.isRecording) {
                PulsingDot(size = 8.dp)
                Text(
                    "Live" + if (state.partNumber > 1) " · part ${state.partNumber}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = RecordingRed,
                )
            } else {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Text("Finishing transcript…", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
