package com.voicepad.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.voicepad.app.audio.AudioRecorder
import com.voicepad.app.audio.RecordingService
import com.voicepad.app.audio.WavReader
import com.voicepad.app.data.Note
import com.voicepad.app.data.NoteStore
import com.voicepad.app.data.RevisionSource
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
 * Owns the recorder and the speech engine and turns live audio into text.
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
        val busyNoteIds: Set<String> = emptySet(),
        val errorMessage: String? = null,
    )

    companion object {
        private const val CHUNK_SECONDS = 25.0
        private const val MIN_CUT_SECONDS = 18.0
        private const val TICK_MS = 1200L
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

    private var committedText = ""
    private var chunkStart = 0

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
                transcribeUntranscribedNotes()
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
        val current = engine ?: return@withLock ""
        withContext(Dispatchers.Default) { current.transcribe(samples) }
    }

    // endregion

    // region Recording

    /** Called whenever the app comes to the foreground. */
    fun appBecameActive() {
        if (engine == null && loadJob?.isActive != true) loadEngine()
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
            _state.update { it.copy(errorMessage = "Microphone access is needed to record.") }
            return
        }
        val fileName = "${UUID.randomUUID()}.wav"
        try {
            recorder.start(File(store.recordingsDir, fileName))
        } catch (e: Throwable) {
            _state.update { it.copy(errorMessage = e.message ?: "Could not start recording.") }
            return
        }
        RecordingService.start(context)

        val note = Note(audioFileName = fileName)
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
                if (engineReady) processChunks(final = false)
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

        val duration = recorder.sampleCount.toDouble() / AudioRecorder.SAMPLE_RATE
        // Opening the app always starts a recording; drop ones stopped before
        // anything could have been said.
        if (duration < 1.0) {
            store.delete(noteId)
            _state.update { it.copy(recordingNoteId = null, liveText = "") }
            return
        }
        store.setDuration(noteId, duration)

        if (engineReady) {
            markBusy(noteId, true)
            processChunks(final = true)
            markBusy(noteId, false)
            store.addRevision(noteId, _state.value.liveText, RevisionSource.TRANSCRIPTION, _state.value.engineChoice.shortName)
        }
        // Without a ready engine the audio is kept and transcribed as soon as
        // the model finishes loading.
        _state.update { it.copy(recordingNoteId = null, liveText = "") }
    }

    /** Commits every full chunk, then transcribes the open tail. */
    private suspend fun processChunks(final: Boolean) {
        val chunkLength = (CHUNK_SECONDS * AudioRecorder.SAMPLE_RATE).toInt()
        try {
            while (recorder.sampleCount - chunkStart >= chunkLength) {
                val cut = quietestCut(chunkStart)
                val text = transcribe(recorder.samples(chunkStart, cut))
                committedText = join(committedText, text)
                chunkStart = cut
                _state.update { it.copy(liveText = committedText) }
            }
            val tail = transcribe(recorder.samples(chunkStart, recorder.sampleCount))
            _state.update { it.copy(liveText = join(committedText, tail)) }
        } catch (e: CancellationException) {
            if (final) throw e
        } catch (e: Throwable) {
            _state.update { it.copy(errorMessage = "Transcription failed: ${e.message}") }
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

    private fun markBusy(id: String, busy: Boolean) =
        _state.update { it.copy(busyNoteIds = if (busy) it.busyNoteIds + id else it.busyNoteIds - id) }

    // endregion

    // region File-based transcription

    /** Re-runs the current model over a note's audio and adds a revision. */
    fun retranscribe(noteId: String) {
        scope.launch { retranscribeNow(noteId) }
    }

    private suspend fun retranscribeNow(noteId: String) {
        val note = store.note(noteId) ?: return
        val file = store.audioFile(note) ?: return
        if (!engineReady || !file.exists()) return
        val source = if (note.revisions.isEmpty()) RevisionSource.TRANSCRIPTION else RevisionSource.RETRANSCRIPTION
        markBusy(noteId, true)
        try {
            val samples = withContext(Dispatchers.IO) { WavReader.read(file) }
            val chunkLength = (CHUNK_SECONDS * AudioRecorder.SAMPLE_RATE).toInt()
            var text = ""
            var start = 0
            while (start < samples.size) {
                val end = minOf(start + chunkLength, samples.size)
                text = join(text, transcribe(samples.copyOfRange(start, end)))
                start = end
            }
            store.addRevision(noteId, text, source, _state.value.engineChoice.shortName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(errorMessage = "Transcription failed: ${e.message}") }
        } finally {
            markBusy(noteId, false)
        }
    }

    private suspend fun transcribeUntranscribedNotes() {
        val pending = store.notes.value.filter {
            it.revisions.isEmpty() && it.id != _state.value.recordingNoteId && it.audioFileName != null
        }
        for (note in pending) retranscribeNow(note.id)
    }

    // endregion

    fun dismissError() = _state.update { it.copy(errorMessage = null) }
}
