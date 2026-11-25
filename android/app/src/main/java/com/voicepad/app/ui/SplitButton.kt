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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.voicepad.app.RecordingController
import com.voicepad.app.data.AutoSplit
import kotlin.math.floor

/**
 * A button with a menu you drag up into: tap for [onTap]; drag up (or
 * long-press) to pick one of [options]. While dragging, the options stack
 * above the button and the one under your finger is chosen when you let go;
 * drag back down to the button to cancel. Long-press opens the same list to
 * tap (and for TalkBack).
 */
@Composable
fun <T> DragUpMenu(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    /** Null: a tap does nothing (the menu still works). */
    onTap: (() -> Unit)?,
    description: String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    // Fit the whole list in about 60 % of the window, so it works in small pop-up windows too.
    // The window's own size, not the screen's: the menu also runs in freeform windows.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }
    val step = (windowHeight * 0.6f / (options.size + 2)).coerceIn(30f, 44f).dp
    val stepPx = with(LocalDensity.current) { step.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    /** Option under the finger while dragging, or -1 for "cancel". */
    var hover by remember { mutableIntStateOf(-1) }
    val currentOptions by rememberUpdatedState(options)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSelect by rememberUpdatedState(onSelect)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
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
                                        currentOnSelect(currentOptions[hover])
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    }
                                    dragging = false
                                }
                                change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis -> {
                                    menuOpen = true
                                }
                                else -> currentOnTap?.let {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    it()
                                }
                            }
                            break
                        }
                        if (!moved && rise > viewConfiguration.touchSlop) {
                            moved = true
                            dragging = true
                            hover = -1
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        if (moved) {
                            // Option i sits (i + 1) steps above the button.
                            val next = floor(rise / stepPx - 0.5f).toInt().coerceIn(-1, currentOptions.lastIndex)
                            if (next != hover) {
                                hover = next
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            change.consume()
                        }
                    }
                }
            },
    ) {
        content()

        if (dragging || menuOpen) {
            val lift = with(LocalDensity.current) { (step * (options.size + 0.5f)).roundToPx() }
            Popup(
                popupPositionProvider = remember(lift) { AboveAnchor(lift) },
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = menuOpen),
            ) {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 8.dp) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                        // Top of the list is furthest from the button: highest index first.
                        options.indices.reversed().forEach { i ->
                            val option = options[i]
                            val highlighted = if (dragging) i == hover else option == selected
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable(enabled = menuOpen) {
                                        onSelect(option)
                                        menuOpen = false
                                    }
                                    .padding(horizontal = 10.dp)
                                    .size(width = 220.dp, height = step - 4.dp),
                            ) {
                                if (option == selected) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                } else {
                                    Box(Modifier.size(18.dp))
                                }
                                icon?.let { Icon(it(option), contentDescription = null, modifier = Modifier.size(20.dp)) }
                                Text(
                                    label(option),
                                    fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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

/** Centred over the anchor and [lift] px above its top, kept inside the window. */
private class AboveAnchor(private val lift: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - lift).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

/** The auto-split choices, as a drag-up menu around [content]. */
@Composable
private fun AutoSplitMenu(
    controller: RecordingController,
    state: RecordingController.UiState,
    modifier: Modifier,
    content: @Composable () -> Unit,
) = DragUpMenu(
    title = "Auto-split",
    options = AutoSplit.entries,
    selected = state.autoSplit,
    label = { it.label },
    onSelect = controller::setAutoSplit,
    onTap = if (state.isRecording) {
        { controller.split() }
    } else {
        null
    },
    description = "Split: tap to start a new note now; drag up or long-press to set auto-split " +
        "(currently ${state.autoSplit.label})",
    modifier = modifier,
    content = content,
)

/** The scissors with the auto-split badge, e.g. "3s". */
@Composable
private fun SplitIcon(state: RecordingController.UiState, tint: Color, size: androidx.compose.ui.unit.Dp = 24.dp) {
    Box {
        Icon(Icons.Default.ContentCut, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        state.autoSplit.badge?.let { badge ->
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(start = size * 0.75f)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * The scissors: tap to split now; drag up (or long-press) to pick an
 * auto-split rule.
 */
@Composable
fun SplitButton(controller: RecordingController, state: RecordingController.UiState, modifier: Modifier = Modifier) {
    AutoSplitMenu(controller, state, modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).padding(vertical = 6.dp),
        ) {
            val tint = if (state.isRecording) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            SplitIcon(state, tint)
            Text(
                if (state.autoSplit == AutoSplit.OFF) "Split" else "Auto",
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

/**
 * Laptop mode's big Split pad: the same as [SplitButton] (tap to split, drag
 * up for auto-split), styled like the pad's other buttons.
 */
@Composable
fun RowScope.SplitPad(controller: RecordingController, state: RecordingController.UiState, weight: Float = 1f) {
    AutoSplitMenu(controller, state, Modifier.weight(weight).fillMaxSize()) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                // Dimmed like a disabled button while not recording; dragging up still sets auto-split.
                modifier = Modifier.fillMaxSize().alpha(if (state.isRecording) 1f else 0.5f),
            ) {
                SplitIcon(state, MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    if (state.autoSplit == AutoSplit.OFF) "Split" else "Split · auto ${state.autoSplit.badge}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text("drag up: auto", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
