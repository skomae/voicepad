package com.voicepad.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.voicepad.app.audio.AudioRecorder
import com.voicepad.app.audio.RecordingService
import com.voicepad.app.audio.WavFile
import com.voicepad.app.data.Note
import com.voicepad.app.data.NoteStore
import com.voicepad.app.data.RevisionSource
import com.voicepad.app.data.TranscriptionStatus
import com.voicepad.app.data.withRevision
import com.voicepad.app.transcription.EngineChoice
import com.voicepad.app.transcription.ModelManager
import com.voicepad.app.transcription.SherpaEngine
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns the recorder and the speech engine and turns audio into text.
 *
 * Recordings are never thrown away because a model failed. Every note carries
 * a saved [TranscriptionStatus]; the audio stays on disk until it has text,
 * and notes that still need text are retried automatically whenever a model
 * becomes ready (up to [MAX_AUTO_ATTEMPTS] times, so a model that crashes the
 * app can't trap it in a loop) or on demand from the UI.
 *
 * Live transcription works on chunks of up to [CHUNK_SECONDS]. While a chunk is
 * open it is re-transcribed every [TICK_MS] so the bubble updates as you speak.
 * Once it is long enough it is cut at the quietest nearby moment, its text is
 * committed and a new chunk starts, so each model call stays short no matter
 * how long the recording runs.
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

    data class UiState(
        val isRecording: Boolean = false,
        val startedAt: Long? = null,
        val liveText: String = "",
        val recordingNoteId: String? = null,
        val engineStatus: EngineStatus = EngineStatus.Idle,
        val engineChoice: EngineChoice = EngineChoice.PARAKEET_V3,
        val autoRecordOnOpen: Boolean = true,
        val errorMessage: String? = null,
    )

    companion object {
        private const val CHUNK_SECONDS = 25.0
        private const val MIN_CUT_SECONDS = 18.0
        private const val TICK_MS = 1200L
        const val MAX_AUTO_ATTEMPTS = 3
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioRecorder()
    private val models = ModelManager(context)
    private val engineMutex = Mutex()

    private val _state = MutableStateFlow(
        UiState(
            engineChoice = prefs.getString("engine", null)
                ?.let { runCatching { EngineChoice.valueOf(it) }.getOrNull() }
                ?: EngineChoice.PARAKEET_V3,
            autoRecordOnOpen = prefs.getBoolean("autoRecord", true),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Live input level 0..1, polled by the UI. */
    val level: Float get() = recorder.level

    private var engine: SherpaEngine? = null
    private var loadJob: Job? = null
    private var liveJob: Job? = null
    private var queueJob: Job? = null

    private var committedText = ""
    private var chunkStart = 0

    init {
        // Before anything records: repair what a crash or kill left behind.
        store.recoverAfterRestart()
    }

    // region Engine

    fun setEngineChoice(choice: EngineChoice) {
        if (choice == _state.value.engineChoice) return
        prefs.edit { putString("engine", choice.name) }
        _state.update { it.copy(engineChoice = choice) }
        loadEngine()
    }

    fun setAutoRecord(enabled: Boolean) {
        prefs.edit { putBoolean("autoRecord", enabled) }
        _state.update { it.copy(autoRecordOnOpen = enabled) }
    }

    fun loadEngine() {
        val choice = _state.value.engineChoice
        loadJob?.cancel()
        loadJob = scope.launch {
            val old = engineMutex.withLock { engine.also { engine = null } }
            old?.release()
            val downloading = !models.isInstalled(choice)
            setStatus(EngineStatus.Loading(0.0, downloading))
            try {
                models.install(choice) { p ->
                    scope.launch { if (_state.value.engineChoice == choice) setStatus(EngineStatus.Loading(p, true)) }
                }
                setStatus(EngineStatus.Loading(1.0, false))
                val loaded = withContext(Dispatchers.Default) { SherpaEngine(choice, models.directory(choice)) }
                engineMutex.withLock { engine = loaded }
                setStatus(EngineStatus.Ready)
                processQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A broken download should not stick around.
                models.directory(choice).deleteRecursively()
                setStatus(EngineStatus.Failed(e.message ?: e.toString()))
            }
        }
    }

    private fun setStatus(status: EngineStatus) = _state.update { it.copy(engineStatus = status) }

    private val engineReady get() = _state.value.engineStatus == EngineStatus.Ready

    /** Runs one model call at a time on a background thread. */
    private suspend fun transcribe(samples: FloatArray): String = engineMutex.withLock {
        val current = engine ?: throw IllegalStateException("The model isn't loaded.")
        withContext(Dispatchers.Default) { current.transcribe(samples) }
    }

    // endregion

    // region Recording

    /** Called whenever the app comes to the foreground. */
    fun appBecameActive() {
        if (engine == null && loadJob?.isActive != true) loadEngine() else processQueue()
        val s = _state.value
        if (s.autoRecordOnOpen && !s.isRecording && s.recordingNoteId == null) start()
    }

    fun toggle() {
        if (_state.value.isRecording) scope.launch { stop() } else start()
    }

    fun start() {
        val s = _state.value
        if (s.isRecording || s.recordingNoteId != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showError("Microphone access is needed to record.")
            return
        }
        val fileName = "${UUID.randomUUID()}.wav"
        try {
            recorder.start(File(store.recordingsDir, fileName))
        } catch (e: Throwable) {
            showError(e.message ?: "Could not start recording.")
            return
        }
        RecordingService.start(context)

        // Saved before any audio arrives, so a crash can always find it.
        val note = Note(audioFileName = fileName, status = TranscriptionStatus.RECORDING)
        store.add(note)
        committedText = ""
        chunkStart = 0
        _state.update {
            it.copy(
                isRecording = true,
                startedAt = System.currentTimeMillis(),
                liveText = "",
                recordingNoteId = note.id,
                errorMessage = null,
            )
        }
        liveJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (engineReady) processChunks(noteId = note.id)
            }
        }
    }

    suspend fun stop() {
        val noteId = _state.value.recordingNoteId ?: return
        if (!_state.value.isRecording) return
        recorder.stop()
        RecordingService.stop(context)
        _state.update { it.copy(isRecording = false, startedAt = null) }
        liveJob?.cancelAndJoin()
        liveJob = null
        recorder.writeError?.let { showError("Part of this recording couldn't be saved: $it") }

        val duration = recorder.sampleCount.toDouble() / AudioRecorder.SAMPLE_RATE
        // Opening the app always starts a recording; drop ones stopped before
        // anything could have been said.
        if (duration < NoteStore.MIN_SECONDS) {
            store.delete(noteId)
            _state.update { it.copy(recordingNoteId = null, liveText = "") }
            return
        }

        if (!engineReady) {
            // Audio is safe on disk; it is transcribed once the model is ready.
            store.update(noteId) {
                it.copy(status = TranscriptionStatus.PENDING, durationSeconds = duration, partialText = _state.value.liveText.ifBlank { null })
            }
        } else {
            store.update(noteId) {
                it.copy(status = TranscriptionStatus.IN_PROGRESS, durationSeconds = duration, attempts = it.attempts + 1)
            }
            val error = processChunks(noteId = noteId, final = true)
            val text = _state.value.liveText
            val engineName = _state.value.engineChoice.shortName
            store.update(noteId) {
                if (error == null) {
                    it.withRevision(text, RevisionSource.TRANSCRIPTION, engineName)
                        .copy(status = TranscriptionStatus.DONE, lastError = null, partialText = null)
                } else {
                    it.copy(status = TranscriptionStatus.FAILED, lastError = error, partialText = text.ifBlank { null })
                }
            }
        }
        _state.update { it.copy(recordingNoteId = null, liveText = "") }
        processQueue()
    }

    /**
     * Commits every full chunk, then transcribes the open tail. Returns null on
     * success or the error message. Failed chunks are not skipped: the chunk
     * start only advances after success, so the next pass retries them.
     */
    private suspend fun processChunks(noteId: String, final: Boolean = false): String? {
        val chunkLength = (CHUNK_SECONDS * AudioRecorder.SAMPLE_RATE).toInt()
        return try {
            while (recorder.sampleCount - chunkStart >= chunkLength) {
                val cut = quietestCut(chunkStart)
                val text = transcribe(recorder.samples(chunkStart, cut))
                committedText = join(committedText, text)
                chunkStart = cut
                _state.update { it.copy(liveText = committedText) }
                // Keep committed text on disk in case the app dies.
                store.update(noteId) { it.copy(partialText = committedText.ifBlank { null }) }
            }
            val tail = transcribe(recorder.samples(chunkStart, recorder.sampleCount))
            _state.update { it.copy(liveText = join(committedText, tail)) }
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

    /** Quietest 100 ms between MIN_CUT and CHUNK seconds after [start]. */
    private fun quietestCut(start: Int): Int {
        val rate = AudioRecorder.SAMPLE_RATE
        val lower = start + (MIN_CUT_SECONDS * rate).toInt()
        val upper = start + (CHUNK_SECONDS * rate).toInt()
        val window = recorder.samples(lower, upper)
        val frame = rate / 10
        if (window.size < frame) return upper
        var best = upper
        var bestEnergy = Float.MAX_VALUE
        var offset = 0
        while (offset + frame <= window.size) {
            var energy = 0f
            for (i in offset until offset + frame) energy += window[i] * window[i]
            if (energy < bestEnergy) {
                bestEnergy = energy
                best = lower + offset + frame / 2
            }
            offset += frame
        }
        return best
    }

    private fun join(a: String, b: String) = listOf(a, b).filter { it.isNotEmpty() }.joinToString(" ")

    // endregion

    // region Saved-audio transcription and retries

    /** Re-runs the current model over a note's audio (manual retry or re-transcribe). */
    fun retranscribe(noteId: String) {
        scope.launch { transcribeSaved(noteId) }
    }

    /** Retries every recording that still needs text, ignoring the auto-retry limit. */
    fun retryAll() {
        scope.launch {
            for (note in store.notes.value.filter { it.needsTranscription }) transcribeSaved(note.id)
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
            while (engineReady && !_state.value.isRecording) {
                val next = store.notes.value.firstOrNull {
                    it.needsTranscription && it.attempts < MAX_AUTO_ATTEMPTS &&
                        it.id != _state.value.recordingNoteId && it.id !in tried
                } ?: break
                tried += next.id
                transcribeSaved(next.id)
            }
        }
    }

    private suspend fun transcribeSaved(noteId: String) {
        val note = store.note(noteId) ?: return
        if (note.status == TranscriptionStatus.IN_PROGRESS || note.status == TranscriptionStatus.RECORDING) return
        val file = store.audioFile(note)
        if (file == null || !file.exists()) {
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
        val engineName = _state.value.engineChoice.shortName

        store.update(noteId) { it.copy(status = TranscriptionStatus.IN_PROGRESS, attempts = it.attempts + 1) }
        try {
            val samples = withContext(Dispatchers.IO) { WavFile.read(file) }
            val chunkLength = (CHUNK_SECONDS * AudioRecorder.SAMPLE_RATE).toInt()
            var text = ""
            var start = 0
            while (start < samples.size) {
                val end = minOf(start + chunkLength, samples.size)
                text = join(text, transcribe(samples.copyOfRange(start, end)))
                start = end
            }
            store.update(noteId) {
                it.withRevision(text, source, engineName).copy(
                    status = TranscriptionStatus.DONE,
                    lastError = null,
                    partialText = null,
                    durationSeconds = if (it.durationSeconds > 0) it.durationSeconds else samples.size / WavFile.SAMPLE_RATE.toDouble(),
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
        }
    }

    // endregion

    private fun showError(message: String) = _state.update { it.copy(errorMessage = message) }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }
}
