package com.voicepad.app

import android.app.Application
import com.voicepad.app.conversation.ConversationController
import com.voicepad.app.data.NoteStore
import com.voicepad.app.glasses.GlassesController
import com.voicepad.app.speech.SpeechOutput

class VoicePadApplication : Application() {
    lateinit var controller: RecordingController
        private set
    lateinit var conversation: ConversationController
        private set
    lateinit var speech: SpeechOutput
        private set
    lateinit var glasses: GlassesController
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CrashGuard.init(this)
        controller = RecordingController(this, NoteStore(filesDir))
        speech = SpeechOutput(this)
        glasses = GlassesController(this)
        conversation = ConversationController(this, controller, speech, glasses)
    }
}
