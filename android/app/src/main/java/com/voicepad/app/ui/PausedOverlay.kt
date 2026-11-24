package com.voicepad.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicepad.app.RecordingController

/** Paused is amber everywhere: nothing like recording red or the app's own colours. */
val PausedAmber = Color(0xFFFFB300)
private val OnPausedAmber = Color(0xFF2B1D00)

/**
 * Covers the screen (or [modifier]'s area) while a recording is paused, so
 * it can't be mistaken for recording: amber, a huge PAUSED, the frozen time,
 * and big Resume / Stop buttons. Swallows touches to what's underneath.
 */
@Composable
fun PausedOverlay(
    controller: RecordingController,
    state: RecordingController.UiState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val pausedAt = state.pausedAt ?: return
    val elapsed = state.startedAt?.let { DateUtils.formatElapsedTime(((pausedAt - it) / 1000).coerceAtLeast(0)) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(PausedAmber)
            // Block taps on the transcript underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(
                Icons.Default.Pause,
                contentDescription = null,
                tint = OnPausedAmber,
                modifier = Modifier.size(if (compact) 64.dp else 120.dp),
            )
            Text(
                "PAUSED",
                color = OnPausedAmber,
                fontSize = if (compact) 48.sp else 72.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
            )
            Text(
                buildString {
                    append("Not recording")
                    if (elapsed != null) append(" · stopped at $elapsed")
                    if (state.partNumber > 1) append(" · part ${state.partNumber}")
                },
                color = OnPausedAmber,
                fontSize = if (compact) 16.sp else 20.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(if (compact) 16.dp else 32.dp))
            Button(
                onClick = controller::resume,
                colors = ButtonDefaults.buttonColors(containerColor = OnPausedAmber, contentColor = PausedAmber),
                modifier = Modifier.fillMaxWidth(0.8f).height(if (compact) 56.dp else 72.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.size(8.dp))
                Text("Resume", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = controller::stop,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = OnPausedAmber),
                border = BorderStroke(2.dp, OnPausedAmber),
                modifier = Modifier.fillMaxWidth(0.8f).height(52.dp),
            ) {
                Text("Stop recording", fontSize = 18.sp)
            }
        }
    }
}
