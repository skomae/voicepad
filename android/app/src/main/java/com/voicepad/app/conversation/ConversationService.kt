package com.voicepad.app.conversation

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
import androidx.core.content.ContextCompat
import com.voicepad.app.MainActivity
import com.voicepad.app.R
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
 * Keeps conversation mode listening while the app is in the background or the
 * phone is locked — the microphone only keeps working away from the screen
 * with a foreground service of this type — and shows what it's hearing: the
 * newest line, and Pause / Leave.
 *
 * The listening itself lives in [ConversationController]; this service mirrors
 * its state and stops itself once the conversation is paused or left.
 */
class ConversationService : Service() {
    companion object {
        private const val CHANNEL_ID = "conversation_live"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_TOGGLE = "com.voicepad.app.CONVERSATION_TOGGLE"
        private const val ACTION_LEAVE = "com.voicepad.app.CONVERSATION_LEAVE"
        /** `Notification.CATEGORY_TRANSLATION`, which NotificationCompat doesn't carry (API 31). */
        private const val CATEGORY_TRANSLATION = "translation"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConversationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConversationService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller: ConversationController get() = (application as VoicePadApplication).conversation
    private var started = false
    /** Latest start request; stopping with it is ignored if a newer start arrived. */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_TOGGLE -> controller.toggleListening()
            ACTION_LEAVE -> controller.close()
        }
        // "Leave" and "Pause" make the notification pointless: don't go (or stay) foreground for it.
        if (!controller.state.value.visible) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
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

    /** Mirrors the conversation into the notification, at most once a second. */
    private fun watch() {
        scope.launch {
            controller.state
                .map { NotificationModel.from(it) }
                .distinctUntilChanged()
                .collect { model ->
                    if (!model.visible || !model.listening) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // Ignored if listening started again in the meantime.
                        stopSelf(lastStartId)
                        return@collect
                    }
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, build(controller.state.value))
                    delay(1000) // Android throttles faster updates anyway
                }
        }
    }

    /** Only the parts of the state the notification shows, so unrelated changes don't re-post it. */
    private data class NotificationModel(
        val visible: Boolean,
        val listening: Boolean,
        val languages: Pair<String, String>,
        val latest: String,
    ) {
        companion object {
            fun from(s: ConversationController.UiState) = NotificationModel(
                visible = s.visible,
                listening = s.listening,
                languages = s.settings.pair,
                latest = s.stage.turn?.let { turn ->
                    turn.textIn(s.settings.myLanguage) ?: turn.text
                }.orEmpty(),
            )
        }
    }

    private fun build(s: ConversationController.UiState): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val settings = s.settings
        val title = "Conversation · ${LanguageNames.native(settings.theirLanguage)} ⇄ ${LanguageNames.native(settings.myLanguage)}"
        val latest = s.stage.turn?.let { turn -> turn.textIn(settings.myLanguage) ?: turn.text }.orEmpty()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // A conversation is private: on the lock screen it says nothing until unlocked.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentTitle(title)
            .setContentText(if (s.listening) latest.ifBlank { "Listening…" } else "Paused")
            .setStyle(NotificationCompat.BigTextStyle().bigText(latest.ifBlank { "Listening…" }))
            .setCategory(CATEGORY_TRANSLATION)
            .addAction(0, if (s.listening) "Pause" else "Listen", actionIntent(ACTION_TOGGLE, 1))
            .addAction(0, "Leave", actionIntent(ACTION_LEAVE, 2))
        if (s.listening) {
            // Android 16 Live Update: a status-bar chip that stays visible.
            builder.setRequestPromotedOngoing(true).setShortCriticalText("LIVE")
        }
        return builder.build()
    }

    private fun actionIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, ConversationService::class.java).setAction(action),
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
        // Default importance so it shows in the status bar and can be
        // promoted to a Live Update; silent, because it updates constantly.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Conversation", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shows while VoicePad listens in conversation mode"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }
}
