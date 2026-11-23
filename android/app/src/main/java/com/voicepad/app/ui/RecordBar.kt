package com.voicepad.app.ui

import android.text.format.DateUtils
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
import androidx.compose.ui.unit.dp
import com.voicepad.app.RecordingController
import kotlinx.coroutines.delay

@Composable
fun RecordBar(controller: RecordingController, state: RecordingController.UiState) {
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
            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 10.dp),
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
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.width(56.dp)) {
                    val started = state.startedAt
                    if (state.isRecording && started != null) {
                        Text(
                            DateUtils.formatElapsedTime(((now - started) / 1000).coerceAtLeast(0)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val finishing = !state.isRecording && state.recordingNoteId != null
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(76.dp)
                        .semantics { contentDescription = if (state.isRecording) "Stop recording" else "Start recording" }
                        .clip(CircleShape)
                        .clickable(enabled = !finishing) { controller.toggle() },
                ) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .scale(if (state.isRecording) 1f + level * 0.3f else 1f)
                            .background(Color.Red.copy(alpha = 0.18f), CircleShape)
                    )
                    Box(Modifier.size(62.dp).background(Color(0xFFE53935), CircleShape))
                    if (state.isRecording) {
                        Box(Modifier.size(22.dp).background(Color.White, RoundedCornerShape(5.dp)))
                    } else {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White)
                    }
                }

                LevelMeter(if (state.isRecording) level else 0f, Modifier.width(56.dp))
            }
        }
    }
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
