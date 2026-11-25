package com.voicepad.app.ui

import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicepad.app.RecordingController
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.IconButton

@Composable
fun RecordBar(controller: RecordingController, state: RecordingController.UiState, compact: Boolean = false) {
    // Small windows: smaller button and less spacing.
    val button = if (compact) 64.dp else 80.dp
    val inner = if (compact) 52.dp else 64.dp
    var level by remember { mutableFloatStateOf(0f) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.isRecording) {
        while (state.isRecording) {
            level = controller.level
            now = System.currentTimeMillis()
            delay(50)
        }
        level = 0f
    }

    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = if (compact) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { controller.dismissError() },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
            ) {
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val started = state.startedAt
                    Text(
                        if (state.isRecording && started != null) {
                            DateUtils.formatElapsedTime(((now - started) / 1000).coerceAtLeast(0))
                        } else {
                            "--:--"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (state.isRecording) RecordingRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LevelMeter(if (state.isRecording) level else 0f, Modifier.padding(top = 4.dp))
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(button)
                        .semantics { contentDescription = if (state.isRecording) "Stop recording" else "Start recording" }
                        .clip(CircleShape)
                        .clickable { controller.toggle() },
                ) {
                    if (state.isRecording) {
                        Box(
                            Modifier
                                .size(button)
                                .scale(1f + level * 0.3f)
                                .background(RecordingRed.copy(alpha = 0.22f), CircleShape)
                        )
                        Box(Modifier.size(inner).background(RecordingRed, CircleShape))
                        Box(Modifier.size(inner * 0.38f).background(Color.White, RoundedCornerShape(5.dp)))
                    } else {
                        // Idle looks clearly different: an outlined ring with a red dot, like a camera.
                        Box(Modifier.size(inner).border(4.dp, MaterialTheme.colorScheme.outline, CircleShape))
                        Box(Modifier.size(inner * 0.7f).background(RecordingRed, CircleShape))
                    }
                }

                // Split: tap for a new note now; drag up or long-press for auto-split.
                SplitButton(controller, state)
            }
            val auto = state.autoSplit.badge
            Text(
                when {
                    state.isRecording && compact ->
                        "Recording" + (if (auto != null) " · $auto auto" else "") + (if (state.backgroundVoices) " · bg voices" else "")
                    state.isRecording ->
                        "Recording" + (if (state.partNumber > 1) " · part ${state.partNumber}" else "") +
                            (if (state.backgroundVoices) " · background voices" else "") +
                            (if (auto != null) " · auto-split $auto" else "") + " — tap ■ to stop"
                    compact -> "Not recording"
                    else -> "Not recording — tap ● to start"
                },
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = if (state.isRecording) RecordingRed else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (compact) 2.dp else 6.dp, start = 12.dp, end = 12.dp),
            )
        }
    }
}

val RecordingRed = Color(0xFFD32F2F)

/** A red dot that pulses, for "recording now". */
@Composable
fun PulsingDot(modifier: Modifier = Modifier, color: Color = RecordingRed, size: androidx.compose.ui.unit.Dp = 10.dp) {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    Box(modifier.size(size).background(color.copy(alpha = alpha), CircleShape))
}

@Composable
private fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(7) { i ->
            val active = level > i / 7f
            Box(
                Modifier
                    .width(4.dp)
                    .height((8 + minOf(i, 6 - i) * 6).dp)
                    .background(
                        if (active) Color(0xFFE53935) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        RoundedCornerShape(2.dp),
                    )
            )
        }
    }
}
