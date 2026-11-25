package com.voicepad.app

import android.app.Application
import com.voicepad.app.conversation.ConversationController
import com.voicepad.app.data.NoteStore
import com.voicepad.app.glasses.GlassesController
import com.voicepad.app.speech.SpeechOutput
import com.voicepad.app.transcription.MlKitTranslationModels
import com.voicepad.app.transcription.ModelFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VoicePadApplication : Application() {
    lateinit var controller: RecordingController
        private set
    lateinit var conversation: ConversationController
        private set
    lateinit var speech: SpeechOutput
        private set
    lateinit var glasses: GlassesController
        private set

    private val startup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CrashGuard.init(this)
        controller = RecordingController(this, NoteStore(filesDir))
        speech = SpeechOutput(this)
        glasses = GlassesController(this)
        conversation = ConversationController(this, controller, speech, glasses)
        // Put the user's model folder in step: copy back what a reinstall lost
        // (ML Kit's translation packs included) and copy up anything installed
        // since the folder was chosen.
        startup.launch {
            runCatching { MlKitTranslationModels.restore(this@VoicePadApplication) }
            runCatching { ModelFolder.get(this@VoicePadApplication).saveAll() }
        }
    }
}
