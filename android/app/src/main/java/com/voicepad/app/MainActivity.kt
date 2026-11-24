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
