package com.voicepad.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.voicepad.app.ui.VoicePadScreen
import com.voicepad.app.ui.VoicePadTheme

class MainActivity : ComponentActivity() {
    private val controller get() = (application as VoicePadApplication).controller
    private var askedForPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // If the mic was denied, this surfaces an explanation in the UI.
        controller.appBecameActive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoicePadTheme {
                VoicePadScreen(controller)
            }
        }
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
