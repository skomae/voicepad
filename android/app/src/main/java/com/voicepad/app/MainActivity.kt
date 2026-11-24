package com.voicepad.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.voicepad.app.ui.ConversationScreen
import com.voicepad.app.ui.VoicePadScreen
import com.voicepad.app.ui.VoicePadTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as VoicePadApplication
    private val controller get() = app.controller
    private val conversation get() = app.conversation
    private var askedForPermission = false

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
            VoicePadTheme {
                val conversationState by conversation.state.collectAsStateWithLifecycle()
                if (conversationState.visible) {
                    ConversationScreen(conversation, controller)
                } else {
                    VoicePadScreen(controller, conversation)
                }
            }
        }
        // A foldable reports a hinge (a folding feature) only while unfolded.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                    conversation.onFoldStateChanged(info.displayFeatures.any { it is FoldingFeature })
                }
            }
        }
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
