package com.voicepad.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.voicepad.app.audio.AudioArchive
import com.voicepad.app.audio.AudioInputs
import com.voicepad.app.audio.AudioRecorder
import com.voicepad.app.audio.InputDevice
import com.voicepad.app.audio.RecordingService
import com.voicepad.app.audio.WavFile
import com.voicepad.app.data.AutoSplit
import com.voicepad.app.data.Note
import com.voicepad.app.data.NoteCleanup
import com.voicepad.app.data.NoteStore
import com.voicepad.app.data.NoteTranslation
import com.voicepad.app.data.RevisionSource
import com.voicepad.app.data.TranscriptionStatus
import com.voicepad.app.data.TextSimilarity
import com.voicepad.app.data.TranslationJoin
import com.voicepad.app.data.TranslationStatus
import com.voicepad.app.data.withTranscription
import com.voicepad.app.llm.DeviceSpecs
import com.voicepad.app.llm.LlamaEngine
import com.voicepad.app.llm.LlmAdvice
import com.voicepad.app.llm.LlmChoice
import com.voicepad.app.llm.LlmEngine
import com.voicepad.app.llm.LlmGuidance
import com.voicepad.app.llm.LlmModelManager
import com.voicepad.app.llm.LlmPrompt
import com.voicepad.app.llm.createLlmEngine
import com.voicepad.app.transcription.AudioLevel
import com.voicepad.app.transcription.AutoEngine
import com.voicepad.app.transcription.ChunkPlanner
import com.voicepad.app.transcription.ChunkPlanner.Span
import com.voicepad.app.transcription.EngineChoice
import com.voicepad.app.transcription.EnglishTranslator
import com.voicepad.app.transcription.LanguageTags
import com.voicepad.app.transcription.ModelManager
import com.voicepad.app.transcription.SherpaEngine
import com.voicepad.app.transcription.SpeechDetector
import com.voicepad.app.transcription.SpeechEngine
import com.voicepad.app.transcription.Transcript
import com.voicepad.app.transmit.KeyboardHost
import com.voicepad.app.transmit.ScanState
import com.voicepad.app.transmit.SinkStatus
import com.voicepad.app.transmit.createKeyboardSink
import com.voicepad.app.ui.NoteTapAction
import com.voicepad.app.ui.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray

/**
 * Owns the recorder and the speech engine and turns audio into text.
 *
 * Recordings are never thrown away because a model failed. Every note carries
 * a saved [TranscriptionStatus]; the audio stays on disk until it has text,
 * and notes that still need text are retried automatically whenever a model
 * becomes ready (up to [MAX_AUTO_ATTEMPTS] times, so a model that crashes the
 * app can't trap it in a loop) or on demand from the UI.
 *
 * Audio reaches the model in pieces chosen by a voice-activity detector
 * (Silero): silence is skipped, and pieces end at pauses (see [ChunkPlanner]).
 * While recording, each finished utterance is transcribed and committed as
 * soon as the pause after it is detected, and the utterance still being
 * spoken is re-transcribed every [TICK_MS] for the live preview. Committed
 * audio is dropped from memory (it is in the WAV), and saved recordings are
 * read from disk piece by piece, so length is limited only by storage.
 */
class RecordingController(
    private val context: Context,
    val store: NoteStore,
) {
    sealed interface EngineStatus {
        data object Idle : EngineStatus
        data class Loading(val progress: Double, val downloading: Boolean) : EngineStatus
        data object Ready : EngineStatus
        data class Failed(val message: String) : EngineStatus
    }

    sealed interface LlmStatus {
        data object Idle : LlmStatus
        data class Loading(val progress: Double, val downloading: Boolean) : LlmStatus
        data object Ready : LlmStatus
        data class Failed(val message: String) : LlmStatus
    }

    data class UiState(
        val isRecording: Boolean = false,
        val startedAt: Long? = null,
        val liveText: String = "",
        val recordingNoteId: String? = null,
        val engineStatus: EngineStatus = EngineStatus.Idle,
        val engineChoice: EngineChoice = EngineChoice.PARAKEET_V3,
        /** Background voices (announcements, TV): room microphone, louder speech, Whisper turbo. */
        val backgroundVoices: Boolean = false,
        /** Light, dark, pure black (OLED), or follow the phone. */
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        /** What a single tap on a note bubble does. */
        val noteTapAction: NoteTapAction = NoteTapAction.EDIT,
        /** Half-folded with a horizontal hinge: transcript on top, controls below. */
        val tabletopLayout: Boolean = true,
        /** When a recording (or split part) finishes transcribing, copy it to the clipboard (with a toast). */
        val copyWhenDone: Boolean = true,
        /** Start a new note automatically after a silence or every few minutes. */
        val autoSplit: AutoSplit = AutoSplit.OFF,
        /** Models already on the phone, for the quick switcher. */
        val installedEngines: Set<EngineChoice> = emptySet(),
        val autoRecordOnOpen: Boolean = true,
        val autoTranslate: Boolean = true,
        val errorMessage: String? = null,
        /** Microphones currently connected. */
        val inputDevices: List<InputDevice> = emptyList(),
        /** Chosen microphone's key; null means the phone picks (automatic). */
        val selectedInputKey: String? = null,
        /** Mic Android is actually recording from right now, if recording. */
        val activeInputName: String? = null,
        /** Translation of the note being recorded, updated as it is spoken. */
        val liveTranslation: NoteTranslation? = null,
        /** Which part of a split recording is being recorded (1 = not split). */
        val partNumber: Int = 1,
        /** When the current recording was paused, or null while it's capturing. */
        val pausedAt: Long? = null,
        /** Stopped or split-off notes still being transcribed (the notification stays until 0). */
        val finishingCount: Int = 0,
        /** Whether the phone is paired as a Bluetooth keyboard (type-as-keyboard). */
        val transmitStatus: SinkStatus = SinkStatus.UNAVAILABLE,
        val transmitError: String? = null,
        /** Whether the optional LLM transform runs before typing. */
        val llmEnabled: Boolean = false,
        val llmStatus: LlmStatus = LlmStatus.Idle,
        val llmChoice: LlmChoice = LlmChoice.QWEN_0_5B,
        /** Instruction the LLM follows before typing (a preset or the user's own). */
        val typePrompt: String = LlmPrompt.CLEANUP.instruction,
        /** User-written prompts, most recent first (presets aren't included). */
        val recentPrompts: List<String> = emptyList(),
        /** Name of the computer the keyboard is connected to. */
        val transmitHost: String? = null,
        /** Text is being transformed and/or typed on the paired host. */
        val isTyping: Boolean = false,
    ) {
        /** The model actually in use: Whisper turbo while background voices is on. */
        val activeEngine: EngineChoice get() = if (backgroundVoices) BACKGROUND_ENGINE else engineChoice
    }

    companion object {
        /** Longest audio one model call gets (Whisper's window is 30 s). */
        private const val MAX_CHUNK_SECONDS = 25.0
        /** With Whisper, utterances are batched until about this long before committing. */
        private const val WHISPER_BATCH_SECONDS = 15.0
        /** Context kept either side of detected speech. */
        private const val PAD_SECONDS = 0.25
        private val MAX_CHUNK = (MAX_CHUNK_SECONDS * AudioRecorder.SAMPLE_RATE).toInt()
        private val PAD = (PAD_SECONDS * AudioRecorder.SAMPLE_RATE).toInt()
        private const val TICK_MS = 1200L
        const val MAX_AUTO_ATTEMPTS = 3
        /**
         * Most robust to distant, noisy speech: in testing with echo and
         * background noise it made 10% word errors where Parakeet made 41%.
         */
        val BACKGROUND_ENGINE = EngineChoice.WHISPER_TURBO
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioRecorder()
    private val models = ModelManager(context)
    private val engineMutex = Mutex()
    private val inputs = AudioInputs(context)
    private val keyboard = createKeyboardSink(context)
    private val llmModels = LlmModelManager(context)
    private val deviceSpecs = DeviceSpecs.read(context)

    private val _state = MutableStateFlow(
        UiState(
            engineChoice = prefs.getString("engine", null)
                ?.let { runCatching { EngineChoice.valueOf(it) }.getOrNull() }
                ?: EngineChoice.PARAKEET_V3,
            autoRecordOnOpen = prefs.getBoolean("autoRecord", true),
            autoTranslate = prefs.getBoolean("autoTranslate", true),
            backgroundVoices = prefs.getBoolean("backgroundVoices", false),
            copyWhenDone = prefs.getBoolean("copyWhenDone", true),
            themeMode = prefs.getString("theme", null)
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            tabletopLayout = prefs.getBoolean("tabletopLayout", true),
            noteTapAction = prefs.getString("noteTapAction", null)
                ?.let { runCatching { NoteTapAction.valueOf(it) }.getOrNull() } ?: NoteTapAction.EDIT,
            autoSplit = prefs.getString("autoSplit", null)
                ?.let { runCatching { AutoSplit.valueOf(it) }.getOrNull() } ?: AutoSplit.OFF,
            inputDevices = inputs.devices.value,
            selectedInputKey = prefs.getString("inputDevice", null),
            llmEnabled = prefs.getBoolean("llmEnabled", false),
            llmChoice = prefs.getString("llmChoice", null)
                ?.let { runCatching { LlmChoice.valueOf(it) }.getOrNull() } ?: LlmChoice.QWEN_0_5B,
            typePrompt = prefs.getString("typePrompt", null)?.takeIf { it.isNotBlank() }
                ?: LlmPrompt.CLEANUP.instruction,
            recentPrompts = prefs.getString("recentPrompts", null)?.let { json ->
                runCatching {
                    val array = JSONArray(json)
                    List(array.length()) { array.getString(it) }
                }.getOrNull()
            }.orEmpty(),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Live input level 0..1, polled by the UI. */
    val level: Float get() = recorder.level

    private var engine: SpeechEngine? = null
    private var loadJob: Job? = null
    private var liveJob: Job? = null
    private var queueJob: Job? = null
    private var translateJob: Job? = null
    /** Notes already attempted since the app was last opened; failures wait for the next open. */
    private val translationTried = mutableSetOf<String>()
    private val translator by lazy { EnglishTranslator() }
    private var llm: LlmEngine? = null
    private var llmLoadJob: Job? = null
    private var typingJob: Job? = null
    private val llmMutex = Mutex()

    /** One finished utterance (or chunk) of a live recording, with its translation. */
    private class LiveSegment(val text: String, val language: String?) {
        /** Language the translator settled on (the model's tag may be missing). */
        var detectedLanguage: String? = null
        var english: String? = null
        var romanization: String? = null
        var translated = false
    }

    /**
     * Live state of one note: the one being recorded, or one still finishing
     * its transcription after Stop or Split. Positions are in the recording
     * (the microphone stream), which runs on across splits.
     */
    private inner class LiveSession(val noteId: String, val origin: Int) {
        var committedText = ""
        var committedUpTo = origin
        /** Recording position fed to the speech detector so far. */
        var detectedUpTo = origin
        /** Last position at which the detector heard no speech; the current utterance began after it. */
        var quietAt = origin
        val detector: SpeechDetector? = newDetector()
        /** Finished utterances not yet committed. */
        val pending = mutableListOf<Span>()
        val languages = mutableListOf<String>()
        val engines = mutableListOf<String>()
        val segments = mutableListOf<LiveSegment>()
        var tail = LiveSegment("", null)
        /** Where this note's audio ends; null while it is still being recorded. */
        var end: Int? = null
        /** Last moment speech was heard in this note (null: nothing said yet). */
        var lastVoiceAt: Int? = null
        /** Ended with Stop (not a split). */
        var stoppedByUser = false
    }

    private var session: LiveSession? = null
    /** Sessions finishing their transcription after Stop or Split. */
    private val finishing = mutableSetOf<LiveSession>()
    private var tailTranslateJob: Job? = null
    private val liveTranslateMutex = Mutex()

    init {
        // Before anything records: repair what a crash or kill left behind.
        store.recoverAfterRestart()
        refreshInstalled()

        inputs.onDevicesChanged = {
            val before = _state.value.inputDevices.map { it.key }.toSet()
            _state.update { it.copy(inputDevices = inputs.devices.value) }
            // The chosen mic was just plugged back in mid-recording: switch to it.
            val key = _state.value.selectedInputKey
            val reconnected = key != null && key !in before && inputs.devices.value.any { it.key == key }
            if (_state.value.isRecording && reconnected) {
                recorder.setPreferredDevice(inputs.beginRecording(key))
            }
        }
        recorder.onRoutingChanged = {
            _state.update { it.copy(activeInputName = recorder.routedDeviceName) }
        }

        // Reflect the Bluetooth keyboard sink's state into the UI.
        scope.launch { keyboard.state.collect { s -> _state.update { it.copy(transmitStatus = s.status, transmitError = s.error, transmitHost = s.host) } } }

        // Translate whenever a note gets new text (transcription, edit, restore).
        scope.launch { store.notes.collect { processTranslations() } }
    }

    // region Microphone

    /** Chooses the mic to record from (null = automatic) and applies it immediately if recording. */
    fun setInputDevice(key: String?) {
        prefs.edit { putString("inputDevice", key) }
        _state.update { it.copy(selectedInputKey = key) }
        if (_state.value.isRecording) recorder.setPreferredDevice(inputs.beginRecording(key))
    }

    // endregion

    // region Engine

    fun setEngineChoice(choice: EngineChoice) {
        if (choice == _state.value.engineChoice) return
        prefs.edit { putString("engine", choice.name) }
        _state.update { it.copy(engineChoice = choice) }
        // While background voices is on, Whisper turbo stays in use.
        if (!_state.value.backgroundVoices) loadEngine()
    }

    fun setAutoRecord(enabled: Boolean) {
        prefs.edit { putBoolean("autoRecord", enabled) }
        _state.update { it.copy(autoRecordOnOpen = enabled) }
    }

    /**
     * Background voices on: switches the microphone to room capture right
     * away (even mid-recording), normalises loudness, and loads Whisper turbo.
     */
    fun setBackgroundVoices(enabled: Boolean) {
        if (enabled == _state.value.backgroundVoices) return
        prefs.edit { putBoolean("backgroundVoices", enabled) }
        _state.update { it.copy(backgroundVoices = enabled) }
        if (_state.value.isRecording) {
            recorder.switchSource(AudioRecorder.sourceFor(context, enabled))
            session?.let { s -> if (enabled) store.update(s.noteId) { it.copy(backgroundVoices = true) } }
        }
        loadEngine()
    }

    /**
     * Quick switcher: use [choice] from now on. Picking a regular model turns
     * background voices off; picking the background model turns it on.
     */
    fun selectModel(choice: EngineChoice) {
        if (choice == BACKGROUND_ENGINE) {
            setBackgroundVoices(true)
            return
        }
        prefs.edit { putString("engine", choice.name) }
        _state.update { it.copy(engineChoice = choice) }
        if (_state.value.backgroundVoices) setBackgroundVoices(false) else loadEngine()
    }

    private fun refreshInstalled() =
        _state.update { s -> s.copy(installedEngines = EngineChoice.entries.filter(models::isInstalled).toSet()) }

    fun loadEngine() {
        val choice = _state.value.activeEngine
        loadJob?.cancel()
        loadJob = scope.launch {
            val old = engineMutex.withLock { engine.also { engine = null } }
            old?.release()
            val downloading = !models.isInstalled(choice)
            setStatus(EngineStatus.Loading(0.0, downloading))
            try {
                models.install(choice) { p ->
                    scope.launch { if (_state.value.activeEngine == choice) setStatus(EngineStatus.Loading(p, true)) }
                }
                setStatus(EngineStatus.Loading(1.0, false))
                val loaded = withContext(Dispatchers.Default) { createEngine(choice) }
                engineMutex.withLock { engine = loaded }
                setStatus(EngineStatus.Ready)
                refreshInstalled()
                processQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A broken download should not stick around.
                models.discard(choice)
                setStatus(EngineStatus.Failed(e.message ?: e.toString()))
            }
        }
    }

    private fun createEngine(choice: EngineChoice): SpeechEngine =
        if (choice.kind == EngineChoice.Kind.AUTO) {
            AutoEngine(
                parakeet = SherpaEngine(EngineChoice.PARAKEET_V3, models.directory(EngineChoice.PARAKEET_V3)),
                senseVoice = SherpaEngine(EngineChoice.SENSE_VOICE, models.directory(EngineChoice.SENSE_VOICE)),
            )
        } else {
            SherpaEngine(choice, models.directory(choice))
        }

    /** The bundled voice-activity detector, or null if it can't load (then whole stretches are used). */
    private fun newDetector(): SpeechDetector? =
        runCatching { SpeechDetector(context.assets) }.getOrNull()

    private val grouping get() = engine?.grouping ?: ChunkPlanner.Grouping.UTTERANCES

    private fun setStatus(status: EngineStatus) = _state.update { it.copy(engineStatus = status) }

    private val engineReady get() = _state.value.engineStatus == EngineStatus.Ready

    /** Runs one model call at a time on a background thread. */
    private suspend fun transcribe(samples: FloatArray, normalize: Boolean = _state.value.backgroundVoices): Transcript = engineMutex.withLock {
        val current = engine ?: throw IllegalStateException("The model isn't loaded.")
        withContext(Dispatchers.Default) {
            current.transcribe(if (normalize) AudioLevel.normalize(samples) else samples)
        }
    }

    // endregion

    // region Recording

    /** Called whenever the app comes to the foreground. */
    fun appBecameActive() {
        translationTried.clear()
        processTranslations()
        if (engine == null && loadJob?.isActive != true) loadEngine() else processQueue()
        val s = _state.value
        if (s.autoRecordOnOpen && !s.isRecording && !conversationActive) start()
    }

    fun toggle() {
        if (_state.value.isRecording) stop() else start()
    }

    fun start() {
        val s = _state.value
        if (s.isRecording || conversationActive) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showError("Microphone access is needed to record.")
            return
        }
        val fileName = "${UUID.randomUUID()}.wav"
        try {
            // A chosen mic that isn't connected falls back to the phone's default.
            val preferred = inputs.beginRecording(s.selectedInputKey)
            recorder.paused = false
            recorder.start(File(store.recordingsDir, fileName), preferred, AudioRecorder.sourceFor(context, s.backgroundVoices))
        } catch (e: Throwable) {
            inputs.endRecording()
            showError(e.message ?: "Could not start recording.")
            return
        }
        RecordingService.start(context)
        beginNote(fileName, origin = 0, part = 1)
        _state.update { it.copy(isRecording = true, errorMessage = null) }
        liveJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val current = session ?: continue
                if (engineReady) processLive(current) else feedDetector(current, recorder.sampleCount, final = false)
                maybeAutoSplit(current)
            }
        }
    }

    /** Creates the note (saved before any audio arrives, so a crash can always find it) and its live session. */
    private fun beginNote(fileName: String, origin: Int, part: Int) {
        val note = Note(
            audioFileName = fileName,
            status = TranscriptionStatus.RECORDING,
            backgroundVoices = _state.value.backgroundVoices,
        )
        store.add(note)
        session = LiveSession(note.id, origin)
        tailTranslateJob?.cancel()
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                startedAt = now,
                // A split while paused starts the new part paused too.
                pausedAt = it.pausedAt?.let { now },
                liveText = "",
                liveTranslation = null,
                recordingNoteId = note.id,
                partNumber = part,
            )
        }
    }

    /**
     * Like a stopwatch lap: ends the current note and starts a new one at this
     * exact moment. The microphone keeps running, so nothing is lost between
     * the two; the finished note transcribes in the background.
     */
    fun split() {
        val old = session ?: return
        if (!_state.value.isRecording) return
        val fileName = "${UUID.randomUUID()}.wav"
        val at = try {
            recorder.switchFile(File(store.recordingsDir, fileName))
        } catch (e: Throwable) {
            showError("Couldn't split: ${e.message}")
            return
        }
        old.end = at
        markFinishing(old)
        beginNote(fileName, origin = at, part = _state.value.partNumber + 1)
        scope.launch { finishSession(old) }
    }

    /** Pauses the microphone without ending the note; [resume] carries on in the same note. */
    fun pause() {
        val s = _state.value
        if (!s.isRecording || s.pausedAt != null) return
        recorder.paused = true
        _state.update { it.copy(pausedAt = System.currentTimeMillis()) }
    }

    fun resume() {
        val pausedAt = _state.value.pausedAt ?: return
        recorder.paused = false
        // Shift the start so the elapsed time doesn't count the pause.
        val pausedFor = System.currentTimeMillis() - pausedAt
        _state.update { it.copy(pausedAt = null, startedAt = it.startedAt?.plus(pausedFor)) }
    }

    fun togglePause() {
        if (_state.value.pausedAt != null) resume() else pause()
    }

    fun stop() {
        val current = session ?: return
        if (!_state.value.isRecording) return
        recorder.paused = false
        recorder.stop()
        inputs.endRecording()
        current.end = recorder.sampleCount
        current.stoppedByUser = true
        markFinishing(current)
        session = null
        tailTranslateJob?.cancel()
        _state.update {
            it.copy(isRecording = false, startedAt = null, activeInputName = null, recordingNoteId = null,
                liveText = "", liveTranslation = null, partNumber = 1, pausedAt = null)
        }
        recorder.writeError?.let { showError("Part of this recording couldn't be saved: $it") }
        // Taken now, so a quick restart's new live job isn't the one cancelled.
        val job = liveJob
        liveJob = null
        scope.launch {
            job?.cancelAndJoin()
            finishSession(current)
        }
    }

    /** Final transcription of a stopped or split-off note, then its saved revision and translation. */
    private suspend fun finishSession(s: LiveSession) {
        val noteId = s.noteId
        try {
            val duration = ((s.end ?: recorder.sampleCount) - s.origin).toDouble() / AudioRecorder.SAMPLE_RATE
            // Opening the app always starts a recording; drop ones stopped before
            // anything could have been said.
            if (duration < NoteStore.MIN_SECONDS) {
                store.delete(noteId)
                return
            }
            if (!engineReady) {
                // Audio is safe on disk; it is transcribed once the model is ready.
                store.update(noteId) {
                    it.copy(status = TranscriptionStatus.PENDING, durationSeconds = duration, partialText = s.committedText.ifBlank { null })
                }
                return
            }
            store.update(noteId) {
                it.copy(status = TranscriptionStatus.IN_PROGRESS, durationSeconds = duration, attempts = it.attempts + 1)
            }
            val error = processLive(s, final = true)
            val text = s.committedText
            store.update(noteId) {
                if (error == null) {
                    val done = it.withTranscription(text, RevisionSource.TRANSCRIPTION, engineLabel(s.engines), mostCommon(s.languages))
                        .copy(status = TranscriptionStatus.DONE, lastError = null, partialText = null)
                    // Reuse the live translation when every part of it finished
                    // (and the transcription is the current text, not behind an edit).
                    val current = done.revisions.lastOrNull()
                    if (current != null && current.source == RevisionSource.TRANSCRIPTION && current.text == text.trim()) {
                        done.copy(translation = liveTranslationFor(s.segments, current.id))
                    } else {
                        done
                    }
                } else {
                    it.copy(status = TranscriptionStatus.FAILED, lastError = error, partialText = text.ifBlank { null })
                }
            }
            // Every finished part is copied: after Stop, a manual split or an auto-split.
            if (error == null) copyWhenDone(noteId)
        } finally {
            s.detector?.release()
            unmarkFinishing(s)
            processQueue()
        }
    }

    /**
     * One live step for [s]: finds newly finished utterances, commits them, and
     * (while it's the note being recorded) refreshes the preview of speech in
     * progress. Returns null on success or the error message. Nothing is
     * skipped on failure: utterances leave [LiveSession.pending] only once
     * their text is committed, so the next pass retries them.
     */
    private suspend fun processLive(s: LiveSession, final: Boolean = false): String? {
        return try {
            val now = s.end ?: recorder.sampleCount
            val vad = s.detector
            feedDetector(s, now, final)

            val batchReady = when {
                final -> true
                grouping == ChunkPlanner.Grouping.UTTERANCES -> true
                else -> s.pending.isNotEmpty() &&
                    now - s.pending.first().start >= (WHISPER_BATCH_SECONDS * AudioRecorder.SAMPLE_RATE).toInt()
            }
            if (batchReady && s.pending.isNotEmpty()) commit(s, now)

            if (!final && s === session) {
                // Preview: whatever is still uncommitted, but only while there's speech.
                val speaking = vad?.inSpeech ?: true
                val tail = if (speaking || s.pending.isNotEmpty()) {
                    // The detector confirms speech ~0.25 s after it starts; back up a second.
                    val openStart = if (vad == null) s.committedUpTo else s.quietAt - AudioRecorder.SAMPLE_RATE
                    val from = minOf(s.pending.firstOrNull()?.start?.minus(PAD) ?: openStart, openStart)
                    transcribe(recorder.samples(maxOf(from, s.committedUpTo, s.origin, now - MAX_CHUNK), now))
                } else {
                    Transcript("", null)
                }
                if (tail.text != s.tail.text) {
                    s.tail = LiveSegment(tail.text, tail.language)
                    translateTail(s)
                }
                _state.update { it.copy(liveText = join(s.committedText, tail.text)) }
                publishLiveTranslation(s)
            }
            null
        } catch (e: CancellationException) {
            if (final) throw e
            null
        } catch (e: Throwable) {
            val message = e.message ?: e.toString()
            if (final) showError("Transcription failed — the recording is saved and can be retried.")
            message
        }
    }

    /**
     * Passes new audio of [s] to its speech detector, collecting finished
     * utterances and noting when speech was last heard (for auto-split).
     * Runs even while no model is loaded, so nothing is missed.
     */
    private fun feedDetector(s: LiveSession, now: Int, final: Boolean) {
        val vad = s.detector
        if (vad != null) {
            if (now > s.detectedUpTo) {
                // The detector counts from the first sample it was given: this note's origin.
                val found = vad.feed(recorder.samples(s.detectedUpTo, now)).map { Span(it.start + s.origin, it.end + s.origin) }
                s.pending += found
                found.lastOrNull()?.let { s.lastVoiceAt = maxOf(s.lastVoiceAt ?: 0, it.end) }
                s.detectedUpTo = now
                if (vad.inSpeech) s.lastVoiceAt = now else s.quietAt = now
            }
            if (final) s.pending += vad.flush().map { Span(it.start + s.origin, minOf(it.end + s.origin, now)) }
        } else {
            s.lastVoiceAt = now // no detector: can't tell silence apart, so assume speech
            if (final || now - s.committedUpTo >= MAX_CHUNK) {
                // Treat everything as speech; the planner cuts at quiet points.
                s.pending.clear()
                s.pending += Span(s.committedUpTo, now)
            }
        }
    }

    fun setTabletopLayout(enabled: Boolean) {
        prefs.edit { putBoolean("tabletopLayout", enabled) }
        _state.update { it.copy(tabletopLayout = enabled) }
    }

    fun setNoteTapAction(action: NoteTapAction) {
        prefs.edit { putString("noteTapAction", action.name) }
        _state.update { it.copy(noteTapAction = action) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString("theme", mode.name) }
        _state.update { it.copy(themeMode = mode) }
    }

    fun setCopyWhenDone(enabled: Boolean) {
        prefs.edit { putBoolean("copyWhenDone", enabled) }
        _state.update { it.copy(copyWhenDone = enabled) }
    }

    /** Puts a just-finished recording's transcript on the clipboard and says so with a toast. */
    private fun copyWhenDone(noteId: String) {
        if (!_state.value.copyWhenDone) return
        val text = store.note(noteId)?.currentText?.takeIf { it.isNotBlank() } ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("VoicePad transcript", text))
        val typing = sendToKeyboardIfConnected(text)
        Toast.makeText(
            context,
            if (typing) "Transcript copied and typing on the keyboard" else "Transcript copied to clipboard",
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun setAutoSplit(mode: AutoSplit) {
        prefs.edit { putString("autoSplit", mode.name) }
        _state.update { it.copy(autoSplit = mode) }
    }

    /** Splits the note being recorded when the auto-split rule says so. */
    private fun maybeAutoSplit(s: LiveSession) {
        if (s !== session || !_state.value.isRecording) return
        val speaking = s.detector?.inSpeech ?: true
        if (_state.value.autoSplit.shouldSplit(s.origin, recorder.sampleCount, speaking, s.lastVoiceAt, AudioRecorder.SAMPLE_RATE)) {
            split()
        }
    }

    /** Transcribes and commits every pending utterance of [s], chunk by chunk. */
    private suspend fun commit(s: LiveSession, audioEnd: Int) {
        val chunks = ChunkPlanner.plan(s.pending, audioEnd, MAX_CHUNK, PAD, grouping) { from, to ->
            quietestPoint(recorder.samples(from, to), from)
        }
        for (chunk in chunks) {
            // Padding never reaches into the previous note's audio.
            val audio = join(chunk.map { recorder.samples(maxOf(it.start, s.origin), it.end) })
            val result = transcribe(audio)
            s.committedText = join(s.committedText, result.text)
            result.language?.let { s.languages += it }
            result.engine?.let { s.engines += it }
            if (result.text.isNotBlank()) {
                val segment = LiveSegment(result.text, result.language)
                s.segments += segment
                translateSegment(s, segment)
            }
            val end = chunk.last().end
            s.pending.removeAll { it.end <= end }
            // A hard cut inside one long utterance: keep its unsent remainder.
            s.pending.replaceAll { if (it.start < end) Span(end, it.end) else it }
            s.committedUpTo = maxOf(s.committedUpTo, end)
            if (s === session) {
                _state.update { it.copy(liveText = s.committedText) }
                publishLiveTranslation(s)
            }
            // Keep committed text on disk in case the app dies.
            store.update(s.noteId) { it.copy(partialText = s.committedText.ifBlank { null }) }
            freeCommittedAudio()
        }
    }

    private fun markFinishing(s: LiveSession) {
        finishing += s
        _state.update { it.copy(finishingCount = finishing.size) }
    }

    private fun unmarkFinishing(s: LiveSession) {
        finishing -= s
        _state.update { it.copy(finishingCount = finishing.size) }
    }

    /** Drops audio from memory that no live or finishing note still needs. */
    private fun freeCommittedAudio() {
        val needed = (finishing + listOfNotNull(session)).minOfOrNull { it.committedUpTo } ?: return
        recorder.discardBefore(needed - PAD)
    }

    // Live translation: each committed utterance is translated once, as soon as
    // its pause is detected; the utterance still being spoken is re-translated
    // whenever its preview text changes.

    private fun translateSegment(s: LiveSession, segment: LiveSegment) {
        if (!_state.value.autoTranslate) return
        scope.launch {
            liveTranslateMutex.withLock { translateInto(segment) }
            if (s === session) publishLiveTranslation(s)
        }
    }

    private fun translateTail(s: LiveSession) {
        if (!_state.value.autoTranslate) return
        val tail = s.tail
        tailTranslateJob?.cancel()
        if (tail.text.isBlank()) return
        tailTranslateJob = scope.launch {
            delay(300) // let a burst of preview updates settle
            liveTranslateMutex.withLock { translateInto(tail) }
            if (s === session && s.tail === tail) publishLiveTranslation(s)
        }
    }

    private suspend fun translateInto(segment: LiveSegment) {
        if (segment.translated) return
        try {
            val result = translator.translate(segment.text, segment.language)
            segment.english = result.english ?: segment.text.takeIf { LanguageTags.isEnglish(result.language) }
            segment.romanization = result.romanization
            if (result.language != null) segment.detectedLanguage = result.language
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Live translation is best effort; the saved note is translated (and retried) afterwards.
        } finally {
            segment.translated = true
        }
    }

    /** Pushes the combined live translation of [s] to the UI. */
    private fun publishLiveTranslation(s: LiveSession) {
        if (!_state.value.autoTranslate) return
        val parts = s.segments + listOfNotNull(s.tail.takeIf { it.text.isNotBlank() })
        val translation = combine(parts, revisionId = "live")
        _state.update { it.copy(liveTranslation = translation) }
    }

    /** The finished live translation for a saved revision, or null to translate it afresh. */
    private fun liveTranslationFor(segments: List<LiveSegment>, revisionId: String): NoteTranslation? {
        if (!_state.value.autoTranslate || segments.isEmpty() || segments.any { !it.translated }) return null
        return combine(segments, revisionId)?.takeIf { it.status != TranslationStatus.PENDING }
    }

    private fun combine(parts: List<LiveSegment>, revisionId: String): NoteTranslation? =
        TranslationJoin.combine(
            parts.map {
                TranslationJoin.Part(it.text, it.detectedLanguage ?: it.language, it.english, it.romanization, it.translated)
            },
            revisionId,
        )

    /** Position (in the recording) of the quietest 100 ms in [window], which starts at [start]. */
    private fun quietestPoint(window: FloatArray, start: Int): Int {
        val frame = AudioRecorder.SAMPLE_RATE / 10
        if (window.size < frame) return start + window.size
        var best = start + window.size
        var bestEnergy = Float.MAX_VALUE
        var offset = 0
        while (offset + frame <= window.size) {
            var energy = 0f
            for (i in offset until offset + frame) energy += window[i] * window[i]
            if (energy < bestEnergy) {
                bestEnergy = energy
                best = start + offset + frame / 2
            }
            offset += frame
        }
        return best
    }

    private fun join(parts: List<FloatArray>): FloatArray {
        if (parts.size == 1) return parts[0]
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { System.arraycopy(p, 0, out, at, p.size); at += p.size }
        return out
    }

    /** "Parakeet v3", or "Parakeet v3 + SenseVoice" when Auto used both. */
    private fun engineLabel(engines: List<String>): String =
        engines.distinct().joinToString(" + ").ifBlank { _state.value.activeEngine.shortName }

    private fun join(a: String, b: String) = listOf(a, b).filter { it.isNotEmpty() }.joinToString(" ")

    private fun mostCommon(languages: List<String>): String? =
        languages.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    // endregion

    // region Conversation mode

    /** True while conversation mode has the microphone and the model; notes don't record meanwhile. */
    private var conversationActive = false
    private var resumeAfterConversation = false

    /** Hands the microphone and the model to conversation mode, saving any note being recorded. */
    fun enterConversation() {
        if (conversationActive) return
        resumeAfterConversation = _state.value.isRecording
        if (_state.value.isRecording) stop()
        conversationActive = true
        if (engine == null && loadJob?.isActive != true) loadEngine()
    }

    /** Back to notes: recording resumes if it was on before conversation mode. */
    fun leaveConversation() {
        if (!conversationActive) return
        conversationActive = false
        if (resumeAfterConversation) start()
        processQueue()
    }

    val isEngineReady: Boolean get() = engineReady

    /** One model call for conversation mode (shares the loaded model with notes). */
    suspend fun transcribeForConversation(samples: FloatArray): Transcript = transcribe(samples, normalize = false)

    /** The chosen microphone for conversation mode (null = phone's default), with a Bluetooth headset switched on. */
    fun beginConversationInput(): android.media.AudioDeviceInfo? = inputs.beginRecording(_state.value.selectedInputKey)

    fun endConversationInput() = inputs.endRecording()

    /** Channels the chosen microphone (or the built-in one) can record. */
    fun conversationMicChannels(): Int {
        val s = _state.value
        val device = s.inputDevices.firstOrNull { it.key == s.selectedInputKey }
            ?: s.inputDevices.firstOrNull { it.kind == "Built-in microphone" }
        return device?.maxChannels ?: 2
    }

    // endregion

    // region Saved-audio transcription and retries

    /** Re-runs the current model over a note's audio (manual retry or re-transcribe). */
    fun retranscribe(noteId: String) {
        scope.launch { transcribeSaved(noteId, userRequested = true) }
    }

    /** Retries every recording that still needs text, ignoring the auto-retry limit. */
    fun retryAll() {
        scope.launch {
            for (note in store.notes.value.filter { it.needsTranscription }) transcribeSaved(note.id, userRequested = true)
        }
    }

    /**
     * Works through notes that still need text, oldest first. Skips ones that
     * already failed [MAX_AUTO_ATTEMPTS] times; those wait for a manual retry.
     * Runs only while not recording, so live text stays responsive.
     */
    private fun processQueue() {
        if (queueJob?.isActive == true) return
        queueJob = scope.launch {
            // Each note gets at most one attempt per pass; the next pass comes
            // with the next app launch, model load or finished recording.
            val tried = mutableSetOf<String>()
            while (engineReady && !_state.value.isRecording && !conversationActive) {
                val next = store.notes.value.firstOrNull {
                    it.needsTranscription && it.attempts < MAX_AUTO_ATTEMPTS &&
                        it.id != _state.value.recordingNoteId && it.id !in tried
                } ?: break
                tried += next.id
                transcribeSaved(next.id)
            }
        }
    }

    private suspend fun transcribeSaved(noteId: String, userRequested: Boolean = false) {
        val note = store.note(noteId) ?: return
        if (note.status == TranscriptionStatus.IN_PROGRESS || note.status == TranscriptionStatus.RECORDING) return
        val stored = store.audioFile(note)
        if (stored == null || !stored.exists()) {
            store.update(noteId) { it.copy(status = TranscriptionStatus.FAILED, lastError = "The audio file is missing.") }
            return
        }
        if (!engineReady) {
            store.update(noteId) { if (it.needsTranscription) it.copy(status = TranscriptionStatus.PENDING) else it }
            showError("The model isn't ready yet — the recording will be transcribed when it is.")
            return
        }
        val hadText = note.status == TranscriptionStatus.DONE && note.revisions.isNotEmpty()
        val source = if (note.revisions.isEmpty()) RevisionSource.TRANSCRIPTION else RevisionSource.RETRANSCRIPTION

        store.update(noteId) { it.copy(status = TranscriptionStatus.IN_PROGRESS, attempts = it.attempts + 1) }
        // Archived notes are compressed: decode to a temporary WAV first.
        val decoded = if (stored.extension == "wav") null else File(context.cacheDir, "${note.id}.decoded.wav")
        try {
            val file = if (decoded == null) stored else withContext(Dispatchers.IO) {
                AudioArchive.decodeToWav(stored, decoded)
                decoded
            }
            val total = withContext(Dispatchers.IO) { WavFile.sampleCount(file) }
            // Find the speech by streaming the file through the detector in
            // 10 s blocks, so memory stays small however long it is.
            val speech = withContext(Dispatchers.Default) {
                val vad = newDetector()
                if (vad == null) {
                    listOf(Span(0, total))
                } else {
                    try {
                        val found = mutableListOf<Span>()
                        val block = AudioRecorder.SAMPLE_RATE * 10
                        var at = 0
                        while (at < total) {
                            found += vad.feed(WavFile.readRange(file, at, at + block))
                            at += block
                        }
                        found += vad.flush()
                        found
                    } finally {
                        vad.release()
                    }
                }
            }
            val chunks = ChunkPlanner.plan(speech, total, MAX_CHUNK, PAD, grouping) { from, to ->
                quietestPoint(WavFile.readRange(file, from, to), from)
            }
            var text = ""
            val languages = mutableListOf<String>()
            val engines = mutableListOf<String>()
            for (chunk in chunks) {
                val audio = withContext(Dispatchers.IO) { join(chunk.map { WavFile.readRange(file, it.start, it.end) }) }
                val result = transcribe(audio, note.backgroundVoices)
                text = join(text, result.text)
                result.language?.let { languages += it }
                result.engine?.let { engines += it }
            }
            val engineName = engineLabel(engines)
            if (text.isBlank() && store.note(noteId)?.currentText?.isNotBlank() == true) {
                showError("No speech found on re-transcription — the existing text was kept.")
            }
            store.update(noteId) {
                it.withTranscription(text, source, engineName, mostCommon(languages), userRequested).copy(
                    status = TranscriptionStatus.DONE,
                    lastError = null,
                    partialText = null,
                    durationSeconds = if (it.durationSeconds > 0) it.durationSeconds else total / WavFile.SAMPLE_RATE.toDouble(),
                )
            }
        } catch (e: Throwable) {
            val message = if (e is CancellationException) "Transcription was interrupted." else e.message ?: e.toString()
            // A failed re-transcription keeps the existing text as current.
            store.update(noteId) {
                it.copy(status = if (hadText) TranscriptionStatus.DONE else TranscriptionStatus.FAILED, lastError = message)
            }
            if (hadText) showError("Re-transcription failed: $message")
            if (e is CancellationException) throw e
        } finally {
            decoded?.delete()
        }
    }

    // endregion

    // region Archive and clean-up

    /** Notes that must not be archived or deleted right now. */
    private fun busyIds(): Set<String> =
        (finishing.map { it.noteId } + listOfNotNull(session?.noteId)).toSet()

    /**
     * Archives notes: compresses each one's audio to AAC (about a tenth of the
     * size) and moves it out of the main list. The WAV is only deleted once
     * the compressed copy has been checked and the note saved; if compression
     * fails the note stays as it was and you're told why.
     */
    fun archive(ids: Collection<String>) = runOnNotes(ids) { note ->
        if (note.archived) return@runOnNotes true
        val ok = compressAudio(note.id)
        if (ok) store.update(note.id) { it.copy(archived = true, archivedAt = System.currentTimeMillis()) }
        ok
    }

    /** Compresses notes' audio to AAC (about a tenth of the size) but keeps them in the list. */
    fun compress(ids: Collection<String>) = runOnNotes(ids) { note -> compressAudio(note.id) }

    /** Runs [work] on each idle note in turn and reports any that couldn't be compressed. */
    private fun runOnNotes(ids: Collection<String>, work: suspend (Note) -> Boolean) {
        scope.launch {
            var failed = 0
            for (id in ids) {
                val note = store.note(id) ?: continue
                if (id in busyIds() || note.status == TranscriptionStatus.IN_PROGRESS) continue
                if (!work(note)) failed++
            }
            if (failed > 0) {
                showError(
                    if (failed == 1) "1 note couldn't be compressed, so it was left as it was."
                    else "$failed notes couldn't be compressed, so they were left as they were."
                )
            }
        }
    }

    /**
     * WAV → AAC for one note. The WAV is only deleted once the compressed copy
     * has been checked and the note saved. True if done (or nothing to do).
     */
    private suspend fun compressAudio(id: String): Boolean {
        val note = store.note(id) ?: return true
        val wav = store.audioFile(note)
        if (wav == null || !wav.exists() || wav.extension != "wav") return true
        return try {
            val m4aName = wav.nameWithoutExtension + ".m4a"
            withContext(Dispatchers.IO) { AudioArchive.compress(wav, File(store.recordingsDir, m4aName)) }
            store.update(id) { it.copy(audioFileName = m4aName) }
            wav.delete()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    }

    /** Back to the main list (the audio stays compressed). */
    fun unarchive(id: String) = store.update(id) { it.copy(archived = false, archivedAt = null) }

    fun delete(ids: Collection<String>) = store.deleteMany(ids.filterNot { it in busyIds() })

    /** Unpinned notes older than [days] that [cleanUp] would touch. */
    fun cleanupCandidates(action: NoteCleanup.Action, days: Int): List<Note> =
        NoteCleanup.select(store.notes.value, action, days, System.currentTimeMillis(), busyIds())

    fun cleanUp(action: NoteCleanup.Action, days: Int) {
        val ids = cleanupCandidates(action, days).map { it.id }
        when (action) {
            NoteCleanup.Action.ARCHIVE -> archive(ids)
            NoteCleanup.Action.COMPRESS -> compress(ids)
            NoteCleanup.Action.DELETE_EMPTY, NoteCleanup.Action.DELETE -> delete(ids)
        }
    }

    /** Current audio size of [notes] and roughly what it would be compressed (bytes). */
    fun audioSizes(notes: List<Note>): Pair<Long, Long> {
        var now = 0L
        var after = 0L
        for (note in notes) {
            val size = store.audioFile(note)?.takeIf { it.exists() }?.length() ?: continue
            now += size
            // 16 kHz 16-bit WAV is 256 kbit/s; the archive format is 24 kbit/s.
            after += if (note.audioFileName?.endsWith(".wav") == true) size * 24 / 256 else size
        }
        return now to after
    }

    // endregion

    // region Translation

    fun setAutoTranslate(enabled: Boolean) {
        prefs.edit { putBoolean("autoTranslate", enabled) }
        _state.update { it.copy(autoTranslate = enabled) }
        if (enabled) processTranslations()
    }

    /** Translates one note now, even if automatic translation is off or gave up. */
    fun translateNow(noteId: String) {
        scope.launch { translateNote(noteId) }
    }

    /** Works through notes whose current text has no translation yet, one at a time. */
    private fun processTranslations() {
        if (!_state.value.autoTranslate || translateJob?.isActive == true) return
        translateJob = scope.launch {
            while (_state.value.autoTranslate) {
                val next = store.notes.value.firstOrNull {
                    val key = it.id + ":" + it.revisions.lastOrNull()?.id
                    key !in translationTried && it.status == TranscriptionStatus.DONE && it.needsTranslation(MAX_AUTO_ATTEMPTS)
                } ?: break
                translationTried += next.id + ":" + next.revisions.lastOrNull()?.id
                translateNote(next.id)
            }
        }
    }

    private suspend fun translateNote(noteId: String) {
        val note = store.note(noteId) ?: return
        val revision = note.revisions.lastOrNull()?.takeIf { it.text.isNotBlank() } ?: return
        val previousAttempts = note.translation?.takeIf { it.revisionId == revision.id }?.attempts ?: 0
        store.update(noteId) {
            it.copy(translation = NoteTranslation(revision.id, TranslationStatus.PENDING, attempts = previousAttempts + 1))
        }
        val outcome = try {
            val result = translator.translate(revision.text, revision.language)
            // A translation that repeats the original (misdetected English) isn't one.
            val same = TextSimilarity.nearlySame(result.english, revision.text)
            NoteTranslation(
                revisionId = revision.id,
                status = if (result.english == null || same) TranslationStatus.NOT_NEEDED else TranslationStatus.DONE,
                language = result.language,
                english = result.english,
                romanization = result.romanization,
                attempts = previousAttempts + 1,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            NoteTranslation(
                revision.id, TranslationStatus.FAILED,
                attempts = previousAttempts + 1,
                error = e.message ?: "Translation failed.",
            )
        }
        // Only store it if the text didn't change while translating.
        store.update(noteId) { if (it.revisions.lastOrNull()?.id == revision.id) it.copy(translation = outcome) else it }
    }

    // endregion

    // region Transmit (type as keyboard)

    /** Starts or stops advertising as a Bluetooth keyboard. */
    fun toggleKeyboard() {
        when (keyboard.state.value.status) {
            SinkStatus.REGISTERING, SinkStatus.ADVERTISING, SinkStatus.CONNECTED -> stopKeyboard()
            else -> keyboard.start()
        }
    }

    /** Paired devices the keyboard can connect to from the phone side. */
    fun keyboardHosts(): List<KeyboardHost> = keyboard.hosts()

    /** Connects the (registered) keyboard to an already-paired host. */
    fun connectKeyboard(address: String): Boolean = keyboard.connect(address)

    /** Nearby computers found by [startKeyboardScan]. */
    val keyboardScan: StateFlow<ScanState> get() = keyboard.scan

    fun startKeyboardScan(): Boolean = keyboard.startScan()

    fun stopKeyboardScan() = keyboard.stopScan()

    /** Pairs with a newly found computer, then connects to it as a keyboard. */
    fun pairKeyboardHost(address: String): Boolean = keyboard.pair(address)

    /** Removes a computer from the remembered list (it stays paired with the phone). */
    fun forgetKeyboardHost(address: String) = keyboard.forget(address)

    fun stopKeyboard() {
        typingJob?.cancel()
        keyboard.stop()
    }

    /**
     * Types [text] as keystrokes on the paired host, after the optional LLM
     * transform. Ignored while a previous text is still being typed; use
     * [stopTyping] to abandon that one.
     */
    fun typeText(text: String) {
        if (text.isBlank() || typingJob?.isActive == true) return
        typingJob = scope.launch {
            _state.update { it.copy(isTyping = true) }
            try {
                val final = if (_state.value.llmEnabled) transform(text) else text
                keyboard.type(final)
            } finally {
                _state.update { it.copy(isTyping = false) }
            }
        }
    }

    /**
     * "Send" from the tabletop controls: types [text] on the connected
     * computer, or copies it when none is connected. With [rewrite] the LLM
     * prompt is applied for this send even if the rewrite toggle is off; it
     * needs the model loaded (it's loaded on demand, which can take a while).
     */
    fun sendText(text: String, rewrite: Boolean) {
        if (text.isBlank()) return
        if (typingJob?.isActive == true) {
            toast("Still typing the previous text.")
            return
        }
        if (rewrite && llm == null) {
            if (_state.value.llmStatus !is LlmStatus.Loading) loadLlm(force = true)
            toast("Loading the LLM model; try again when it's ready.")
            return
        }
        val connected = _state.value.transmitStatus == SinkStatus.CONNECTED
        typingJob = scope.launch {
            _state.update { it.copy(isTyping = true) }
            try {
                val final = if (rewrite) transform(text) else text
                if (connected) {
                    keyboard.type(final)
                } else {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("VoicePad", final))
                    toast(if (rewrite) "Rewritten and copied (no keyboard connected)" else "Copied (no keyboard connected)")
                }
            } finally {
                _state.update { it.copy(isTyping = false) }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    /** Types a note's current text. */
    fun typeNote(noteId: String) {
        typeText(store.note(noteId)?.currentText.orEmpty())
    }

    /**
     * Also types [text] when a host is connected; used wherever the app
     * copies text to the clipboard. Returns whether typing started.
     */
    fun sendToKeyboardIfConnected(text: String): Boolean {
        if (_state.value.transmitStatus != SinkStatus.CONNECTED || text.isBlank()) return false
        if (typingJob?.isActive == true) return false
        typeText(text)
        return true
    }

    fun stopTyping() {
        typingJob?.cancel()
    }

    // endregion

    // region LLM transformer

    fun setLlmEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("llmEnabled", enabled) }
        _state.update { it.copy(llmEnabled = enabled) }
        if (enabled) loadLlm() else releaseLlm()
    }

    fun setLlmChoice(choice: LlmChoice) {
        if (choice == _state.value.llmChoice) return
        prefs.edit { putString("llmChoice", choice.name) }
        _state.update { it.copy(llmChoice = choice) }
        if (_state.value.llmEnabled) loadLlm()
    }

    /** Uses [instruction] for the LLM rewrite and remembers it if it's the user's own. */
    fun setTypePrompt(instruction: String) {
        val trimmed = instruction.trim()
        if (trimmed.isEmpty()) return
        val recent = LlmPrompt.remember(_state.value.recentPrompts, trimmed)
        prefs.edit {
            putString("typePrompt", trimmed)
            putString("recentPrompts", JSONArray(recent).toString())
        }
        _state.update { it.copy(typePrompt = trimmed, recentPrompts = recent) }
    }

    fun forgetPrompt(instruction: String) {
        val recent = _state.value.recentPrompts - instruction
        prefs.edit { putString("recentPrompts", JSONArray(recent).toString()) }
        _state.update { it.copy(recentPrompts = recent) }
    }

    fun llmAdvice(choice: LlmChoice): LlmAdvice =
        LlmGuidance.advice(deviceSpecs, _state.value.engineChoice, choice)

    /** Loads the chosen model; [force] loads it for one-off rewrites even with the toggle off. */
    fun loadLlm(force: Boolean = false) {
        if (!_state.value.llmEnabled && !force) return
        val choice = _state.value.llmChoice
        val previous = llmLoadJob
        previous?.cancel()
        llmLoadJob = scope.launch {
            // A cancelled load may still be inside native code; let it finish
            // so two models are never loading (and resident) at once.
            previous?.join()
            llmMutex.withLock { llm?.release(); llm = null }
            setLlmStatus(LlmStatus.Loading(0.0, !llmModels.isInstalled(choice)))
            try {
                llmModels.install(choice) { p -> setLlmStatus(LlmStatus.Loading(p, true)) }
                setLlmStatus(LlmStatus.Loading(1.0, false))
                val engine = withContext(Dispatchers.Default) { createLlmEngine(llmModels.file(choice), context.applicationInfo.nativeLibraryDir) }
                llmMutex.withLock {
                    if (isActive) {
                        llm = engine
                    } else {
                        engine.release()
                    }
                }
                ensureActive()
                setLlmStatus(LlmStatus.Ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlamaEngine.ModelLoadException) {
                // Only throw the download away if it isn't a GGUF file at all;
                // otherwise Retry would re-download hundreds of MB for nothing.
                if (!llmModels.looksValid(choice)) llmModels.discard(choice)
                setLlmStatus(LlmStatus.Failed(e.message ?: e.toString()))
            } catch (e: Throwable) {
                setLlmStatus(LlmStatus.Failed(e.message ?: e.toString()))
            }
        }
    }

    private fun releaseLlm() {
        val job = llmLoadJob
        job?.cancel()
        llmLoadJob = null
        setLlmStatus(LlmStatus.Idle)
        scope.launch {
            job?.join()
            llmMutex.withLock { llm?.release(); llm = null }
        }
    }

    private fun setLlmStatus(status: LlmStatus) = _state.update { it.copy(llmStatus = status) }

    /**
     * Runs the optional LLM transform over [text], falling back to the
     * original. Holds [llmMutex] for the whole call so the engine can't be
     * released underneath it.
     */
    private suspend fun transform(text: String): String = llmMutex.withLock {
        val engine = llm ?: return@withLock text
        try {
            withContext(Dispatchers.Default) { engine.transform(text, _state.value.typePrompt) }
                .ifBlank { text }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            text // never block typing on the LLM
        }
    }

    // endregion

    private fun showError(message: String) = _state.update { it.copy(errorMessage = message) }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }
}
