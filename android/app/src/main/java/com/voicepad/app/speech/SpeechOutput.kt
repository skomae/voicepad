package com.voicepad.app.speech

import android.content.Context
import com.voicepad.app.CrashGuard
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * Says text out loud. Uses a local voice ([LocalVoice]: Kitten for English,
 * MeloTTS for Mandarin) when there is one for the language and it's
 * downloaded, otherwise the phone's own text-to-speech.
 *
 * Every clip is cached as a WAV file (keyed by voice, language and text), so
 * saying something again is instant. Requests play one at a time: [say]
 * replaces what's playing (a tap), [enqueue] waits its turn (reading
 * translations aloud automatically).
 */
class SpeechOutput(private val context: Context) {
    sealed interface VoiceStatus {
        data object NotDownloaded : VoiceStatus
        data class Downloading(val progress: Double) : VoiceStatus
        data object Ready : VoiceStatus
        data class Failed(val message: String) : VoiceStatus
    }

    data class State(
        /** Id of the request being prepared or played, for the UI's speaker icons. */
        val activeId: String? = null,
        /** True while the voice is being made — nothing audible yet, so the UI shows it's working. */
        val generating: Boolean = false,
        /** True while sound is coming out (the microphone should ignore it). */
        val playing: Boolean = false,
        val useLocalVoices: Boolean = true,
        val voices: Map<LocalVoice, VoiceStatus> = emptyMap(),
        val error: String? = null,
    )

    companion object {
        private const val TAG = "SpeechOutput"
        /** The cache is trimmed to this size, oldest clips first. */
        private const val CACHE_BYTES = 150L * 1024 * 1024
        /** 2: clips are peak-normalised before they're written (see [saveNormalized]). */
        private const val CACHE_VERSION = 2
        /** How long the phone's TTS engine gets to answer onInit before we call it missing. */
        private const val SYSTEM_INIT_TIMEOUT_MS = 8_000L
    }

    private val prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val files = LocalVoiceFiles(context)
    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }
    private val synthMutex = Mutex()
    private val loaded = mutableMapOf<LocalVoice, LocalSynthesizer>()
    private val downloads = mutableMapOf<LocalVoice, Job>()

    private val _state = MutableStateFlow(
        State(
            useLocalVoices = prefs.getBoolean("useLocalVoices", true),
            voices = LocalVoice.entries.associateWith { if (files.isInstalled(it)) VoiceStatus.Ready else VoiceStatus.NotDownloaded },
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private class Request(val id: String, val text: String, val language: String)

    private val queue = ArrayDeque<Request>()
    private var current: Job? = null
    private var player: MediaPlayer? = null

    fun setUseLocalVoices(enabled: Boolean) {
        prefs.edit { putBoolean("useLocalVoices", enabled) }
        _state.update { it.copy(useLocalVoices = enabled) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Says [text] now, replacing anything playing or queued. Saying the same [id] again stops it. */
    fun say(id: String, text: String, language: String) {
        val wasThis = _state.value.activeId == id
        stop()
        if (!wasThis) {
            queue += Request(id, text, language)
            next()
        }
    }

    /** Says [text] after whatever is already playing or queued. */
    fun enqueue(id: String, text: String, language: String) {
        queue += Request(id, text, language)
        if (current?.isActive != true) next()
    }

    fun stop() {
        queue.clear()
        current?.cancel()
        current = null
        releasePlayer()
        _state.update { it.copy(activeId = null, generating = false, playing = false) }
    }

    private fun next() {
        val request = queue.removeFirstOrNull()
        if (request == null) {
            _state.update { it.copy(activeId = null, generating = false, playing = false) }
            return
        }
        _state.update { it.copy(activeId = request.id) }
        current = scope.launch {
            try {
                val clip = clipFor(request.text, request.language) { _state.update { it.copy(generating = true) } }
                _state.update { it.copy(generating = false) }
                play(clip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "couldn't say '${request.text}' as '${request.language}'", e)
                _state.update { it.copy(generating = false, error = e.message ?: "Couldn't speak that.") }
            }
            current = null
            next()
        }
    }

    private suspend fun play(file: File) = suspendCancellableCoroutine { cont ->
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        fun finish() {
            if (player === mp) releasePlayer()
            _state.update { it.copy(playing = false) }
            if (cont.isActive) cont.resume(Unit)
        }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { finish() }
            mp.setOnErrorListener { _, _, _ -> finish(); true }
            mp.prepare()
            _state.update { it.copy(playing = true) }
            mp.start()
        } catch (e: Throwable) {
            finish()
        }
        cont.invokeOnCancellation { scope.launch { if (player === mp) releasePlayer() } }
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    // region Synthesis and cache

    /**
     * The cached clip for [text], synthesising it first if needed. [onSynthesize] runs
     * when there's no cached clip and the voice is about to be made — the UI shows
     * that it's working until the first sound comes out.
     */
    private suspend fun clipFor(text: String, language: String, onSynthesize: () -> Unit = {}): File {
        val local = localVoiceFor(language)
        if (local != null) {
            val prepared = if (local == LocalVoice.KITTEN_EN) SpeechText.spellNumbers(text) else text
            val file = cacheFile(local.name, language, prepared)
            if (file.exists()) return file.also { it.setLastModified(System.currentTimeMillis()) }
            onSynthesize()
            var leftOut: List<String> = emptyList()
            val made = synthMutex.withLock {
                withContext(Dispatchers.Default) {
                    val synth = loaded.getOrPut(local) {
                        CrashGuard.during(CrashGuard.Risky.LOCAL_VOICE) { LocalSynthesizer(local, files.directory(local)) }
                    }
                    // Kitten drops words that aren't in its dictionary. Where there's a
                    // phone engine to hand the line to, let it say it properly; where
                    // there isn't (the Retroid ships with none), saying the rest beats
                    // saying nothing, as long as the gap gets named.
                    val unknown = synth.knownWords?.let { SpeechText.unknownWords(prepared, it) }.orEmpty()
                    if (unknown.isNotEmpty() && hasSystemEngine()) {
                        null
                    } else {
                        leftOut = unknown
                        CrashGuard.during(CrashGuard.Risky.LOCAL_VOICE) { synth.generate(prepared) }?.let { audio ->
                            val tmp = File(file.path + ".part")
                            check(audio.saveNormalized(tmp)) { "Couldn't save speech." }
                            tmp.renameTo(file)
                            file
                        }
                    }
                }
            }
            if (made != null) {
                if (leftOut.isNotEmpty()) _state.update { it.copy(error = leftOutMessage(leftOut)) }
                trimCache()
                return made
            }
            // Falling through to the phone's voice: worth a line, since on a phone with
            // no TTS engine that is exactly where "isn't available" comes from.
            android.util.Log.w(TAG, "local voice declined ${local.name} for '$text'; falling back to the phone's voice")
            return systemClip(text, language, local, onSynthesize)
        } else {
            android.util.Log.w(TAG, "no local voice for '$language'; using the phone's voice for '$text'")
        }
        return systemClip(text, language, null, onSynthesize)
    }

    /** Whether the phone has any text-to-speech engine at all; asked once, it doesn't change. */
    private var systemEngines: Boolean? = null

    private fun hasSystemEngine(): Boolean = systemEngines ?: run {
        // The documented way to tell whether any engine is installed: the service intent
        // every engine declares. A phone without one (the Retroid Pocket ships bare)
        // never binds and always reports the error below.
        val found = runCatching {
            context.packageManager
                .queryIntentServices(android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .isNotEmpty()
        }.getOrDefault(true)
        systemEngines = found
        found
    }

    /** The local voice to use for [language], downloading it in the background the first time. */
    private fun localVoiceFor(language: String): LocalVoice? {
        if (!_state.value.useLocalVoices) return null
        val voice = LocalVoice.forLanguage(language) ?: return null
        if (files.isInstalled(voice)) return voice
        download(voice)
        return null // the phone's voice speaks until the download is done
    }

    /** Downloads a local voice (also offered in settings). */
    fun download(voice: LocalVoice) {
        if (files.isInstalled(voice) || downloads[voice]?.isActive == true) return
        downloads[voice] = scope.launch {
            setVoice(voice, VoiceStatus.Downloading(0.0))
            try {
                var last = -1
                files.install(voice) { p ->
                    val percent = (p * 100).toInt()
                    if (percent != last) {
                        last = percent
                        scope.launch { setVoice(voice, VoiceStatus.Downloading(p)) }
                    }
                }
                setVoice(voice, VoiceStatus.Ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                files.discard(voice)
                setVoice(voice, VoiceStatus.Failed(e.message ?: "Download failed."))
            }
        }
    }

    private fun setVoice(voice: LocalVoice, status: VoiceStatus) =
        _state.update { it.copy(voices = it.voices + (voice to status)) }

    private fun cacheFile(engine: String, language: String, text: String): File {
        // Bump CACHE_VERSION whenever how a clip is rendered changes: a cached
        // wav keyed on the old pipeline would otherwise be replayed forever.
        val digest = MessageDigest.getInstance("SHA-256").digest("$CACHE_VERSION|$engine|$language|$text".toByteArray())
        return File(cacheDir, digest.joinToString("") { "%02x".format(it) }.take(32) + ".wav")
    }

    private fun trimCache() {
        val clips = cacheDir.listFiles { f -> f.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in clips) {
            total += f.length()
            if (total > CACHE_BYTES) f.delete()
        }
    }

    private var system: TextToSpeech? = null
    private var systemReady: CompletableDeferred<Boolean>? = null
    private val systemLock = Mutex()

    /** A clip from the phone's own text-to-speech, cached the same way. */
    private suspend fun systemClip(text: String, language: String, declined: LocalVoice? = null, onSynthesize: () -> Unit = {}): File {
        val file = cacheFile("system", language, text)
        if (file.exists()) return file.also { it.setLastModified(System.currentTimeMillis()) }
        // A phone with no engine at all (a handheld like the Retroid ships without one)
        // would otherwise have us build a TextToSpeech, wait for onInit to fail, and
        // report "isn't available" — while the line being spoken is only here because
        // the local voice declined it. Say what's actually missing instead.
        if (!hasSystemEngine()) throw IllegalStateException(missingVoiceMessage(language, declined?.displayName))
        onSynthesize()
        val tts = systemTts()
        val result = tts.setLanguage(Locale.forLanguageTag(if (language == "yue") "zh-HK" else language))
        check(result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
            "This phone has no voice for ${Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH)}. " +
                "Add one in Android Settings → Text-to-speech."
        }
        val tmp = File(file.path + ".part")
        val utterance = file.name
        val ok = suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == utterance && cont.isActive) cont.resume(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { if (id == utterance && cont.isActive) cont.resume(false) }
                override fun onError(id: String?, errorCode: Int) { if (id == utterance && cont.isActive) cont.resume(false) }
            })
            if (tts.synthesizeToFile(text, null, tmp, utterance) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
        }
        check(ok && tmp.length() > 44) { "The phone's voice couldn't say that." }
        tmp.renameTo(file)
        trimCache()
        return file
    }

    /**
     * The phone's text-to-speech, initialised once.
     *
     * One caller at a time: two overlapping requests used to build two engines, and
     * the second one replaced the field — when its init reported a failure the caller
     * said "text-to-speech isn't available" while the first engine was still the one
     * doing the talking. A dead engine is released here too, so the next request
     * starts from a clean instance instead of awaiting a deferred that already
     * answered false (which leaked a binder connection per attempt).
     */
    private suspend fun systemTts(): TextToSpeech = systemLock.withLock {
        system?.let { existing -> if (systemReady?.await() == true) return existing }
        // Whatever was here either never came up or answered false: let it go.
        system?.let { stale -> runCatching { stale.shutdown() } }
        system = null
        val ready = CompletableDeferred<Boolean>()
        systemReady = ready
        val tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        system = tts
        // A phone whose engine never answers onInit would otherwise hang the caller.
        val ok = withTimeoutOrNull(SYSTEM_INIT_TIMEOUT_MS) { ready.await() } == true
        if (!ok) {
            runCatching { tts.shutdown() }
            system = null
            systemReady = null
            throw IllegalStateException("The phone's text-to-speech isn't available.")
        }
        tts
    }

    // endregion
}

/**
 * Shown when a line can't be spoken because the phone ships no text-to-speech engine.
 * [declinedVoice] is the bundled voice that was asked first, if any: it explains why an
 * app that talks perfectly well needs the phone's engine at all.
 */
internal fun missingVoiceMessage(language: String, declinedVoice: String?): String =
    if (declinedVoice != null) {
        "This phone has no text-to-speech engine, and $declinedVoice doesn't know a word in this line."
    } else {
        "This phone has no text-to-speech engine, and VoicePad's own voices only cover English and Mandarin — not “$language”."
    }

/**
 * Said when the bundled voice spoke the line minus words it has no pronunciation for —
 * on a phone with no engine to hand those words to, that's the closest thing to a full
 * sentence, but the gap shouldn't pass silently.
 */
internal fun leftOutMessage(words: List<String>): String {
    val quoted = words.joinToString(", ") { "“$it”" }
    val pronoun = if (words.size == 1) "that word" else "those words"
    return "Kitten has no pronunciation for $quoted, so it left $pronoun out."
}
