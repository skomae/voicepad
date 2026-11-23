package com.voicepad.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.voicepad.app.audio.AudioInputs
import com.voicepad.app.audio.AudioRecorder
import com.voicepad.app.audio.InputDevice
import com.voicepad.app.audio.RecordingService
import com.voicepad.app.audio.WavFile
import com.voicepad.app.data.Note
import com.voicepad.app.data.NoteStore
import com.voicepad.app.data.NoteTranslation
import com.voicepad.app.data.RevisionSource
import com.voicepad.app.data.TranscriptionStatus
import com.voicepad.app.data.TranslationStatus
import com.voicepad.app.data.withRevision
import com.voicepad.app.transcription.AutoEngine
import com.voicepad.app.transcription.ChunkPlanner
import com.voicepad.app.transcription.ChunkPlanner.Span
import com.voicepad.app.transcription.EngineChoice
import com.voicepad.app.transcription.EnglishTranslator
import com.voicepad.app.transcription.ModelManager
import com.voicepad.app.transcription.SherpaEngine
import com.voicepad.app.transcription.SpeechDetector
import com.voicepad.app.transcription.SpeechEngine
import com.voicepad.app.transcription.Transcript
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

    data class UiState(
        val isRecording: Boolean = false,
        val startedAt: Long? = null,
        val liveText: String = "",
        val recordingNoteId: String? = null,
        val engineStatus: EngineStatus = EngineStatus.Idle,
        val engineChoice: EngineChoice = EngineChoice.PARAKEET_V3,
        val autoRecordOnOpen: Boolean = true,
        val autoTranslate: Boolean = true,
        val errorMessage: String? = null,
        /** Microphones currently connected. */
        val inputDevices: List<InputDevice> = emptyList(),
        /** Chosen microphone's key; null means the phone picks (automatic). */
        val selectedInputKey: String? = null,
        /** Mic Android is actually recording from right now, if recording. */
        val activeInputName: String? = null,
    )

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
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioRecorder()
    private val models = ModelManager(context)
    private val engineMutex = Mutex()
    private val inputs = AudioInputs(context)

    private val _state = MutableStateFlow(
        UiState(
            engineChoice = prefs.getString("engine", null)
                ?.let { runCatching { EngineChoice.valueOf(it) }.getOrNull() }
                ?: EngineChoice.PARAKEET_V3,
            autoRecordOnOpen = prefs.getBoolean("autoRecord", true),
            autoTranslate = prefs.getBoolean("autoTranslate", true),
            inputDevices = inputs.devices.value,
            selectedInputKey = prefs.getString("inputDevice", null),
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

    // Live state for the recording in progress.
    private var committedText = ""
    /** Recording position up to which text is committed. */
    private var committedUpTo = 0
    /** Recording position fed to the speech detector so far. */
    private var detectedUpTo = 0
    /** Last position at which the detector heard no speech; the current utterance began after it. */
    private var quietAt = 0
    private var detector: SpeechDetector? = null
    /** Finished utterances not yet committed. */
    private val pendingSpeech = mutableListOf<Span>()
    /** Language / model reported for each committed piece. */
    private val chunkLanguages = mutableListOf<String>()
    private val chunkEngines = mutableListOf<String>()

    init {
        // Before anything records: repair what a crash or kill left behind.
        store.recoverAfterRestart()

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
                val loaded = withContext(Dispatchers.Default) { createEngine(choice) }
                engineMutex.withLock { engine = loaded }
                setStatus(EngineStatus.Ready)
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
    private suspend fun transcribe(samples: FloatArray): Transcript = engineMutex.withLock {
        val current = engine ?: throw IllegalStateException("The model isn't loaded.")
        withContext(Dispatchers.Default) { current.transcribe(samples) }
    }

    // endregion

    // region Recording

    /** Called whenever the app comes to the foreground. */
    fun appBecameActive() {
        translationTried.clear()
        processTranslations()
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
            // A chosen mic that isn't connected falls back to the phone's default.
            val preferred = inputs.beginRecording(s.selectedInputKey)
            recorder.start(File(store.recordingsDir, fileName), preferred)
        } catch (e: Throwable) {
            inputs.endRecording()
            showError(e.message ?: "Could not start recording.")
            return
        }
        RecordingService.start(context)

        // Saved before any audio arrives, so a crash can always find it.
        val note = Note(audioFileName = fileName, status = TranscriptionStatus.RECORDING)
        store.add(note)
        committedText = ""
        committedUpTo = 0
        detectedUpTo = 0
        quietAt = 0
        pendingSpeech.clear()
        chunkLanguages.clear()
        chunkEngines.clear()
        detector?.release()
        detector = newDetector()
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
                if (engineReady) processLive(noteId = note.id)
            }
        }
    }

    suspend fun stop() {
        val noteId = _state.value.recordingNoteId ?: return
        if (!_state.value.isRecording) return
        recorder.stop()
        inputs.endRecording()
        RecordingService.stop(context)
        _state.update { it.copy(isRecording = false, startedAt = null, activeInputName = null) }
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
            val error = processLive(noteId = noteId, final = true)
            val text = _state.value.liveText
            val engineName = engineLabel(chunkEngines)
            store.update(noteId) {
                if (error == null) {
                    it.withRevision(text, RevisionSource.TRANSCRIPTION, engineName, mostCommon(chunkLanguages))
                        .copy(status = TranscriptionStatus.DONE, lastError = null, partialText = null)
                } else {
                    it.copy(status = TranscriptionStatus.FAILED, lastError = error, partialText = text.ifBlank { null })
                }
            }
        }
        detector?.release()
        detector = null
        _state.update { it.copy(recordingNoteId = null, liveText = "") }
        processQueue()
    }

    /**
     * One live step: finds newly finished utterances, commits the ones that
     * are ready, and refreshes the preview of speech still in progress.
     * Returns null on success or the error message. Nothing is skipped on
     * failure: utterances are only removed from [pendingSpeech] once their
     * text is committed, so the next pass retries them.
     */
    private suspend fun processLive(noteId: String, final: Boolean = false): String? {
        return try {
            val now = recorder.sampleCount
            val vad = detector
            if (vad != null) {
                if (now > detectedUpTo) {
                    pendingSpeech += vad.feed(recorder.samples(detectedUpTo, now))
                    detectedUpTo = now
                    if (!vad.inSpeech) quietAt = now
                }
                if (final) pendingSpeech += vad.flush()
            } else if (final || now - committedUpTo >= MAX_CHUNK) {
                // No detector: treat everything as speech; the planner cuts at quiet points.
                pendingSpeech.clear()
                pendingSpeech += Span(committedUpTo, now)
            }

            val batchReady = when {
                final -> true
                grouping == ChunkPlanner.Grouping.UTTERANCES -> true
                else -> pendingSpeech.isNotEmpty() &&
                    now - pendingSpeech.first().start >= (WHISPER_BATCH_SECONDS * AudioRecorder.SAMPLE_RATE).toInt()
            }
            if (batchReady && pendingSpeech.isNotEmpty()) commit(noteId, now)

            if (!final) {
                // Preview: whatever is still uncommitted, but only while there's speech.
                val speaking = vad?.inSpeech ?: true
                val tail = if (speaking || pendingSpeech.isNotEmpty()) {
                    // The detector confirms speech ~0.25 s after it starts; back up a second.
                    val openStart = if (vad == null) committedUpTo else quietAt - AudioRecorder.SAMPLE_RATE
                    val from = minOf(pendingSpeech.firstOrNull()?.start?.minus(PAD) ?: openStart, openStart)
                    transcribe(recorder.samples(maxOf(from, committedUpTo, now - MAX_CHUNK), now)).text
                } else {
                    ""
                }
                _state.update { it.copy(liveText = join(committedText, tail)) }
            } else {
                _state.update { it.copy(liveText = committedText) }
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

    /** Transcribes and commits every pending utterance, chunk by chunk. */
    private suspend fun commit(noteId: String, audioEnd: Int) {
        val chunks = ChunkPlanner.plan(pendingSpeech, audioEnd, MAX_CHUNK, PAD, grouping) { from, to ->
            quietestPoint(recorder.samples(from, to), from)
        }
        for (chunk in chunks) {
            val audio = join(chunk.map { recorder.samples(it.start, it.end) })
            val result = transcribe(audio)
            committedText = join(committedText, result.text)
            result.language?.let { chunkLanguages += it }
            result.engine?.let { chunkEngines += it }
            val end = chunk.last().end
            pendingSpeech.removeAll { it.end <= end }
            // A hard cut inside one long utterance: keep its unsent remainder.
            pendingSpeech.replaceAll { if (it.start < end) Span(end, it.end) else it }
            committedUpTo = maxOf(committedUpTo, end)
            _state.update { it.copy(liveText = committedText) }
            // Keep committed text on disk in case the app dies, and free the audio.
            store.update(noteId) { it.copy(partialText = committedText.ifBlank { null }) }
            recorder.discardBefore(committedUpTo - PAD)
        }
    }

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
        engines.distinct().joinToString(" + ").ifBlank { _state.value.engineChoice.shortName }

    private fun join(a: String, b: String) = listOf(a, b).filter { it.isNotEmpty() }.joinToString(" ")

    private fun mostCommon(languages: List<String>): String? =
        languages.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

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

        store.update(noteId) { it.copy(status = TranscriptionStatus.IN_PROGRESS, attempts = it.attempts + 1) }
        try {
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
                val result = transcribe(audio)
                text = join(text, result.text)
                result.language?.let { languages += it }
                result.engine?.let { engines += it }
            }
            val engineName = engineLabel(engines)
            store.update(noteId) {
                it.withRevision(text, source, engineName, mostCommon(languages)).copy(
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
        }
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
            NoteTranslation(
                revisionId = revision.id,
                status = if (result.english == null) TranslationStatus.NOT_NEEDED else TranslationStatus.DONE,
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

    private fun showError(message: String) = _state.update { it.copy(errorMessage = message) }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }
}
