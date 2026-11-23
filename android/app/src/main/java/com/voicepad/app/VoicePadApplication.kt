package com.voicepad.app

import android.app.Application
import com.voicepad.app.data.NoteStore

class VoicePadApplication : Application() {
    lateinit var controller: RecordingController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = RecordingController(this, NoteStore(this))
    }
}
