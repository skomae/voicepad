package com.voicepad.app.conversation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.voicepad.app.RecordingController
import com.voicepad.app.audio.AudioRecorder
import com.voicepad.app.data.Note
import com.voicepad.app.data.NoteStore
import com.voicepad.app.data.NoteTranslation
import com.voicepad.app.data.RevisionSource
import com.voicepad.app.data.TranscriptionStatus
import com.voicepad.app.data.TranslationStatus
import com.voicepad.app.data.withTranscription
import com.voicepad.app.transcription.ChunkPlanner.Span
import com.voicepad.app.transcription.EnglishTranslator
import com.voicepad.app.transcription.SpeechDetector
import com.voicepad.app.speech.SpeechOutput
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Conversation mode: live two-way translation between two languages, shown
 * in big text, with each turn labelled by who said it.
 *
 * - Each finished utterance (found by the voice detector at its pause) is
 *   transcribed with the model notes use, assigned to one of the two
 *   languages, and translated into the other; the utterance still being
 *   spoken is previewed every [TICK_MS].
 * - **Several microphone channels** (a stereo wireless kit with one
 *   transmitter per person, a multi-channel USB interface, or the phone's
 *   own pair of mics) each get their own voice detector; each channel is one
 *   speaker, and speech leaking into the other mics is dropped where it is
 *   quieter ([ChannelAttribution]).
 * - **One channel**: voices are told apart by their sound ([SpeakerEmbedder]
 *   + [SpeakerClustering]), or, without the voice model, by language.
 *
 * Every listening session is also saved as a note (audio plus a speaker-
 * labelled transcript with translations), so nothing is lost.
 */
class ConversationController(
    private val context: Context,
    private val notes: RecordingController,
    val speech: SpeechOutput,
) {
    data class Settings(
        val myLanguage: String = "en",
        val theirLanguage: String = "zh",
        /** Microphone channels to record; 1 tells voices apart by sound. */
        val channels: Int = 1,
        val layout: ConversationLayout = ConversationLayout.FACE_TO_FACE,
        /** Main text size in sp. */
        val textSize: Int = 34,
        /** With one channel, tell speakers apart by voice (downloads a small model). */
        val identifyVoices: Boolean = true,
        /** Open conversation mode when a foldable phone is unfolded. */
        val openOnUnfold: Boolean = false,
        /** Read each translation aloud once its turn is finished. */
        val autoSpeak: Boolean = false,
    ) {
        val pair: Pair<String, String> get() = myLanguage to theirLanguage
    }

    sealed interface Readiness {
        data object Off : Readiness
        /** [progress] 0..1 while downloading, null while loading. */
        data class Preparing(val progress: Double? = null) : Readiness
        data object Ready : Readiness
        data class Failed(val message: String) : Readiness
    }

    /** Whether the outer (rear) screen of a foldable can show the other person's side. */
    enum class OuterScreen {
        /** Not a foldable, or the phone can't show content there while unfolded. */
        UNSUPPORTED,
        /** Supported, but not right now (folded, or the phone is busy with it). */
        UNAVAILABLE,
        AVAILABLE,
        /** Showing the other person's side. */
        SHOWING,
    }

    data class UiState(
        val visible: Boolean = false,
        val outerScreen: OuterScreen = OuterScreen.UNSUPPORTED,
        /** Layout used instead of the chosen one for now (the outer screen, when unfolding opened this). */
        val layoutOverride: ConversationLayout? = null,
        val listening: Boolean = false,
        val settings: Settings = Settings(),
        val turns: List<Turn> = emptyList(),
        /** Speech in progress: not final, may still change. */
        val live: Turn? = null,
        /** Channels actually being recorded. */
        val recordingChannels: Int = 1,
        /** The channels carry the same sound, so voices are told apart by sound instead. */
        val channelsIdentical: Boolean = false,
        val translation: Readiness = Readiness.Off,
        val voiceModel: Readiness = Readiness.Off,
        val message: String? = null,
        /**
         * The app closed while recording this many channels (a crash, most
         * likely): ask whether to go back to one before recording again.
         */
        val crashedWithChannels: Int? = null,
    ) {
        val layout: ConversationLayout get() = layoutOverride ?: settings.layout

        /** How speakers are being told apart right now. */
        val speakerSource: SpeakerSource
            get() = when {
                recordingChannels > 1 && !channelsIdentical -> SpeakerSource.CHANNEL
                settings.identifyVoices && voiceModel == Readiness.Ready -> SpeakerSource.VOICE
                else -> SpeakerSource.LANGUAGE
            }
    }

    companion object {
        private const val TICK_MS = 500L
        private const val SR = AudioRecorder.SAMPLE_RATE
        /** Context kept either side of detected speech. */
        private const val PAD = SR / 4
        /** Longest preview of speech in progress. */
        private const val MAX_LIVE = SR * 20
        /** Average dB between the loudest and next channel below which channels count as identical. */
        private const val IDENTICAL_DB = 1.5f

        /** Identifies a turn read out in one language, for the speaker icons. */
        fun speechId(turnId: Long, language: String) = "$turnId:$language"

        /** Who said [turn], for labels and the saved transcript. */
        fun speakerLabel(turn: Turn): String = speakerLabel(turn.speaker, turn.language)

        fun speakerLabel(speaker: SpeakerRef?, language: String): String = when (speaker?.source) {
            null -> LanguageNames.native(language)
            SpeakerSource.CHANNEL -> "Mic ${speaker.index + 1}"
            SpeakerSource.VOICE -> "Speaker ${speaker.index + 1}"
            SpeakerSource.LANGUAGE -> LanguageNames.native(language)
        }
    }

    private val prefs = context.getSharedPreferences("conversation", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioRecorder()
    private val translator by lazy { EnglishTranslator() }
    private val store: NoteStore get() = notes.store

    private val _state = MutableStateFlow(
        UiState(
            settings = loadSettings(),
            // Still marked from last time: it never stopped cleanly.
            crashedWithChannels = prefs.getInt("multiChannelRecording", 0).takeIf { it > 1 },
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val settings get() = _state.value.settings

    /** Live level 0..1 of each recorded channel, polled by the UI. */
    val channelLevels: FloatArray get() = recorder.channelLevels

    // region Settings

    private fun loadSettings() = Settings(
        myLanguage = prefs.getString("myLanguage", null) ?: "en",
        theirLanguage = prefs.getString("theirLanguage", null) ?: "zh",
        channels = prefs.getInt("channels", 1),
        layout = prefs.getString("layout", null)?.let { runCatching { ConversationLayout.valueOf(it) }.getOrNull() }
            ?: ConversationLayout.FACE_TO_FACE,
        textSize = prefs.getInt("textSize", 34),
        identifyVoices = prefs.getBoolean("identifyVoices", true),
        openOnUnfold = prefs.getBoolean("openOnUnfold", false),
        autoSpeak = prefs.getBoolean("autoSpeak", false),
    )

    private fun saveSettings(s: Settings) = prefs.edit {
        putString("myLanguage", s.myLanguage)
        putString("theirLanguage", s.theirLanguage)
        putInt("channels", s.channels)
        putString("layout", s.layout.name)
        putInt("textSize", s.textSize)
        putBoolean("identifyVoices", s.identifyVoices)
        putBoolean("openOnUnfold", s.openOnUnfold)
        putBoolean("autoSpeak", s.autoSpeak)
    }

    private fun changeSettings(transform: (Settings) -> Settings): Settings {
        val updated = transform(settings)
        saveSettings(updated)
        _state.update { it.copy(settings = updated) }
        return updated
    }

    /** Sets the two languages; picking the other side's language swaps them. */
    fun setLanguages(mine: String, theirs: String) {
        val (a, b) = if (mine == theirs) settings.theirLanguage to settings.myLanguage else mine to theirs
        changeSettings { it.copy(myLanguage = a, theirLanguage = b) }
        if (_state.value.visible) prepareTranslation()
    }

    fun swapLanguages() = setLanguages(settings.theirLanguage, settings.myLanguage)

    /** Records [channels] mic channels from now on (restarts listening if it's on). */
    fun setChannels(channels: Int) {
        if (channels == settings.channels) return
        changeSettings { it.copy(channels = channels) }
        if (_state.value.listening) {
            stopListening()
            startListening()
        }
    }

    /** Switches to the next layout (the control strip's layout button); returns it. */
    fun cycleLayout(): ConversationLayout {
        val next = _state.value.layout.next(_state.value.outerScreen != OuterScreen.UNSUPPORTED)
        setLayout(next)
        return next
    }

    fun setLayout(layout: ConversationLayout) {
        layoutChosen = true
        changeSettings { it.copy(layout = layout) }
        _state.update { it.copy(layoutOverride = null) }
    }

    /** Reported by the activity as the outer screen comes and goes. */
    fun setOuterScreen(status: OuterScreen) {
        if (status == _state.value.outerScreen) return
        // The outer screen often reports in only after the unfold that opened this.
        val useIt = status == OuterScreen.AVAILABLE && openedByUnfold && _state.value.visible && !layoutChosen
        _state.update { it.copy(outerScreen = status, layoutOverride = if (useIt) ConversationLayout.OUTER_SCREEN else it.layoutOverride) }
    }

    fun setTextSize(size: Int) { changeSettings { it.copy(textSize = size.coerceIn(18, 96)) } }

    fun setIdentifyVoices(enabled: Boolean) {
        changeSettings { it.copy(identifyVoices = enabled) }
        if (enabled && _state.value.visible) ensureVoiceModel()
    }

    fun setOpenOnUnfold(enabled: Boolean) { changeSettings { it.copy(openOnUnfold = enabled) } }

    fun setAutoSpeak(enabled: Boolean) {
        // Only what's said from now on is read out, not the conversation so far.
        if (enabled) spokenTurns += _state.value.turns.map { it.id }
        changeSettings { it.copy(autoSpeak = enabled) }
        if (!enabled) speech.stop()
    }

    /** Says [turn] in [language] (its original or its translation); tapping again stops it. */
    fun speak(turn: Turn, language: String) {
        val text = turn.textIn(language) ?: return
        speech.say(speechId(turn.id, language), text, language)
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun showMessage(message: String) = _state.update { it.copy(message = message) }

    /** Clears the screen (everything is already saved as notes). */
    fun clearTurns() = _state.update { it.copy(turns = emptyList(), live = null) }

    // endregion

    // region Opening, closing, folding

    private var openedByUnfold = false
    /** A layout was picked since conversation mode opened, so don't switch it automatically. */
    private var layoutChosen = false
    private var pausedInBackground = false
    /** Last fold state seen; null until the first reading after launch. */
    private var lastUnfolded: Boolean? = null

    fun open(byUnfold: Boolean = false) {
        if (_state.value.visible) return
        openedByUnfold = byUnfold
        layoutChosen = false
        // Speakers are numbered afresh for each conversation (pausing keeps them).
        voices.reset()
        lastVoiceFor.clear()
        notes.enterConversation()
        // Unfolded with an outer screen to spare: the other person gets it.
        val outer = byUnfold && _state.value.outerScreen != OuterScreen.UNSUPPORTED
        _state.update { it.copy(visible = true, layoutOverride = if (outer) ConversationLayout.OUTER_SCREEN else null) }
        prepareTranslation()
        if (settings.identifyVoices) ensureVoiceModel()
        startListening()
    }

    fun close() {
        if (!_state.value.visible) return
        openedByUnfold = false
        pausedInBackground = false
        stopListening()
        _state.update { it.copy(visible = false, live = null, message = null) }
        speech.stop()
        notes.leaveConversation()
    }

    /**
     * Called with the window's fold state whenever it changes. Unfolding opens
     * conversation mode (if that's switched on); folding again closes it if
     * unfolding opened it.
     */
    fun onFoldStateChanged(unfolded: Boolean) {
        val previous = lastUnfolded
        lastUnfolded = unfolded
        if (previous == null || previous == unfolded) return
        if (unfolded && settings.openOnUnfold && !_state.value.visible) open(byUnfold = true)
        if (!unfolded && openedByUnfold && _state.value.visible) close()
    }

    /** The app went to the background: Android stops giving it microphone audio, so pause. */
    fun onAppStopped() {
        if (_state.value.visible && _state.value.listening) {
            pausedInBackground = true
            stopListening()
        }
    }

    fun onAppStarted() {
        if (_state.value.visible && pausedInBackground) {
            pausedInBackground = false
            startListening()
        }
    }

    // endregion

    // region Listening

    /** One microphone channel with its own voice detector. */
    private inner class Lane(val channel: Int) {
        val detector: SpeechDetector? = runCatching { SpeechDetector(context.assets) }.getOrNull()
        var detectedUpTo = 0
        /** Last position the detector heard no speech; the current utterance began after it. */
        var quietAt = 0
        /** End of the last finished utterance. */
        var finishedUpTo = 0
        val pending = ArrayDeque<Span>()
    }

    /** One listening session: one recording, one saved note. */
    private inner class Session(val number: Int, val noteId: String, val lanes: List<Lane>) {
        val startedAt = System.currentTimeMillis()
        var silenceReported = false
    }

    private var session: Session? = null
    private var sessionCount = 0
    private var loopJob: Job? = null
    /** Previous session still finishing (its audio lives in [recorder] until then). */
    private var finishingJob: Job? = null
    private var nextTurnId = 1L
    private val translationJobs = mutableMapOf<Long, Job>()
    private var liveTranslateJob: Job? = null
    private var separationTotal = 0f
    private var separationCount = 0
    /** Turns already read aloud (or that shouldn't be). */
    private val spokenTurns = mutableSetOf<Long>()
    /**
     * Stretches of the recording during which the phone was talking. Speech
     * found there is the phone's own voice coming back through the mic.
     */
    private val muted = mutableListOf<IntRange>()
    private var mutedFrom: Int? = null

    init {
        scope.launch {
            speech.state.map { it.playing }.distinctUntilChanged().collect { playing ->
                if (session == null) return@collect
                val now = recorder.sampleCount
                if (playing) {
                    mutedFrom = now
                } else {
                    // The room keeps echoing for a moment after the voice stops.
                    muted += (mutedFrom ?: now)..(now + SR * 3 / 10)
                    mutedFrom = null
                }
            }
        }
    }

    private fun isEcho(span: Span): Boolean {
        val from = mutedFrom
        if (from != null && span.end > from) return true
        return muted.any { span.start < it.last && span.end > it.first }
    }

    fun toggleListening() = if (_state.value.listening) stopListening() else startListening()

    /**
     * Answer to "VoicePad closed while recording N channels — switch back to
     * one?": [useOneChannel] switches; either way, listening (re)starts.
     */
    fun answerCrashPrompt(useOneChannel: Boolean) {
        prefs.edit { remove("multiChannelRecording") }
        _state.update { it.copy(crashedWithChannels = null) }
        if (useOneChannel) changeSettings { it.copy(channels = 1) }
        if (_state.value.visible) startListening()
    }

    /** Marks a multi-channel recording as running, written to disk at once so a crash can't lose it. */
    private fun markMultiChannel(channels: Int?) {
        prefs.edit(commit = true) { if (channels != null && channels > 1) putInt("multiChannelRecording", channels) else remove("multiChannelRecording") }
    }

    fun startListening() {
        if (_state.value.listening || !_state.value.visible) return
        // Don't risk the same crash again before the user has decided.
        if (_state.value.crashedWithChannels != null && settings.channels > 1) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showMessage("Microphone access is needed for conversation mode.")
            return
        }
        _state.update { it.copy(listening = true) }
        val previous = finishingJob
        scope.launch {
            // The recorder still holds the previous session's audio until it's done.
            previous?.join()
            if (_state.value.listening && session == null) begin()
        }
    }

    private fun begin() {
        val wanted = settings.channels
        val fileName = "${UUID.randomUUID()}.wav"
        val file = File(store.recordingsDir, fileName)
        val device = notes.beginConversationInput()
        try {
            try {
                recorder.start(file, device, sourceFor(wanted), wanted)
            } catch (e: Throwable) {
                if (wanted == 1) throw e
                showMessage("${e.message ?: "This microphone can't record $wanted channels."} Recording 1 channel.")
                recorder.start(file, device, sourceFor(1), 1)
            }
        } catch (e: Throwable) {
            notes.endConversationInput()
            _state.update { it.copy(listening = false) }
            showMessage(e.message ?: "Could not start the microphone.")
            return
        }
        val got = recorder.channelCount
        markMultiChannel(got)
        if (got < wanted && _state.value.message == null) {
            showMessage("The microphone gave $got channel${if (got == 1) "" else "s"} instead of $wanted.")
        }
        separationTotal = 0f
        separationCount = 0
        if (got == 1 && settings.identifyVoices) ensureVoiceModel()

        val note = Note(audioFileName = fileName, status = TranscriptionStatus.RECORDING)
        store.add(note)
        val s = Session(++sessionCount, note.id, List(got) { Lane(it) })
        session = s
        muted.clear()
        mutedFrom = if (speech.state.value.playing) 0 else null
        _state.update { it.copy(recordingChannels = got, channelsIdentical = false, live = null) }
        loopJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                try {
                    step(s, final = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // While the model is being switched, calls fail; that's not worth a message.
                    if (notes.isEngineReady) showMessage("Transcription failed: ${e.message ?: e}")
                }
            }
        }
    }

    /** Close talk for one channel; several channels need a source that keeps them apart. */
    private fun sourceFor(channels: Int) =
        if (channels == 1) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.CAMCORDER

    fun stopListening() {
        if (!_state.value.listening) return
        _state.update { it.copy(listening = false, live = null) }
        liveTranslateJob?.cancel()
        val s = session ?: return
        session = null
        recorder.stop()
        markMultiChannel(null) // stopped cleanly
        notes.endConversationInput()
        val job = loopJob
        loopJob = null
        finishingJob = scope.launch {
            job?.cancelAndJoin()
            try {
                if (notes.isEngineReady) step(s, final = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showMessage("Transcription failed: ${e.message ?: e}")
            }
            // Give translations of the last turns a moment to land in the note.
            withTimeoutOrNull(15_000) { translationJobs.values.toList().joinAll() }
            save(s)
            s.lanes.forEach { it.detector?.release() }
        }
    }

    /**
     * Finds newly finished utterances on every channel and turns them into
     * turns, oldest first; then previews speech still in progress.
     */
    private suspend fun step(s: Session, final: Boolean) {
        recorder.captureError?.let { error ->
            if (_state.value.message != error) showMessage(error)
        }
        val now = recorder.sampleCount
        // Some phones accept a stereo recording and then deliver nothing.
        if (!final && now == 0 && !s.silenceReported && System.currentTimeMillis() - s.startedAt > 3000) {
            s.silenceReported = true
            showMessage(
                if (_state.value.recordingChannels > 1) "No sound is coming from the microphone in ${_state.value.recordingChannels} channels — switch to 1 channel in settings."
                else "No sound is coming from the microphone."
            )
        }
        for (lane in s.lanes) feed(lane, now, final)
        if (!notes.isEngineReady) return // audio waits in memory (and on disk) until the model is ready
        while (true) {
            val lane = s.lanes.filter { it.pending.isNotEmpty() }.minByOrNull { it.pending.first().start } ?: break
            val span = lane.pending.removeFirst()
            lane.finishedUpTo = maxOf(lane.finishedUpTo, span.end)
            finishUtterance(s, lane.channel, span)
        }
        if (!final && session === s) {
            if (!speech.state.value.playing) updateLive(s, now)
            if (settings.autoSpeak) speakSettled(s, now)
        }
        // Audio still needed: utterances waiting for the model, and speech in progress.
        // (A channel nobody speaks on needs nothing, so it doesn't hold memory.)
        val keep = s.lanes.minOf { lane ->
            lane.pending.firstOrNull()?.start
                // Speech in progress, but never more than the live preview reads: a noisy mic
                // can keep its detector "in speech" for good, which would hold all audio in memory.
                ?: if (lane.detector?.inSpeech == true) maxOf(lane.quietAt - SR, now - MAX_LIVE - SR) else lane.detectedUpTo
        }
        recorder.discardBefore(keep - PAD - SR)
    }

    private fun feed(lane: Lane, now: Int, final: Boolean) {
        val vad = lane.detector
        if (vad == null) {
            // No detector: fixed four-second pieces of the first channel.
            if (lane.channel == 0 && now > lane.detectedUpTo && (final || now - lane.detectedUpTo >= 4 * SR)) {
                lane.pending += Span(lane.detectedUpTo, now)
                lane.detectedUpTo = now
            }
            return
        }
        if (now > lane.detectedUpTo) {
            // The detector counts from the first sample it was given: the recording's start.
            lane.pending += vad.feed(recorder.channelSamples(lane.channel, lane.detectedUpTo, now))
            lane.detectedUpTo = now
            if (!vad.inSpeech) lane.quietAt = now
        }
        if (final) lane.pending += vad.flush().map { Span(it.start, minOf(it.end, now)) }
    }

    private val multiChannel get() = _state.value.recordingChannels > 1

    /** Levels per channel over [span], frame by frame. */
    private fun levels(span: Span) = (0 until _state.value.recordingChannels).map {
        ChannelAttribution.frameLevels(recorder.channelSamples(it, span.start, span.end))
    }

    private suspend fun finishUtterance(s: Session, channel: Int, span: Span) {
        if (isEcho(span)) return
        if (multiChannel) {
            val levels = levels(span)
            ChannelAttribution.separationDb(levels)?.let(::trackSeparation)
            // The same speech, heard louder on another mic: that one keeps it.
            if (!ChannelAttribution.owns(levels, channel)) return
        }
        val audio = recorder.channelSamples(channel, maxOf(0, span.start - PAD), span.end + PAD)
        val transcript = notes.transcribeForConversation(audio)
        val text = transcript.text.trim()
        if (text.isEmpty()) return

        val pair = settings.pair
        val previous = _state.value.turns.lastOrNull { it.session == s.number }
        val byChannel = multiChannel && !_state.value.channelsIdentical
        val channelSpeaker = SpeakerRef(SpeakerSource.CHANNEL, channel).takeIf { byChannel }
        // A channel's speaker usually keeps to one language; otherwise people take turns.
        val fallback = channelSpeaker?.let { ref -> _state.value.turns.lastOrNull { it.speaker == ref }?.language }
            ?: previous?.let { ConversationLanguages.target(pair, it.language) }
        val language = ConversationLanguages.spoken(pair, transcript.language, translator.languageCandidates(text), text, fallback)
        val speaker = channelSpeaker ?: voiceSpeaker(audio, language, pair)

        val turn = if (
            Turns.continues(previous, s.number, speaker, language, span.start, SR) &&
            previous === _state.value.turns.lastOrNull() &&
            previous?.id !in spokenTurns // already read aloud: what follows is read as its own turn
        ) {
            val joined = Turns.join(previous!!.text, text)
            previous.copy(text = joined, end = span.end, romanization = translator.romanize(language, joined))
        } else {
            Turn(
                id = nextTurnId++, session = s.number, speaker = speaker, language = language, text = text,
                target = ConversationLanguages.target(pair, language),
                romanization = translator.romanize(language, text), start = span.start, end = span.end,
            )
        }
        _state.update { st ->
            val turns = if (st.turns.lastOrNull()?.id == turn.id) st.turns.dropLast(1) + turn else st.turns + turn
            st.copy(turns = turns, live = null)
        }
        liveTranslateJob?.cancel()
        translate(turn.id)
        // Keep the transcript on disk in case the app dies.
        store.update(s.noteId) { it.copy(partialText = transcriptOf(s.number)) }
    }

    /** Most recent voice per language, for utterances too short to fingerprint. */
    private val lastVoiceFor = mutableMapOf<String, SpeakerRef>()
    private val voices = SpeakerClustering()
    private var embedder: SpeakerEmbedder? = null
    private var embedderJob: Job? = null
    private val embedMutex = Mutex()

    /** Who spoke [audio] when there's one channel (or the channels are identical). */
    private suspend fun voiceSpeaker(audio: FloatArray, language: String, pair: Pair<String, String>): SpeakerRef {
        val byLanguage = SpeakerRef(SpeakerSource.LANGUAGE, if (language == pair.first) 0 else 1)
        val model = embedder
        if (!settings.identifyVoices || model == null) return byLanguage
        val embedding = runCatching {
            embedMutex.withLock { withContext(Dispatchers.Default) { model.embed(audio) } }
        }.getOrNull()
        if (embedding == null) return lastVoiceFor[language] ?: byLanguage
        return SpeakerRef(SpeakerSource.VOICE, voices.assign(embedding, language)).also { lastVoiceFor[language] = it }
    }

    /**
     * Reads out translations of turns that are finished: translated, and no
     * longer able to continue (the speaker has been quiet long enough).
     */
    private fun speakSettled(s: Session, now: Int) {
        for (turn in _state.value.turns) {
            if (turn.session != s.number || turn.id in spokenTurns) continue
            val translation = turn.translation ?: continue
            if (turn.id in translationJobs || now - turn.end < Turns.MAX_GAP_SECONDS * SR) continue
            spokenTurns += turn.id
            speech.enqueue(speechId(turn.id, turn.target), translation, turn.target)
        }
    }

    private fun trackSeparation(db: Float) {
        separationTotal += db
        separationCount++
        if (separationCount >= 3) {
            val identical = separationTotal / separationCount < IDENTICAL_DB
            if (identical != _state.value.channelsIdentical) {
                _state.update { it.copy(channelsIdentical = identical) }
                if (identical && settings.identifyVoices) ensureVoiceModel()
            }
        }
    }

    /** Previews what's being said right now on the channel that is speaking (the loudest, if several). */
    private suspend fun updateLive(s: Session, now: Int) {
        val levels = recorder.channelLevels
        val lane = s.lanes.filter { it.detector?.inSpeech == true }.maxByOrNull { levels.getOrElse(it.channel) { 0f } }
        if (lane == null) {
            if (_state.value.live != null) _state.update { it.copy(live = null) }
            return
        }
        // The detector confirms speech ~0.25 s after it starts; back up a second.
        val from = maxOf(lane.quietAt - SR, lane.finishedUpTo, now - MAX_LIVE, 0)
        if (now - from < SR / 2) return
        val result = notes.transcribeForConversation(recorder.channelSamples(lane.channel, from, now))
        val text = result.text.trim()
        if (session !== s || !_state.value.listening) return
        if (text.isEmpty()) return
        val pair = settings.pair
        val speaker = SpeakerRef(SpeakerSource.CHANNEL, lane.channel).takeIf { multiChannel && !_state.value.channelsIdentical }
        val language = ConversationLanguages.spoken(
            pair, result.language, translator.languageCandidates(text), text,
            fallback = speaker?.let { ref -> _state.value.turns.lastOrNull { it.speaker == ref }?.language },
        )
        val old = _state.value.live
        val live = Turn(
            id = -1, session = s.number, speaker = speaker, language = language, text = text,
            target = ConversationLanguages.target(pair, language),
            // Keep showing the previous translation until the new one arrives.
            translation = old?.translation?.takeIf { old.language == language },
            romanization = translator.romanize(language, text), start = from, end = now,
        )
        _state.update { it.copy(live = live) }
        if (old?.text != text) {
            liveTranslateJob?.cancel()
            liveTranslateJob = scope.launch {
                delay(200) // let quick changes settle
                val translated = runCatching { translator.translate(text, live.language, live.target) }.getOrNull() ?: return@launch
                _state.update { st ->
                    val current = st.live
                    if (current?.text == text) st.copy(live = current.copy(translation = translated)) else st
                }
            }
        }
    }

    /** Translates turn [id] (again, if its text grew) into the other language. */
    private fun translate(id: Long) {
        translationJobs.remove(id)?.cancel()
        val turn = _state.value.turns.firstOrNull { it.id == id } ?: return
        val source = turn.text
        val job = scope.launch {
            val outcome = try {
                Result.success(translator.translate(source, turn.language, turn.target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            _state.update { st ->
                st.copy(turns = st.turns.map { t ->
                    if (t.id != id || t.text != source) t
                    else outcome.fold(
                        onSuccess = { tr -> t.copy(translation = tr, translationFailed = false, translationRomanization = translator.romanize(t.target, tr)) },
                        onFailure = { t.copy(translationFailed = true) },
                    )
                })
            }
            outcome.exceptionOrNull()?.let { showMessage("Translation failed: ${it.message ?: it}") }
        }
        translationJobs[id] = job
        job.invokeOnCompletion { if (translationJobs[id] === job) translationJobs.remove(id) }
    }

    private fun transcriptOf(sessionNumber: Int) =
        Turns.transcript(_state.value.turns.filter { it.session == sessionNumber }, ::speakerLabel)

    /** Saves the session as a note: the speaker-labelled transcript with translations, plus its audio. */
    private fun save(s: Session) {
        val turns = _state.value.turns.filter { it.session == s.number }
        val duration = recorder.sampleCount.toDouble() / SR
        recorder.writeError?.let { showMessage("Part of this recording couldn't be saved: $it") }
        if (turns.isEmpty()) {
            if (!notes.isEngineReady && duration >= NoteStore.MIN_SECONDS) {
                // The model never got ready: keep the audio, it's transcribed like any note.
                store.update(s.noteId) { it.copy(status = TranscriptionStatus.PENDING, durationSeconds = duration) }
            } else {
                store.delete(s.noteId)
            }
            return
        }
        val language = turns.groupingBy { it.language }.eachCount().maxByOrNull { it.value }?.key
        store.update(s.noteId) { note ->
            val done = note.withTranscription(
                transcriptOf(s.number), RevisionSource.TRANSCRIPTION,
                "Conversation · " + notes.state.value.activeEngine.shortName, language,
            ).copy(status = TranscriptionStatus.DONE, durationSeconds = duration, partialText = null, lastError = null)
            // The translations are already in the transcript.
            val revision = done.revisions.lastOrNull()
            if (revision != null) done.copy(translation = NoteTranslation(revision.id, TranslationStatus.NOT_NEEDED)) else done
        }
    }

    // endregion

    // region Models

    /** Downloads the two languages' translation packs in the background. */
    private var prepareJob: Job? = null

    private fun prepareTranslation() {
        prepareJob?.cancel()
        val (a, b) = settings.pair
        prepareJob = scope.launch {
            _state.update { it.copy(translation = Readiness.Preparing()) }
            try {
                translator.prepare(a, b)
                _state.update { it.copy(translation = Readiness.Ready) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(translation = Readiness.Failed(e.message ?: "Couldn't get the translation packs.")) }
            }
        }
    }

    fun retryModels() {
        prepareTranslation()
        if (settings.identifyVoices) ensureVoiceModel()
    }

    private fun ensureVoiceModel() {
        if (embedder != null || embedderJob?.isActive == true) return
        embedderJob = scope.launch {
            val downloading = !SpeakerEmbedder.isInstalled(context)
            _state.update { it.copy(voiceModel = Readiness.Preparing(if (downloading) 0.0 else null)) }
            try {
                var lastPercent = -1
                embedder = SpeakerEmbedder.load(context) { p ->
                    val percent = (p * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        scope.launch { _state.update { it.copy(voiceModel = Readiness.Preparing(p)) } }
                    }
                }
                _state.update { it.copy(voiceModel = Readiness.Ready) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(voiceModel = Readiness.Failed(e.message ?: "Couldn't load the voice model.")) }
            }
        }
    }

    // endregion
}
