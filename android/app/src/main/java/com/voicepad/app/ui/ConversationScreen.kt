package com.voicepad.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.voicepad.app.speech.LocalVoice
import com.voicepad.app.speech.SpeechOutput
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.nl.translate.TranslateLanguage
import com.voicepad.app.RecordingController
import com.voicepad.app.RecordingController.EngineStatus
import com.voicepad.app.conversation.ConversationController
import com.voicepad.app.conversation.ConversationController.Readiness
import com.voicepad.app.conversation.ConversationLayout
import com.voicepad.app.conversation.LanguageNames
import com.voicepad.app.conversation.SpeakerRef
import com.voicepad.app.conversation.SpeakerSource
import com.voicepad.app.conversation.Turn
import com.voicepad.app.transcription.EngineChoice
import kotlinx.coroutines.delay

/**
 * Conversation mode: live two-way translation in big text.
 *
 * Face to face (default): the screen is split in two, the far half upside
 * down, each half showing the whole conversation in one language — what its
 * reader said, and what the other person said translated. Controls sit in
 * the middle where both can reach them.
 */
@Composable
fun ConversationScreen(conversation: ConversationController, notes: RecordingController) {
    val state by conversation.state.collectAsStateWithLifecycle()
    val notesState by notes.state.collectAsStateWithLifecycle()
    val speechState by conversation.speech.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Side?>(null) }
    val context = LocalContext.current

    BackHandler { conversation.close() }
    // Nobody should have to tap the screen to keep it awake mid-conversation.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var levels by remember { mutableStateOf(FloatArray(1)) }
    LaunchedEffect(state.listening) {
        while (state.listening) {
            levels = conversation.channelLevels
            delay(80)
        }
        levels = FloatArray(state.recordingChannels)
    }

    val settings = state.settings
    val controls: @Composable () -> Unit = {
        ControlStrip(
            state = state,
            notesState = notesState,
            levels = levels,
            onClose = conversation::close,
            onToggle = conversation::toggleListening,
            onPick = { picking = it },
            onSwap = conversation::swapLanguages,
            onSettings = { showSettings = true },
            onCycleLayout = {
                val layout = conversation.cycleLayout()
                Toast.makeText(context, layout.label, Toast.LENGTH_SHORT).show()
            },
            onDismissMessage = conversation::dismissMessage,
            onRetryModels = { conversation.retryModels(); if (notesState.engineStatus is EngineStatus.Failed) notes.loadEngine() },
            onUseEngine = notes::selectModel,
        )
    }

    CompositionLocalProvider(
        LocalSpeak provides SpeakActions(speechState, onSpeak = conversation::speak, onDismissError = conversation.speech::dismissError),
    ) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            when (state.layout) {
                ConversationLayout.OUTER_SCREEN -> {
                    // Theirs is on the outer screen (OuterScreen.kt); this is yours.
                    LanguagePane(settings.myLanguage, state, settings.textSize, Modifier.weight(1f).fillMaxWidth())
                    controls()
                }
                ConversationLayout.FACE_TO_FACE -> {
                    LanguagePane(
                        settings.theirLanguage, state, settings.textSize,
                        Modifier.weight(1f).fillMaxWidth().graphicsLayer { rotationZ = 180f },
                    )
                    controls()
                    LanguagePane(settings.myLanguage, state, settings.textSize, Modifier.weight(1f).fillMaxWidth())
                }
                ConversationLayout.FACE_TO_FACE_SIDEWAYS -> {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        LanguagePane(settings.myLanguage, state, settings.textSize, Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider()
                        LanguagePane(
                            settings.theirLanguage, state, settings.textSize,
                            Modifier.weight(1f).fillMaxHeight().graphicsLayer { rotationZ = 180f },
                        )
                    }
                    controls()
                }
                ConversationLayout.SIDE_BY_SIDE -> {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        LanguagePane(settings.myLanguage, state, settings.textSize, Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider()
                        LanguagePane(settings.theirLanguage, state, settings.textSize, Modifier.weight(1f).fillMaxHeight())
                    }
                    controls()
                }
                ConversationLayout.TRANSCRIPT -> {
                    TranscriptPane(state, settings.textSize, Modifier.weight(1f).fillMaxWidth())
                    controls()
                }
            }
        }
    }
    }

    picking?.let { side ->
        LanguagePickerDialog(
            title = if (side == Side.MINE) "You speak" else "They speak",
            selected = if (side == Side.MINE) settings.myLanguage else settings.theirLanguage,
            onPick = { code ->
                if (side == Side.MINE) conversation.setLanguages(code, settings.theirLanguage)
                else conversation.setLanguages(settings.myLanguage, code)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
    if (showSettings) {
        ConversationSettingsSheet(conversation, state, notesState, notes.conversationMicChannels(), onPick = { picking = it }, onDismiss = { showSettings = false })
    }
}

internal enum class Side { MINE, THEIRS }

/** Speaking turns aloud; absent (e.g. in previews) the speaker icons are hidden. */
internal class SpeakActions(
    val state: SpeechOutput.State,
    val onSpeak: (Turn, String) -> Unit,
    val onDismissError: () -> Unit,
)

internal val LocalSpeak = staticCompositionLocalOf<SpeakActions?> { null }

/** Speaker icon: says the turn in [language]; while it's being said, a stop icon. */
@Composable
private fun SpeakButton(turn: Turn, language: String, tint: Color) {
    val speak = LocalSpeak.current ?: return
    if (turn.id < 0 || turn.textIn(language) == null) return
    val active = speak.state.activeId == ConversationController.speechId(turn.id, language)
    IconButton(onClick = { speak.onSpeak(turn, language) }, modifier = Modifier.size(32.dp)) {
        Icon(
            if (active) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (active) "Stop" else "Say it",
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private val SpeakerColors = listOf(
    Color(0xFF1E88E5), Color(0xFFF4511E), Color(0xFF43A047),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFD81B60),
)

internal fun speakerColor(ref: SpeakerRef?): Color =
    ref?.let { SpeakerColors[it.index % SpeakerColors.size] } ?: Color.Gray

/** The whole conversation in one [language], newest at the bottom. */
@Composable
internal fun LanguagePane(language: String, state: ConversationController.UiState, textSize: Int, modifier: Modifier) {
    val turns = state.turns
    val live = state.live
    val listState = rememberLazyListState()
    // Stay on the newest words unless the reader scrolled back.
    LaunchedEffect(turns.size, turns.lastOrNull()?.text, live?.text) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    Column(modifier) {
        Text(
            LanguageNames.native(language),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (turns.isEmpty() && live == null) {
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (live != null) {
                    item(key = "live") { PaneTurn(live, language, textSize, isLive = true, newest = true) }
                }
                val newestFirst = turns.asReversed()
                items(newestFirst.size, key = { newestFirst[it].id }) { i ->
                    PaneTurn(newestFirst[i], language, textSize, isLive = false, newest = live == null && i == 0)
                }
            }
        }
    }
}

/**
 * One turn as its reader sees it: in their language, big; the newest the
 * biggest. Translated turns also show what was actually said, small.
 */
@Composable
private fun PaneTurn(turn: Turn, language: String, textSize: Int, isLive: Boolean, newest: Boolean) {
    val color = speakerColor(turn.speaker)
    val size = if (newest) textSize else (textSize * 0.7f).toInt()
    val original = turn.language == language
    val main = turn.textIn(language) ?: if (turn.translationFailed) turn.text else "…"
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ConversationController.speakerLabel(turn) + if (isLive) " · speaking…" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
                SpeakButton(turn, language, color)
            }
            Text(
                main,
                fontSize = size.sp,
                lineHeight = (size * 1.2f).sp,
                fontWeight = if (newest) FontWeight.SemiBold else FontWeight.Normal,
                fontStyle = if (isLive) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (newest || isLive) 1f else 0.75f),
            )
            if (!original) {
                Text(
                    turn.text + (if (turn.translationFailed) "  (not translated)" else ""),
                    fontSize = (size * 0.42f).coerceAtLeast(13f).sp,
                    lineHeight = (size * 0.55f).coerceAtLeast(16f).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One list with each turn's original, its romanisation and its translation. */
@Composable
internal fun TranscriptPane(state: ConversationController.UiState, textSize: Int, modifier: Modifier) {
    val listState = rememberLazyListState()
    val all = state.turns + listOfNotNull(state.live)
    LaunchedEffect(all.size, all.lastOrNull()?.text, all.lastOrNull()?.translation) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier,
    ) {
        val newestFirst = all.asReversed()
        items(newestFirst, key = { it.id }) { turn ->
            val isLive = turn.id < 0
            val newest = turn === newestFirst.first()
            val size = if (newest) textSize else (textSize * 0.7f).toInt()
            val color = speakerColor(turn.speaker)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(5.dp).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (turn.speaker == null) "" else ConversationController.speakerLabel(turn) + " · ") +
                                LanguageNames.native(turn.language) + if (isLive) " · speaking…" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = color,
                        )
                        // Says the translation, for the other person.
                        SpeakButton(turn, turn.target, color)
                    }
                    Text(
                        turn.text,
                        fontSize = (size * 0.8f).sp,
                        lineHeight = (size * 0.95f).sp,
                        fontStyle = if (isLive) FontStyle.Italic else FontStyle.Normal,
                    )
                    turn.romanization?.let {
                        Text(it, fontSize = (size * 0.45f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "→ " + (turn.translation ?: if (turn.translationFailed) "(not translated)" else "…"),
                        fontSize = size.sp,
                        lineHeight = (size * 1.2f).sp,
                        fontWeight = if (newest) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    turn.translationRomanization?.let {
                        Text(it, fontSize = (size * 0.45f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Close, the two languages, the microphone, and what's still loading. */
@Composable
internal fun ControlStrip(
    state: ConversationController.UiState,
    notesState: RecordingController.UiState,
    levels: FloatArray,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onPick: (Side) -> Unit,
    onSwap: () -> Unit,
    onSettings: () -> Unit,
    onDismissMessage: () -> Unit,
    onRetryModels: () -> Unit,
    onUseEngine: (EngineChoice) -> Unit,
    onCycleLayout: () -> Unit = {},
) {
    val settings = state.settings
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            StatusLines(state, notesState, onDismissMessage, onRetryModels, onUseEngine)
            val languages = @Composable { modifier: Modifier ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier, horizontalArrangement = Arrangement.Center) {
                    LanguageButton(settings.myLanguage) { onPick(Side.MINE) }
                    IconButton(onClick = onSwap, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Swap languages")
                    }
                    LanguageButton(settings.theirLanguage) { onPick(Side.THEIRS) }
                }
            }
            // On a phone-width screen the languages get a row of their own above the buttons.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 440.dp
            Column {
            if (narrow) languages(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Leave conversation mode") }
                val layoutButton = @Composable {
                    // Tap to cycle through the layouts; the icon shows the current one.
                    IconButton(onClick = onCycleLayout, modifier = Modifier.size(40.dp)) {
                        Icon(layoutIcon(state.layout), contentDescription = "Layout: ${state.layout.label}. Tap for the next one.")
                    }
                }
                if (narrow) {
                    layoutButton()
                    Spacer(Modifier.weight(1f))
                } else {
                    languages(Modifier.weight(1f))
                }
                MicButton(state.listening, levels.maxOrNull() ?: 0f, onToggle)
                Spacer(Modifier.width(6.dp))
                if (state.listening && state.recordingChannels > 1) ChannelMeters(levels)
                if (narrow) Spacer(Modifier.weight(1f)) else layoutButton()
                IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Settings, contentDescription = "Conversation settings") }
            }
            }
            }
        }
    }
}

private fun layoutIcon(layout: ConversationLayout): ImageVector = when (layout) {
    ConversationLayout.FACE_TO_FACE -> Icons.Default.ViewAgenda
    ConversationLayout.FACE_TO_FACE_SIDEWAYS -> Icons.Default.VerticalSplit
    ConversationLayout.SIDE_BY_SIDE -> Icons.Default.ViewColumn
    ConversationLayout.TRANSCRIPT -> Icons.AutoMirrored.Filled.ViewList
    ConversationLayout.OUTER_SCREEN -> Icons.Default.ScreenShare
}

@Composable
private fun LanguageButton(code: String, onClick: () -> Unit) {
    Text(
        LanguageNames.native(code),
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 140.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

@Composable
private fun MicButton(listening: Boolean, level: Float, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (listening) "Pause listening" else "Start listening" },
    ) {
        if (listening) {
            Box(Modifier.size(60.dp).scale(0.8f + level * 0.4f).background(RecordingRed.copy(alpha = 0.22f), CircleShape))
            Box(Modifier.size(48.dp).background(RecordingRed, CircleShape))
            Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White)
        } else {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
            Icon(Icons.Default.MicOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A small level bar per channel, in its speaker's colour, so you can see which mic is which. */
@Composable
private fun ChannelMeters(levels: FloatArray) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(28.dp),
    ) {
        levels.forEachIndexed { channel, level ->
            Box(Modifier.width(5.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(level.coerceIn(0.05f, 1f))
                        .background(SpeakerColors[channel % SpeakerColors.size], RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/** What's loading or wrong, most important first; each line says what to do about it. */
@Composable
private fun StatusLines(
    state: ConversationController.UiState,
    notesState: RecordingController.UiState,
    onDismissMessage: () -> Unit,
    onRetry: () -> Unit,
    onUseEngine: (EngineChoice) -> Unit,
) {
    val settings = state.settings
    val lines = buildList<@Composable () -> Unit> {
        state.message?.let { add { StatusText(it, error = true, action = "OK", onAction = onDismissMessage) } }
        when (val s = notesState.engineStatus) {
            is EngineStatus.Loading -> add {
                StatusText(if (s.downloading) "Downloading speech model… ${(s.progress * 100).toInt()}%" else "Loading speech model…")
            }
            is EngineStatus.Failed -> add { StatusText("Speech model failed: ${s.message}", error = true, action = "Retry", onAction = onRetry) }
            else -> {}
        }
        val engine = notesState.activeEngine
        val missing = listOf(settings.myLanguage, settings.theirLanguage).filterNot(engine::understands)
        if (missing.isNotEmpty()) {
            val recommended = EngineChoice.recommendedFor(settings.myLanguage, settings.theirLanguage, notesState.installedEngines)
            add {
                StatusText(
                    "${engine.shortName} doesn't understand ${missing.joinToString(" or ") { LanguageNames.english(it) }}." +
                        if (recommended in notesState.installedEngines) "" else " ${recommended.shortName} is a ${recommended.approximateSize.removeSuffix(" download")} download.",
                    error = true,
                    action = "Use ${recommended.shortName}",
                    onAction = { onUseEngine(recommended) },
                )
            }
        }
        when (val t = state.translation) {
            is Readiness.Preparing -> add { StatusText("Getting translation packs (about 30 MB per language, once)…") }
            is Readiness.Failed -> add { StatusText("Translation unavailable: ${t.message}", error = true, action = "Retry", onAction = onRetry) }
            else -> {}
        }
        if (state.speakerSource != SpeakerSource.CHANNEL && settings.identifyVoices) {
            when (val v = state.voiceModel) {
                is Readiness.Preparing -> add {
                    StatusText(v.progress?.let { "Downloading voice model to tell speakers apart… ${(it * 100).toInt()}%" } ?: "Loading voice model…")
                }
                is Readiness.Failed -> add { StatusText("Can't tell voices apart: ${v.message}", action = "Retry", onAction = onRetry) }
                else -> {}
            }
        }
        if (state.listening && state.channelsIdentical) {
            add { StatusText("Your mic's channels carry the same sound, so speakers are told apart by voice instead.") }
        }
        LocalSpeak.current?.let { speak ->
            speak.state.error?.let { add { StatusText(it, error = true, action = "OK", onAction = speak.onDismissError) } }
            speak.state.voices.forEach { (voice, status) ->
                if (status is SpeechOutput.VoiceStatus.Downloading) {
                    add { StatusText("Downloading ${voice.displayName} voice… ${(status.progress * 100).toInt()}% (the phone's voice speaks meanwhile)") }
                }
            }
        }
        if (state.layout == ConversationLayout.OUTER_SCREEN) {
            when (state.outerScreen) {
                ConversationController.OuterScreen.UNSUPPORTED ->
                    add { StatusText("This phone can't show anything on its outer screen while unfolded — pick another layout in settings.", error = true) }
                ConversationController.OuterScreen.UNAVAILABLE ->
                    add { StatusText("The outer screen isn't available right now — unfold the phone fully.") }
                else -> {}
            }
        }
        if (!state.listening) add { StatusText("Paused — tap the microphone to listen.") }
    }
    lines.take(3).forEach { it() }
}

@Composable
private fun StatusText(text: String, error: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 2.dp),
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Every language on-device translation offers, common ones first. */
@Composable
private fun LanguagePickerDialog(title: String, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val languages = remember {
        val all = TranslateLanguage.getAllLanguages().toSet()
        LanguageNames.COMMON.filter { it in all } + all.filterNot { it in LanguageNames.COMMON }.sortedBy(LanguageNames::english)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.height(420.dp)) {
                items(languages, key = { it }) { code ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(code) }
                            .background(if (code == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(LanguageNames.native(code), Modifier.weight(1f), fontWeight = if (code == selected) FontWeight.SemiBold else FontWeight.Normal)
                        Text(LanguageNames.english(code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ConversationSettingsSheet(
    conversation: ConversationController,
    state: ConversationController.UiState,
    notesState: RecordingController.UiState,
    micChannels: Int,
    onPick: (Side) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = state.settings
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Conversation", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Text size", Modifier.weight(1f))
                IconButton(onClick = { conversation.setTextSize(settings.textSize - 4) }) { Icon(Icons.Default.Remove, "Smaller") }
                Text("${settings.textSize}", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { conversation.setTextSize(settings.textSize + 4) }) { Icon(Icons.Default.Add, "Bigger") }
            }

            SectionTitle("Layout")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConversationLayout.entries.filter {
                    it != ConversationLayout.OUTER_SCREEN || state.outerScreen != ConversationController.OuterScreen.UNSUPPORTED || state.layout == it
                }.forEach { layout ->
                    FilterChip(selected = state.layout == layout, onClick = { conversation.setLayout(layout) }, label = { Text(layout.label) })
                }
            }
            Text(
                when (state.layout) {
                    ConversationLayout.OUTER_SCREEN ->
                        "Unfolded: this screen shows the conversation in your language; the outer screen shows their " +
                            "language, the last two turns in big text, for the person facing the back of the phone. " +
                            "Used automatically when unfolding opens conversation mode."
                    ConversationLayout.FACE_TO_FACE -> "Lay the phone between you: the far half is upside down, in their language."
                    ConversationLayout.FACE_TO_FACE_SIDEWAYS ->
                        "Lay a wide or unfolded screen between you, one at each end: their half is turned round to face them."
                    ConversationLayout.SIDE_BY_SIDE -> "One column per language, for a wide or unfolded screen you both look at."
                    ConversationLayout.TRANSCRIPT -> "Original, pronunciation and translation together, for following along yourself."
                },
                style = MaterialTheme.typography.bodySmall, color = muted,
            )

            SectionTitle("Languages")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("You speak", Modifier.weight(1f))
                OutlinedButton(onClick = { onPick(Side.MINE) }) { Text(LanguageNames.english(settings.myLanguage)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("They speak", Modifier.weight(1f))
                OutlinedButton(onClick = { onPick(Side.THEIRS) }) { Text(LanguageNames.english(settings.theirLanguage)) }
            }
            val engine = notesState.activeEngine
            val recommended = EngineChoice.recommendedFor(settings.myLanguage, settings.theirLanguage, notesState.installedEngines)
            Text(
                "Speech model: ${engine.displayName}. " +
                    if (engine.understands(settings.myLanguage) && engine.understands(settings.theirLanguage)) "It understands both languages."
                    else "Best for these languages: ${recommended.displayName} (${recommended.approximateSize}).",
                style = MaterialTheme.typography.bodySmall, color = muted,
            )

            SectionTitle("Microphone channels")
            val maxChannels = maxOf(2, micChannels)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 4, 6, 8).filter { it <= maxChannels }.forEach { n ->
                    FilterChip(selected = settings.channels == n, onClick = { conversation.setChannels(n) }, label = { Text("$n") })
                }
            }
            Text(
                "1: one microphone; people are told apart by the sound of their voice. " +
                    "2 or more: one mic per person — e.g. a two-transmitter wireless kit in stereo mode, a " +
                    "multi-channel USB interface, or the phone's own top and bottom mics with the phone between " +
                    "you. Each channel is one speaker, and speech that leaks into the other mics is ignored. " +
                    "The mic is the one chosen in Settings → Microphone" +
                    (notesState.inputDevices.firstOrNull { it.key == notesState.selectedInputKey }?.let { " (${it.name})." } ?: " (automatic)."),
                style = MaterialTheme.typography.bodySmall, color = muted,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            ToggleRow(
                title = "Tell voices apart",
                subtitle = "With one channel, recognise who is speaking by their voice and number them Speaker 1, 2… " +
                    "Downloads a ${com.voicepad.app.conversation.SpeakerEmbedder.SIZE_LABEL} voice model once; runs on the phone. " +
                    "Off: turns are labelled by language.",
                checked = settings.identifyVoices,
                onChange = conversation::setIdentifyVoices,
            )
            SpeechSettings(conversation, settings)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            ToggleRow(
                title = "Open when the phone is unfolded",
                subtitle = "On a foldable, unfolding it opens conversation mode; folding it again goes back to notes.",
                checked = settings.openOnUnfold,
                onChange = conversation::setOpenOnUnfold,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                "Every conversation is also saved as a note, with its audio and the transcript with translations.",
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
            TextButton(onClick = { conversation.clearTurns(); onDismiss() }) { Text("Clear the screen") }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Reading aloud: automatically, and which voices. */
@Composable
private fun SpeechSettings(conversation: ConversationController, settings: ConversationController.Settings) {
    val speech by conversation.speech.state.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ToggleRow(
        title = "Read translations aloud",
        subtitle = "Says each translation once its speaker pauses. The microphone ignores the phone's own voice " +
            "meanwhile, so speak after it finishes. Tap the speaker icon on any turn to hear it again.",
        checked = settings.autoSpeak,
        onChange = conversation::setAutoSpeak,
    )
    ToggleRow(
        title = "Use on-device voices",
        subtitle = "Kitten for English and MeloTTS for Mandarin, run on the phone and downloaded the first time " +
            "they're needed. Other languages, and English words Kitten doesn't know, use the phone's own voice. " +
            "Every clip is kept, so replaying is instant.",
        checked = speech.useLocalVoices,
        onChange = conversation.speech::setUseLocalVoices,
    )
    if (speech.useLocalVoices) {
        LocalVoice.entries.forEach { voice ->
            val status = speech.voices[voice]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
                Text("${voice.displayName} · ${voice.sizeLabel}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                when (status) {
                    SpeechOutput.VoiceStatus.Ready -> Text("Ready", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    is SpeechOutput.VoiceStatus.Downloading -> Text("${(status.progress * 100).toInt()}%", color = muted, style = MaterialTheme.typography.bodySmall)
                    else -> TextButton(onClick = { conversation.speech.download(voice) }) {
                        Text(if (status is SpeechOutput.VoiceStatus.Failed) "Retry download" else "Download")
                    }
                }
            }
        }
    }
}
