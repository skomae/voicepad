package com.voicepad.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicepad.app.RecordingController
import com.voicepad.app.conversation.CaptionLines
import com.voicepad.app.data.Note
import com.voicepad.app.transcription.LanguageTags
import kotlinx.coroutines.delay

/**
 * Caption view: what's being said, as a teleprompter. Like conversation mode's
 * stage, without translation. The newest paragraph is big at the bottom, its
 * last few words brightest while they arrive; earlier paragraphs and the last
 * few notes sit above it, smaller and dimmer (scroll up to read back). With
 * *Separate speakers* on, each paragraph carries its speaker's colour and name.
 */
@Composable
fun CaptionStage(
    controller: RecordingController,
    state: RecordingController.UiState,
    /** Notes finished before this one, oldest first: shown above, for context. */
    earlier: List<Note>,
    modifier: Modifier = Modifier,
) {
    val textSize = state.captionTextSize
    val lines = remember(state.liveText) { CaptionLines.parse(state.liveText) }
    val hero = lines.lastOrNull()
    // Earlier paragraphs of this note, then earlier notes' paragraphs, newest first (the list is reversed).
    val history = remember(lines, earlier) {
        val past = earlier.flatMap { CaptionLines.parse(it.currentText) } + lines.dropLast(1)
        past.asReversed().take(40)
    }
    // Red while recording, blue in keyboard mode, like the rest of the recording screen.
    val accent = recordingColor(state)
    val heroColor = hero?.speaker?.let { SpeakerColors[it % SpeakerColors.size] } ?: accent

    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.isRecording) {
        while (state.isRecording) {
            level = controller.level
            delay(60)
        }
        level = 0f
    }

    val listState = rememberLazyListState()
    // Stay on the newest words unless the reader scrolled back.
    LaunchedEffect(state.liveText, history.size) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }

    Box(modifier) {
        // A glow from the top edge that follows the voice.
        Canvas(Modifier.fillMaxSize()) {
            val alpha = if (state.isRecording) 0.08f + level.coerceIn(0f, 1f) * 0.30f else 0.04f
            drawRect(
                Brush.radialGradient(
                    listOf(heroColor.copy(alpha = alpha), Color.Transparent),
                    center = Offset(size.width / 2, 0f),
                    radius = size.width * 0.9f,
                ),
            )
        }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp)) {
                Text(
                    when {
                        state.isRecording -> "Live captions"
                        else -> "Finishing…"
                    } + (if (state.partNumber > 1) " · part ${state.partNumber}" else ""),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
                if (state.isRecording) {
                    Spacer(Modifier.width(10.dp))
                    LevelBars(accent) { level }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (hero == null) {
                    Text(
                        if (state.isRecording) "Listening…" else "",
                        fontSize = textSize.sp,
                        lineHeight = (textSize * 1.2f).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (hero != null) {
                        item(key = "hero") { HeroCaption(hero, textSize, heroColor, arriving = state.isRecording) }
                    }
                    itemsIndexed(history) { _, line -> PastCaption(line, textSize) }
                }
            }
        }
    }
}

/** The paragraph being spoken: large, with the words still arriving at full brightness. */
@Composable
private fun HeroCaption(line: CaptionLines.Line, textSize: Int, color: Color, arriving: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    val text = line.text
    val settled = if (arriving) CaptionLines.settledLength(text, dense = LanguageTags.containsHan(text)) else 0
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(12.dp))
        Column {
            line.label?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = color)
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = ink.copy(alpha = 0.7f))) { append(text.take(settled)) }
                    append(text.drop(settled))
                },
                fontSize = textSize.sp,
                lineHeight = (textSize * 1.22f).sp,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                // TalkBack and braille displays follow the captions as they change.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** An earlier paragraph: smaller and dimmer, still in its speaker's colour. */
@Composable
private fun PastCaption(line: CaptionLines.Line, textSize: Int) {
    val size = (textSize * 0.55f).coerceAtLeast(16f)
    val color = line.speaker?.let { SpeakerColors[it % SpeakerColors.size] } ?: MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column {
            line.label?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.8f))
            }
            Text(
                line.text,
                fontSize = size.sp,
                lineHeight = (size * 1.25f).sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Five small bars that move with the microphone level: a voice is being heard. */
@Composable
private fun LevelBars(color: Color, level: () -> Float) {
    val shape = remember { floatArrayOf(0.55f, 1f, 0.7f, 0.9f, 0.5f) }
    Canvas(Modifier.size(width = 26.dp, height = 14.dp)) {
        val l = level().coerceIn(0f, 1f)
        val w = size.width / 9
        shape.forEachIndexed { i, s ->
            val h = size.height * (0.18f + 0.82f * l * s)
            drawRoundRect(color, Offset(i * 2 * w, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}
