package com.voicepad.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepad.app.ui.isDark
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.voicepad.app.ui.ConversationScreen
import com.voicepad.app.ui.CrashReportDialog
import com.voicepad.app.ui.CrashGuardDialog
import com.voicepad.app.ui.OuterScreenPresenter
import com.voicepad.app.ui.VoicePadScreen
import com.voicepad.app.ui.VoicePadTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as VoicePadApplication
    private val controller get() = app.controller
    private val conversation get() = app.conversation
    private var askedForPermission = false
    private lateinit var outerScreen: OuterScreenPresenter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // If the mic was denied, this surfaces an explanation in the UI.
        controller.appBecameActive()
        conversation.onAppStarted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val dark = state.themeMode.isDark()
            // Status/navigation bar icons follow the app's theme, not the phone's.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
            }
            VoicePadTheme(state.themeMode) {
                val conversationState by conversation.state.collectAsStateWithLifecycle()
                val crashedDuring = conversationState.crashedDuring.firstOrNull()
                if (crashedDuring != null) {
                    CrashGuardDialog(crashedDuring, onAnswer = { off -> conversation.answerCrashPrompt(crashedDuring, off) })
                } else {
                    CrashReportDialog()
                }
                if (conversationState.visible) {
                    ConversationScreen(conversation, controller)
                } else {
                    VoicePadScreen(controller, conversation)
                }
            }
        }
        // The other person's side on a foldable's outer screen, when that layout is in use.
        outerScreen = OuterScreenPresenter(this, conversation).also { it.start() }
        // A foldable reports a hinge (a folding feature) only while unfolded.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                    conversation.onFoldStateChanged(info.displayFeatures.any { it is FoldingFeature })
                }
            }
        }
    }

    override fun onDestroy() {
        outerScreen.stop()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        conversation.onAppStarted()
    }

    override fun onStop() {
        super.onStop()
        // Folding or unfolding recreates the activity; that's not leaving the app.
        if (!isChangingConfigurations) conversation.onAppStopped()
    }

    override fun onResume() {
        super.onResume()
        // Start recording the moment the app is opened.
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            controller.appBecameActive()
        } else if (!askedForPermission) {
            // Ask once per launch; the permission dialog itself pauses and
            // resumes the activity.
            askedForPermission = true
            val wanted = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
