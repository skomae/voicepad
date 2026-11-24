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
        /** True while sound is coming out (the microphone should ignore it). */
        val playing: Boolean = false,
        val useLocalVoices: Boolean = true,
        val voices: Map<LocalVoice, VoiceStatus> = emptyMap(),
        val error: String? = null,
    )

    companion object {
        /** The cache is trimmed to this size, oldest clips first. */
        private const val CACHE_BYTES = 150L * 1024 * 1024
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
        _state.update { it.copy(activeId = null, playing = false) }
    }

    private fun next() {
        val request = queue.removeFirstOrNull()
        if (request == null) {
            _state.update { it.copy(activeId = null, playing = false) }
            return
        }
        _state.update { it.copy(activeId = request.id) }
        current = scope.launch {
            try {
                val clip = clipFor(request.text, request.language)
                play(clip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Couldn't speak that.") }
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

    /** The cached clip for [text], synthesising it first if needed. */
    private suspend fun clipFor(text: String, language: String): File {
        val local = localVoiceFor(language)
        if (local != null) {
            val prepared = if (local == LocalVoice.KITTEN_EN) SpeechText.spellNumbers(text) else text
            val file = cacheFile(local.name, language, prepared)
            if (file.exists()) return file.also { it.setLastModified(System.currentTimeMillis()) }
            val made = synthMutex.withLock {
                withContext(Dispatchers.Default) {
                    val synth = loaded.getOrPut(local) {
                        CrashGuard.during(CrashGuard.Risky.LOCAL_VOICE) { LocalSynthesizer(local, files.directory(local)) }
                    }
                    // Kitten would skip words missing from its dictionary; the phone's voice says them.
                    val unknown = synth.knownWords?.let { SpeechText.unknownWords(prepared, it) }.orEmpty()
                    if (unknown.isNotEmpty()) null else CrashGuard.during(CrashGuard.Risky.LOCAL_VOICE) { synth.generate(prepared) }?.let { audio ->
                        val tmp = File(file.path + ".part")
                        check(audio.save(tmp.path)) { "Couldn't save speech." }
                        tmp.renameTo(file)
                        file
                    }
                }
            }
            if (made != null) {
                trimCache()
                return made
            }
        }
        return systemClip(text, language)
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
        val digest = MessageDigest.getInstance("SHA-256").digest("$engine|$language|$text".toByteArray())
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

    /** A clip from the phone's own text-to-speech, cached the same way. */
    private suspend fun systemClip(text: String, language: String): File {
        val file = cacheFile("system", language, text)
        if (file.exists()) return file.also { it.setLastModified(System.currentTimeMillis()) }
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

    private suspend fun systemTts(): TextToSpeech {
        system?.let { existing -> if (systemReady?.await() == true) return existing }
        val ready = CompletableDeferred<Boolean>()
        systemReady = ready
        val tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        system = tts
        check(ready.await()) { "The phone's text-to-speech isn't available." }
        return tts
    }

    // endregion
}
