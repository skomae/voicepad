package com.voicepad.app

import android.app.Application
import com.voicepad.app.conversation.ConversationController
import com.voicepad.app.data.NoteStore

class VoicePadApplication : Application() {
    lateinit var controller: RecordingController
        private set
    lateinit var conversation: ConversationController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = RecordingController(this, NoteStore(filesDir))
        conversation = ConversationController(this, controller)
    }
}
