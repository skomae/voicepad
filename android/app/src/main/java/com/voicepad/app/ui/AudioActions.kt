package com.voicepad.app.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File

/** Plays one recording at a time. */
class AudioPlayer {
    var playingId by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null

    fun toggle(context: Context, id: String, file: File?) {
        val wasPlaying = playingId == id
        stop()
        if (wasPlaying) return
        if (file == null || !file.exists()) {
            Toast.makeText(context, "Audio file is missing.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playingId = id
        } catch (e: Exception) {
            stop()
            Toast.makeText(context, "Couldn't play this recording.", Toast.LENGTH_SHORT).show()
        }
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        playingId = null
    }
}

/** Opens the share sheet for a recording's WAV file (save to Drive, send, etc.). */
fun shareRecording(context: Context, file: File?, transcript: String) {
    if (file == null || !file.exists()) {
        Toast.makeText(context, "Audio file is missing.", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = if (file.extension == "m4a") "audio/mp4" else "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (transcript.isNotBlank()) putExtra(Intent.EXTRA_TEXT, transcript)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share recording"))
}
