package com.voicepad.app.ui

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Easing
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicepad.app.conversation.ConversationController
import com.voicepad.app.conversation.Glyphs
import com.voicepad.app.conversation.LanguageNames
import com.voicepad.app.conversation.SpeakerRef
import com.voicepad.app.conversation.Stage
import com.voicepad.app.conversation.Stage.Phase
import com.voicepad.app.conversation.Turn
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal val SpeakerColors = listOf(
    Color(0xFF1E88E5), Color(0xFFF4511E), Color(0xFF43A047),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFD81B60),
)

internal fun speakerColor(ref: SpeakerRef?): Color =
    ref?.let { SpeakerColors[it.index % SpeakerColors.size] } ?: Color.Gray

/** [turn]'s colour; speech still in progress has no speaker yet, so it goes by its language. */
internal fun stageColor(turn: Turn?, myLanguage: String): Color = when {
    turn == null -> Color.Gray
    turn.speaker != null -> speakerColor(turn.speaker)
    else -> SpeakerColors[if (turn.language == myLanguage) 0 else 1]
}

/** False when the phone's animations are switched off (Settings → Accessibility → Remove animations). */
@Composable
internal fun rememberMotionEnabled(force: Boolean = false): Boolean {
    val systemOn = rememberSystemAnimationsOn()
    return force || systemOn
}

/** Whether the phone's own animations are on (power saving and accessibility settings can turn them off). */
@Composable
internal fun rememberSystemAnimationsOn(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/**
 * The conversation in one [language] as a stage: the newest thought big at
 * the bottom, lit in its speaker's colour, everything older small and dim
 * above it (scroll up to read back).
 *
 * The newest thought moves through the [Stage.Phase]s: words arriving,
 * a held pause, the models working, and the translation landing.
 */
@Composable
internal fun StagePane(
    language: String,
    state: ConversationController.UiState,
    textSize: Int,
    level: () -> Float,
    modifier: Modifier,
    showHistory: Boolean = true,
) {
    val stage = state.stage
    val turn = stage.turn
    val motion = rememberMotionEnabled(state.settings.forceAnimations)
    val color = stageColor(turn, state.settings.myLanguage)
    val listState = rememberLazyListState()
    // Stay on the newest words unless the reader scrolled back.
    LaunchedEffect(stage.history.size, turn?.id) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    Box(modifier) {
        Aura(color, stage.phase, level, motion, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp)) {
                Text(LanguageNames.native(language), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (state.hearing) {
                    Spacer(Modifier.width(10.dp))
                    HearingBars(MaterialTheme.colorScheme.primary, level)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (turn == null) {
                    Text(
                        if (state.listening) "…" else "",
                        fontSize = textSize.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (turn != null) {
                        item(key = "hero") { Hero(turn, stage.phase, language, textSize, color, motion) }
                    }
                    if (showHistory) {
                        val newestFirst = stage.history.asReversed()
                        items(newestFirst, key = { it.id }) { HistoryTurn(it, language, textSize) }
                    }
                }
            }
        }
    }
}

/** The newest thought as [language]'s reader sees it. */
@Composable
private fun Hero(turn: Turn, phase: Phase, language: String, textSize: Int, color: Color, motion: Boolean) {
    val original = turn.language == language
    // Double-tap types what this reader sees (finished turns only).
    val typed = if (phase == Phase.LANDED) (turn.textIn(language) ?: turn.text) else ""
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).doubleTapToType(typed)) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ConversationController.speakerLabel(turn) + when (phase) {
                        Phase.SPEAKING -> " · speaking…"
                        Phase.HOLDING -> " · thinking…"
                        Phase.WORKING -> " · translating…"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
                if (motion && phase == Phase.WORKING) {
                    Spacer(Modifier.width(10.dp))
                    Orbit(Modifier.size(22.dp))
                }
                if (motion && phase == Phase.HOLDING) {
                    Spacer(Modifier.width(10.dp))
                    ThinkingDots(color)
                }
                if (phase != Phase.SPEAKING && phase != Phase.HOLDING) SpeakButton(turn, language, color)
            }
            // Once it has landed, tap a word (when one side is Chinese) for its breakdown.
            val landed = phase == Phase.LANDED
            val mainLayout = remember { TextLayoutRef() }
            val tapMain = if (landed) Modifier.tapWordsOf(turn, tappedTranslation = !original, mainLayout, typed) else Modifier
            if (original) {
                SpokenText(turn, phase, textSize, tapMain, onLayout = { mainLayout.value = it })
            } else {
                TranslatedText(turn, phase, language, textSize, color, motion, tapMain, onLayout = { mainLayout.value = it })
            }
            if (!original) {
                val spokenLayout = remember { TextLayoutRef() }
                Text(
                    turn.text + (if (turn.translationFailed) "  (not translated)" else ""),
                    fontSize = (textSize * 0.42f).coerceAtLeast(13f).sp,
                    lineHeight = (textSize * 0.55f).coerceAtLeast(16f).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    onTextLayout = { spokenLayout.value = it },
                    modifier = if (landed) Modifier.tapWordsOf(turn, tappedTranslation = false, spokenLayout, typed) else Modifier,
                )
            }
        }
    }
}

/** The speaker's own words: while they talk, the newest few are the brightest. */
@Composable
private fun SpokenText(
    turn: Turn,
    phase: Phase,
    textSize: Int,
    modifier: Modifier = Modifier,
    onLayout: (TextLayoutResult) -> Unit = {},
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val text = turn.text
    val settled = if (phase != Phase.SPEAKING) 0 else if (Glyphs.isDense(turn.language)) {
        (text.length - 5).coerceAtLeast(0)
    } else {
        // Everything before the last three words.
        var cut = text.length
        repeat(3) { cut = text.lastIndexOf(' ', (cut - 1).coerceAtLeast(0)).coerceAtLeast(0) }
        cut
    }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = ink.copy(alpha = 0.62f))) { append(text.take(settled)) }
            append(text.drop(settled))
        },
        fontSize = textSize.sp,
        lineHeight = (textSize * 1.2f).sp,
        fontWeight = FontWeight.SemiBold,
        color = ink,
        onTextLayout = onLayout,
        modifier = modifier,
    )
}

/**
 * The translation the listener reads. While it's on its way, stand-in
 * characters in the reader's own script flicker where the words will be;
 * when it arrives, they resolve into it from the start.
 */
@Composable
private fun TranslatedText(
    turn: Turn,
    phase: Phase,
    language: String,
    textSize: Int,
    color: Color,
    motion: Boolean,
    modifier: Modifier = Modifier,
    onLayout: (TextLayoutResult) -> Unit = {},
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val translation = turn.translation
    val pending = turn.pending && !turn.translationFailed

    // 0 → 1 as a finished translation takes over from the stand-ins.
    val reveal = remember(turn.id) { mutableFloatStateOf(1f) }
    var wasPending by remember(turn.id) { mutableStateOf(pending) }
    LaunchedEffect(turn.id, pending) {
        if (wasPending && !pending && motion) {
            // Driven by the frame clock, so it also plays when the phone's animations are off but forced on here.
            val start = withFrameMillis { it }
            reveal.floatValue = 0f
            while (reveal.floatValue < 1f) {
                withFrameMillis { now -> reveal.floatValue = ((now - start) / 550f).coerceAtMost(1f) }
            }
        }
        wasPending = pending
    }

    val shown: String
    val tailLength: Int
    if (pending) {
        shown = translation.orEmpty()
        val expected = Glyphs.estimateLength(turn.text, turn.language, language)
        // A held turn isn't being worked on, so nothing flickers.
        tailLength = if (phase == Phase.HOLDING) 0 else (expected - shown.length).coerceIn(3, 28)
    } else {
        val full = translation ?: turn.text
        val k = (reveal.value * full.length).toInt().coerceIn(0, full.length)
        shown = full.take(k)
        tailLength = (full.length - k).coerceAtMost(28)
    }
    val scramble by rememberScramble(language, active = motion && tailLength > 0)
    val tail = when {
        tailLength == 0 -> ""
        !motion -> "…"
        else -> scramble.take(tailLength)
    }
    Text(
        buildAnnotatedString {
            // Just landed: the words arrive in the speaker's colour and settle to the normal one.
            withStyle(SpanStyle(color = if (pending) ink.copy(alpha = 0.75f) else lerp(color, ink, reveal.value))) { append(shown) }
            if (tail.isNotEmpty()) {
                if (shown.isNotEmpty() && !Glyphs.isDense(language)) append(' ')
                withStyle(SpanStyle(color = color.copy(alpha = if (phase == Phase.SPEAKING) 0.4f else 0.85f))) { append(tail) }
            }
        },
        fontSize = textSize.sp,
        lineHeight = (textSize * 1.2f).sp,
        fontWeight = FontWeight.SemiBold,
        color = ink,
        onTextLayout = onLayout,
        modifier = modifier,
    )
}

/** Forty stand-in characters for [language], a third of them changing every 70 ms while [active]. */
@Composable
private fun rememberScramble(language: String, active: Boolean): State<String> {
    val pool = remember(language) { Glyphs.pool(language) }
    val first = remember(language) { String(CharArray(40) { if (Glyphs.isGap(language, it)) ' ' else pool.random() }) }
    return produceState(first, language, active) {
        if (!active) return@produceState
        val chars = value.toCharArray()
        while (true) {
            delay(70)
            for (i in chars.indices) if (chars[i] != ' ' && Random.nextFloat() < 0.35f) chars[i] = pool.random()
            value = String(chars)
        }
    }
}

/** An older turn: small and dim, so the newest one owns the stage. */
@Composable
private fun HistoryTurn(turn: Turn, language: String, textSize: Int) {
    val size = (textSize * 0.5f).coerceAtLeast(15f)
    val typed = turn.textIn(language) ?: turn.text
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).doubleTapToType(typed)) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(speakerColor(turn.speaker).copy(alpha = 0.6f), RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(10.dp))
        val layout = remember { TextLayoutRef() }
        Text(
            turn.textIn(language) ?: turn.text,
            fontSize = size.sp,
            lineHeight = (size * 1.25f).sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout.value = it },
            modifier = Modifier.weight(1f, fill = false)
                .tapWordsOf(turn, tappedTranslation = language != turn.language && turn.translation != null, layout, typed),
        )
        SpeakButton(turn, language, speakerColor(turn.speaker).copy(alpha = 0.8f))
    }
}

/**
 * A glow in the speaker's colour from the pane's top edge: it follows their
 * voice while they talk, breathes slowly during a held pause, and pulses
 * faintly while the models work.
 */
@Composable
private fun Aura(color: Color, phase: Phase, level: () -> Float, motion: Boolean, modifier: Modifier) {
    val breath = if (motion && (phase == Phase.HOLDING || phase == Phase.WORKING)) {
        rememberCycle(if (phase == Phase.HOLDING) 2400 else 700, reverse = true, easing = FastOutSlowInEasing)
    } else null
    Canvas(modifier) {
        val alpha = when (phase) {
            Phase.IDLE -> 0f
            Phase.SPEAKING -> 0.10f + (if (motion) level().coerceIn(0f, 1f) * 0.30f else 0.08f)
            Phase.HOLDING -> 0.08f + 0.12f * (breath?.value ?: 0.5f)
            Phase.WORKING -> 0.10f + 0.07f * (breath?.value ?: 0.5f)
            Phase.LANDED -> 0.05f
        }
        if (alpha <= 0f) return@Canvas
        drawRect(
            Brush.radialGradient(
                listOf(color.copy(alpha = alpha), Color.Transparent),
                center = Offset(size.width / 2, 0f),
                radius = size.width * 0.8f,
            )
        )
    }
}

/** Five small bars that move with the microphone level: a voice is being heard. */
@Composable
private fun HearingBars(color: Color, level: () -> Float) {
    val shape = remember { floatArrayOf(0.55f, 1f, 0.7f, 0.9f, 0.5f) }
    Canvas(Modifier.size(width = 26.dp, height = 14.dp)) {
        val l = level().coerceIn(0f, 1f)
        val w = size.width / 9
        shape.forEachIndexed { i, s ->
            val h = size.height * (0.18f + 0.82f * l * s)
            drawRoundRect(color, Offset(i * 2 * w, (size.height - h) / 2), Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 2))
        }
    }
}

/** Three dots breathing in turn: the speaker is thinking, and the turn is still theirs. */
@Composable
private fun ThinkingDots(color: Color) {
    val t by rememberCycle(2400)
    Canvas(Modifier.size(width = 30.dp, height = 10.dp)) {
        val r = size.height / 2
        repeat(3) { i ->
            val wave = (sin((t - i * 0.14f) * 2 * PI) + 1) / 2
            drawCircle(color.copy(alpha = 0.25f + 0.75f * wave.toFloat()), r * (0.7f + 0.3f * wave.toFloat()), Offset(r + i * (size.width - 2 * r) / 2, r))
        }
    }
}

/** The two sides' colours circling each other: the models are working between the languages. */
@Composable
private fun Orbit(modifier: Modifier) {
    val cycle by rememberCycle(900)
    val angle = cycle * (2 * PI).toFloat()
    Canvas(modifier) {
        val r = size.minDimension * 0.3f
        val dot = size.minDimension * 0.17f
        repeat(2) { i ->
            val a = angle + i * PI.toFloat()
            drawCircle(SpeakerColors[i], dot, center + Offset(cos(a) * r, sin(a) * r))
        }
    }
}

/**
 * Round the microphone while a turn is held open: it drains over the
 * thinking pause and snaps back to full when the speaker goes on.
 */
@Composable
internal fun HoldRing(hold: ConversationController.Hold?, refilled: Boolean, color: Color, modifier: Modifier) {
    if (hold == null) return
    var fraction by remember(hold) { mutableFloatStateOf(1f) }
    LaunchedEffect(hold, refilled) {
        if (refilled) {
            fraction = 1f
            return@LaunchedEffect
        }
        while (true) {
            fraction = ((hold.endsAtMs - SystemClock.uptimeMillis()).toFloat() / hold.totalMs).coerceIn(0f, 1f)
            if (fraction <= 0f) break
            withFrameMillis { }
        }
    }
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val inset = Offset(stroke / 2, stroke / 2)
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(color.copy(alpha = 0.22f), 0f, 360f, false, inset, arc, style = Stroke(stroke))
        drawArc(color, -90f, 360f * fraction, false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Lays its content out turned by [quarterTurns] × 90°, swapping width and height for odd turns. */
internal fun Modifier.quarterTurns(quarterTurns: Int): Modifier {
    val n = quarterTurns.mod(4)
    if (n == 0) return this
    return layout { measurable, constraints ->
        val swap = n % 2 == 1
        val inner = if (swap) {
            Constraints(constraints.minHeight, constraints.maxHeight, constraints.minWidth, constraints.maxWidth)
        } else constraints
        val placeable = measurable.measure(inner)
        val width = if (swap) placeable.height else placeable.width
        val height = if (swap) placeable.width else placeable.height
        layout(width, height) {
            placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) { rotationZ = 90f * n }
        }
    }
}

/**
 * A value cycling 0 → 1 every [periodMs] (back down again when [reverse]),
 * driven by the frame clock. Unlike Compose's transitions it keeps moving
 * when the phone's animations are switched off, for "animate anyway".
 */
@Composable
private fun rememberCycle(periodMs: Int, reverse: Boolean = false, easing: Easing = LinearEasing): State<Float> {
    val value = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs, reverse) {
        val start = withFrameMillis { it }
        val span = if (reverse) 2L * periodMs else periodMs.toLong()
        while (true) {
            withFrameMillis { now ->
                val p = ((now - start) % span).toFloat() / periodMs
                value.floatValue = easing.transform(if (reverse && p > 1f) 2f - p else p.coerceAtMost(1f))
            }
        }
    }
    return value
}
