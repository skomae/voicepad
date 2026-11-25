package com.voicepad.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepad.app.VoicePadApplication
import com.voicepad.app.transcription.LanguageTags
import com.voicepad.app.zh.BreakdownRequest
import com.voicepad.app.zh.ChineseDictionary
import com.voicepad.app.zh.Pinyin
import com.voicepad.app.zh.WordBreakdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private sealed interface Loaded {
    data object Missing : Loaded
    data class Ready(val engine: WordBreakdown, val result: WordBreakdown.Result) : Loaded
}

/**
 * How a Chinese text relates to its translation, word by word: each Chinese
 * word as a card (characters, pinyin, the meaning used), the translation's
 * words tinted in the colour of the card they came from, and for the chosen
 * word its other possible meanings and readings ranked by likelihood.
 * Choosing a word of the translation also lists other Chinese words for it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WordBreakdownSheet(request: BreakdownRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val english = request.translation?.takeIf { request.translationIsEnglish }
    val loaded by produceState<Loaded?>(null, request) {
        val engine = ChineseDictionary.get(context)
        value = if (engine == null) Loaded.Missing else withContext(Dispatchers.Default) {
            Loaded.Ready(engine, engine.analyse(request.chinese, english))
        }
    }
    var selectedCard by remember(request) { mutableStateOf<Int?>(null) }
    var selectedWord by remember(request) { mutableStateOf<Int?>(null) }
    LaunchedEffect(loaded) {
        val result = (loaded as? Loaded.Ready)?.result ?: return@LaunchedEffect
        when (val focus = request.focus) {
            is BreakdownRequest.Focus.Chinese -> selectedCard = result.cardAt(focus.offset)
            is BreakdownRequest.Focus.Translation -> {
                selectedWord = result.translationWordAt(focus.offset)
                selectedCard = selectedWord?.let { result.translationWords[it].card }
            }
            null -> selectedCard = result.cards.indices.firstOrNull()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Word breakdown", style = MaterialTheme.typography.titleLarge)
            when (val state = loaded) {
                null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Opening the dictionary…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Loaded.Missing -> Text(
                    "The Chinese dictionary isn't included in this build.",
                    color = MaterialTheme.colorScheme.error,
                )
                is Loaded.Ready -> Breakdown(
                    request = request,
                    engine = state.engine,
                    result = state.result,
                    english = english,
                    selectedCard = selectedCard,
                    selectedWord = selectedWord,
                    onCard = { i ->
                        selectedCard = i
                        selectedWord = state.result.translationWords.indexOfFirst { it.card == i }.takeIf { it >= 0 }
                    },
                    onWord = { w ->
                        selectedWord = w
                        state.result.translationWords[w].card?.let { selectedCard = it }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Breakdown(
    request: BreakdownRequest,
    engine: WordBreakdown,
    result: WordBreakdown.Result,
    english: String?,
    selectedCard: Int?,
    selectedWord: Int?,
    onCard: (Int) -> Unit,
    onWord: (Int) -> Unit,
) {
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    val linkedCards = result.translationWords.mapNotNull { it.card }.toSet()
    fun cardColor(i: Int): Color? = if (i in linkedCards) LinkColors[i % LinkColors.size] else null

    // The translation, each word tinted like the card it came from. Tap one to follow it.
    if (english != null) {
        Label("English · tap a word")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            result.translationWords.forEachIndexed { w, word ->
                val color = word.card?.let { cardColor(it) }
                val selected = w == selectedWord || (selectedWord == null && word.card != null && word.card == selectedCard)
                Text(
                    word.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    textDecoration = if (color != null) TextDecoration.Underline else null,
                    color = if (color == null) subtle else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background((color ?: subtle).copy(alpha = if (selected) 0.35f else if (color != null) 0.14f else 0f))
                        .clickable { onWord(w) }
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    } else if (request.translation != null) {
        Label(request.translationLanguage?.let { LanguageTags.displayName(it) } ?: "Translation")
        Text(request.translation, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Words are matched to the dictionary's English meanings, so they're only linked for English translations.",
            style = MaterialTheme.typography.bodySmall,
            color = subtle,
        )
    }

    Label("Chinese · tap a word")
    if (result.cards.isEmpty()) {
        Text(request.chinese, style = MaterialTheme.typography.titleLarge)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        result.cards.forEachIndexed { i, card -> WordCard(card, cardColor(i), selected = i == selectedCard) { onCard(i) } }
    }

    HorizontalDivider()
    val cardIndex = selectedCard?.takeIf { it in result.cards.indices }
    if (cardIndex != null) {
        val matchedWords = result.translationWords.filter { it.card == cardIndex }.map { it.text }
        CardDetail(result.cards[cardIndex], cardColor(cardIndex), matchedWords)
    }
    val word = selectedWord?.let { result.translationWords.getOrNull(it) }
    if (word != null) {
        if (word.card == null) {
            Text(
                "No Chinese word here was matched to “${word.text}”.",
                style = MaterialTheme.typography.bodyMedium,
                color = subtle,
            )
        }
        EnglishCandidates(engine, word.text)
    }

    Text(
        "Percentages are estimates from the dictionary, not the translator's own: each meaning is weighted by how " +
            "common the word and reading are and by its place in the entry, and boosted when it matches the " +
            "translation. Dictionary: CC-CEDICT (CC BY-SA 4.0). Word frequencies: jieba (MIT).",
        style = MaterialTheme.typography.bodySmall,
        color = subtle,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun WordCard(card: WordBreakdown.Card, color: Color?, selected: Boolean, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color?.copy(alpha = 0.12f) ?: MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(if (selected) 2.5.dp else 1.dp, if (selected) (color ?: MaterialTheme.colorScheme.primary) else outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${card.text}, ${card.pinyin.orEmpty()}, ${card.best?.gloss.orEmpty()}" },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(min = 52.dp, max = 140.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(tonedPinyin(card.numbered, card.pinyin), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(card.text, fontSize = 26.sp, lineHeight = 32.sp)
            Text(
                card.best?.let { shortGloss(it.gloss) } ?: "?",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CardDetail(card: WordBreakdown.Card, color: Color?, matchedWords: List<String>) {
    val accent = color ?: MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val speech = remember { (context.applicationContext as VoicePadApplication).speech }
    val speechState by speech.state.collectAsStateWithLifecycle()
    val speechId = "breakdown:${card.text}"
    val saying = speechState.activeId == speechId
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(card.text, fontSize = 40.sp, lineHeight = 46.sp)
        IconButton(onClick = { speech.say(speechId, card.text, "zh") }, modifier = Modifier.size(36.dp)) {
            if (saying && speechState.generating) {
                MakingVoiceBars(accent)
            } else {
                Icon(
                    if (saying) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (saying) "Stop" else "Say the word",
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(tonedPinyin(card.numbered, card.pinyin), style = MaterialTheme.typography.titleLarge)
            if (matchedWords.isNotEmpty()) {
                Text(
                    "→ " + matchedWords.joinToString(", ") { "“$it”" } + " in the translation",
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent,
                )
            }
        }
    }
    if (card.senses.isEmpty()) {
        Text("Not in the dictionary.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val showPinyin = card.readings.size > 1
        Label("Meanings")
        card.senses.take(MAX_SENSES).forEach { sense ->
            ScoreRow(
                title = sense.gloss,
                detail = sense.pinyin.takeIf { showPinyin },
                score = sense.score,
                color = accent,
                matched = sense.matched,
            )
        }
        if (card.senses.size > MAX_SENSES) {
            Text("and ${card.senses.size - MAX_SENSES} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showPinyin) {
            Label("Readings")
            card.readings.forEach { r -> ScoreRow(tonedPinyin(r.numbered, r.pinyin), null, r.score, accent, matched = false) }
        }
    }
    if (card.characters.isNotEmpty()) {
        Label("Characters")
        card.characters.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(c.char, fontSize = 24.sp, modifier = Modifier.width(36.dp))
                Text(tonedPinyin(c.numbered, c.pinyin), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(64.dp))
                Text(
                    c.gloss?.let(::shortGloss) ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Other Chinese words for an English one: the dictionary's reverse lookup. */
@Composable
private fun EnglishCandidates(engine: WordBreakdown, word: String) {
    val candidates by produceState<List<WordBreakdown.Candidate>?>(null, word) {
        value = withContext(Dispatchers.Default) { engine.englishCandidates(word) }
    }
    val list = candidates ?: return
    if (list.isEmpty()) return
    Label("Chinese words for “$word”")
    list.forEach { c ->
        ScoreRow(
            title = buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 20.sp)) { append(c.word) }
                append("  ")
                append(tonedPinyin(c.numbered, c.pinyin))
            },
            detail = c.gloss,
            score = c.score,
            color = MaterialTheme.colorScheme.tertiary,
            matched = false,
        )
    }
}

@Composable
private fun ScoreRow(title: String, detail: String?, score: Double, color: Color, matched: Boolean) =
    ScoreRow(AnnotatedString(title), detail, score, color, matched)

/** A ranked option: what it is, a bar for its likelihood, and the percentage. */
@Composable
private fun ScoreRow(title: AnnotatedString, detail: String?, score: Double, color: Color, matched: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (matched) {
                Icon(Icons.Default.Check, contentDescription = "Used in the translation", tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (matched) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                percent(score),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.padding(top = 3.dp).fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        ) {
            Box(
                Modifier.fillMaxWidth(score.toFloat().coerceIn(0.02f, 1f)).height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color.copy(alpha = if (matched) 1f else 0.7f)),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

private const val MAX_SENSES = 8

private fun percent(score: Double): String = when {
    score >= 0.995 -> "100%"
    score < 0.01 -> "<1%"
    else -> "${(score * 100).roundToInt()}%"
}

/** The first meaning of a gloss, without notes like "(bound form)". */
private fun shortGloss(gloss: String): String =
    gloss.substringBefore(';').replace(Regex("\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim().ifEmpty { gloss }

/** Colours that tie a Chinese word to the English words it became; readable on light and dark. */
private val LinkColors = listOf(
    Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF6D4C41),
)

/** Tone colours, the common convention: 1 red, 2 green, 3 blue, 4 purple, neutral grey. */
private val ToneColors = mapOf(
    1 to Color(0xFFE53935), 2 to Color(0xFF43A047), 3 to Color(0xFF1E88E5), 4 to Color(0xFF8E24AA),
)

/** Pinyin with each syllable in its tone's colour. */
@Composable
internal fun tonedPinyin(numbered: String?, marked: String?): AnnotatedString {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    if (numbered == null || marked == null) return AnnotatedString(marked ?: "")
    val tones = numbered.trim().split(Regex("\\s+")).map(Pinyin::tone)
    val syllables = marked.trim().split(Regex("\\s+"))
    if (tones.size != syllables.size) return AnnotatedString(marked)
    return buildAnnotatedString {
        syllables.forEachIndexed { i, s ->
            if (i > 0) append(' ')
            withStyle(SpanStyle(color = ToneColors[tones[i]] ?: neutral)) { append(s) }
        }
    }
}
