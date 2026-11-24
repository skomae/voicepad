package com.voicepad.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.voicepad.app.RecordingController
import com.voicepad.app.data.AutoSplit
import kotlin.math.floor

/**
 * The scissors: tap to split now; drag up (or long-press) to pick an
 * auto-split rule. While dragging, options stack above the button and the
 * one under your finger is selected when you let go; drag back down to the
 * button to cancel.
 */
@Composable
fun SplitButton(controller: RecordingController, state: RecordingController.UiState, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val options = AutoSplit.entries
    // Fit the whole list in about 60 % of the window, so it works in small pop-up windows too.
    val windowHeight = LocalConfiguration.current.screenHeightDp
    val step = (windowHeight * 0.6f / (options.size + 2)).coerceIn(30f, 44f).dp
    val stepPx = with(LocalDensity.current) { step.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    /** Option under the finger while dragging, or -1 for "cancel". */
    var hover by remember { mutableIntStateOf(-1) }

    Box(modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics {
                    contentDescription = "Split: tap to start a new note now; drag up or long-press to set auto-split " +
                        "(currently ${state.autoSplit.label})"
                }
                .pointerInput(state.isRecording) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            val rise = down.position.y - change.position.y
                            if (!change.pressed) {
                                when {
                                    moved -> {
                                        if (hover >= 0) {
                                            controller.setAutoSplit(options[hover])
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        }
                                        dragging = false
                                    }
                                    change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis -> {
                                        menuOpen = true
                                    }
                                    state.isRecording -> {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        controller.split()
                                    }
                                }
                                break
                            }
                            if (!moved && rise > viewConfiguration.touchSlop) {
                                moved = true
                                dragging = true
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            if (moved) {
                                // Option i sits (i + 1) steps above the button.
                                val next = floor(rise / stepPx - 0.5f).toInt().coerceIn(-1, options.lastIndex)
                                if (next != hover) {
                                    hover = next
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                change.consume()
                            }
                        }
                    }
                }
                .padding(vertical = 6.dp),
        ) {
            val tint = if (state.isRecording) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            Box {
                Icon(Icons.Default.ContentCut, contentDescription = null, tint = tint)
                state.autoSplit.badge?.let { badge ->
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(start = 18.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                            .padding(horizontal = 4.dp),
                    )
                }
            }
            Text(
                if (state.autoSplit == AutoSplit.OFF) "Split" else "Auto",
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }

        if (dragging || menuOpen) {
            val lift = with(LocalDensity.current) { (step * (options.size + 0.5f)).roundToPx() }
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, -lift),
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = menuOpen),
            ) {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 8.dp) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "Auto-split",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                        // Top of the list is furthest from the button: highest index first.
                        options.indices.reversed().forEach { i ->
                            val option = options[i]
                            val highlighted = if (dragging) i == hover else option == state.autoSplit
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (highlighted) MaterialTheme.colorScheme.primaryContainer
                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                    .clickable(enabled = menuOpen) {
                                        controller.setAutoSplit(option)
                                        menuOpen = false
                                    }
                                    .padding(horizontal = 10.dp)
                                    .size(width = 210.dp, height = step - 4.dp),
                            ) {
                                if (option == state.autoSplit) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                } else {
                                    Box(Modifier.size(18.dp))
                                }
                                Text(
                                    option.label,
                                    fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            }
                        }
                        if (dragging) {
                            Text(
                                if (hover < 0) "Release here to cancel" else "Release to choose",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
