package com.voicepad.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import com.voicepad.app.conversation.Turn
import com.voicepad.app.zh.BreakdownRequest

/** Opens the word breakdown; provided by the conversation screen (absent on the cover screen). */
internal val LocalWordTap = staticCompositionLocalOf<((BreakdownRequest) -> Unit)?> { null }

/** The last layout of a Text, so a tap position can be turned into a character offset. */
internal class TextLayoutRef {
    var value: TextLayoutResult? = null
}

/**
 * Tap a word of [turn] for its breakdown: in what was said, or in its
 * translation ([tappedTranslation]). [layout] must be filled from the Text's
 * onTextLayout; [prefix] is how many characters are shown before the text
 * (like "→ "). Double-tap still types [typed] while a keyboard is connected.
 * Does nothing for turns with no Chinese side.
 */
@Composable
internal fun Modifier.tapWordsOf(
    turn: Turn,
    tappedTranslation: Boolean,
    layout: TextLayoutRef,
    typed: String,
    prefix: Int = 0,
): Modifier {
    val open = LocalWordTap.current ?: return this
    if (turn.id < 0 || turn.pending || BreakdownRequest.forTurn(turn, tappedTranslation, null) == null) return this
    val type = LocalTypeOnKeyboard.current
    val doubleTap: ((Offset) -> Unit)? = if (type != null && typed.isNotBlank()) {
        { _ -> type(typed) }
    } else {
        null
    }
    return pointerInput(turn, tappedTranslation, type, typed, prefix) {
        detectTapGestures(
            onTap = { pos ->
                val offset = layout.value?.getOffsetForPosition(pos)?.minus(prefix)?.coerceAtLeast(0)
                BreakdownRequest.forTurn(turn, tappedTranslation, offset)?.let(open)
            },
            onDoubleTap = doubleTap,
        )
    }
}
