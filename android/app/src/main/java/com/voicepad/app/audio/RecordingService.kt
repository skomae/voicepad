package com.voicepad.app.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voicepad.app.MainActivity
import com.voicepad.app.R
import com.voicepad.app.RecordingController
import com.voicepad.app.VoicePadApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps VoicePad running in the background while it records or finishes a
 * transcript, and shows what it's doing: a live notification with the
 * elapsed time, the latest words, and Stop / Split buttons. On Android 16 it
 * is also promoted to a Live Update (a "REC" chip in the status bar).
 *
 * The recording itself lives in [RecordingController]; this service mirrors
 * its state and stops itself once nothing is recording or finishing.
 */
class RecordingService : Service() {
    companion object {
        private const val CHANNEL_ID = "recording_live"
        private const val OLD_CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.voicepad.app.STOP"
        private const val ACTION_SPLIT = "com.voicepad.app.SPLIT"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller: RecordingController get() = (application as VoicePadApplication).controller
    private var started = false
    /** Latest start request; stopping with it is ignored if a newer start arrived. */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> controller.stop()
            ACTION_SPLIT -> controller.split()
        }
        if (!started) {
            started = true
            createChannel()
            goForeground(build(controller.state.value))
            watch()
        } else {
            goForeground(build(controller.state.value))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Mirrors the controller's state into the notification, at most once a second. */
    private fun watch() {
        scope.launch {
            controller.state
                .map { NotificationModel.from(it) }
                .distinctUntilChanged()
                .collect { model ->
                    if (!model.recording && model.finishing == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // Ignored if a new recording started in the meantime.
                        stopSelf(lastStartId)
                        return@collect
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(controller.state.value))
                    delay(1000) // Android throttles faster updates anyway
                }
        }
    }

    /** Only the parts of the state the notification shows, so unrelated changes don't re-post it. */
    private data class NotificationModel(
        val recording: Boolean,
        val startedAt: Long?,
        val part: Int,
        val background: Boolean,
        val finishing: Int,
        val latest: String,
    ) {
        companion object {
            fun from(s: RecordingController.UiState) = NotificationModel(
                recording = s.isRecording,
                startedAt = s.startedAt,
                part = s.partNumber,
                background = s.backgroundVoices,
                finishing = s.finishingCount,
                latest = s.liveText.takeLast(300),
            )
        }
    }

    private fun build(s: RecordingController.UiState): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (s.isRecording) {
            val title = buildString {
                append("Recording")
                if (s.partNumber > 1) append(" · part ${s.partNumber}")
                if (s.backgroundVoices) append(" · background voices")
            }
            val text = s.liveText.ifBlank { "Listening…" }
            builder
                .setContentTitle(title)
                // The last words, so a glance shows it's hearing you.
                .setContentText(text.takeLast(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.takeLast(400)))
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(s.startedAt ?: System.currentTimeMillis())
                // Android 16 Live Update: a status-bar chip that stays visible.
                .setRequestPromotedOngoing(true)
                .setShortCriticalText("REC")
                .addAction(0, "Stop", actionIntent(ACTION_STOP, 1))
                .addAction(0, "Split", actionIntent(ACTION_SPLIT, 2))
        } else {
            val n = s.finishingCount
            builder
                .setContentTitle("Finishing transcript")
                .setContentText(if (n == 1) "Transcribing your recording…" else "Transcribing $n recordings…")
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun actionIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun goForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        // Default importance so it shows in the status bar and can be
        // promoted to a Live Update; silent, because it updates constantly.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shows while VoicePad records or finishes a transcript"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }
}
