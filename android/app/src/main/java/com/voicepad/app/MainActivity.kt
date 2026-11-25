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
import com.voicepad.app.conversation.ConversationController
import com.voicepad.app.ui.ConversationScreen
import com.voicepad.app.ui.CrashReportDialog
import com.voicepad.app.ui.CrashGuardDialog
import com.voicepad.app.ui.VoicePadScreen
import com.voicepad.app.ui.VoicePadTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.voicepad.app.transmit.SinkStatus
import com.voicepad.app.ui.LocalTypeOnKeyboard
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.voicepad.app.ui.TabletopHinge
import com.voicepad.app.ui.TabletopScreen
import android.content.pm.ActivityInfo
import android.content.res.Configuration

class MainActivity : ComponentActivity() {
    private val app get() = application as VoicePadApplication
    private val controller get() = app.controller
    private val conversation get() = app.conversation
    private var askedForPermission = false

    /** Set while half-folded with a horizontal hinge (tabletop / flex mode). */
    private var tabletopHinge by mutableStateOf<TabletopHinge?>(null)

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
                val connected = state.transmitStatus == SinkStatus.CONNECTED
                val typeOnKeyboard: ((String) -> Unit)? =
                    remember(connected) { if (connected) controller::typeText else null }
                CompositionLocalProvider(LocalTypeOnKeyboard provides typeOnKeyboard) {
                    val hinge = tabletopHinge
                    when {
                        conversationState.visible -> ConversationScreen(conversation, controller)
                        hinge != null && state.tabletopLayout -> TabletopScreen(controller, hinge)
                        else -> VoicePadScreen(controller, conversation)
                    }
                }
            }
        }
        // A foldable reports a hinge (a folding feature) only while unfolded.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                    val hinge = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    // Standing half open with the hinge across the screen: like a laptop / tabletop posture.
                    val tabletop = hinge?.takeIf {
                        it.state == FoldingFeature.State.HALF_OPENED && it.orientation == FoldingFeature.Orientation.HORIZONTAL
                    }
                    conversation.onFoldStateChanged(
                        unfolded = hinge != null,
                        fold = tabletop?.let { ConversationController.FoldLine(it.bounds.top, it.bounds.bottom) },
                    )
                    // Separately: half-open with the hinge across the screen is tabletop posture.
                    tabletopHinge = tabletop?.let { TabletopHinge(top = it.bounds.top, bottom = it.bounds.bottom) }
                    rotateForLaptop(hinge)
                }
            }
        }
        cover = CoverScreen(this, conversation, controller).also { it.start() }
    }

    private var cover: CoverScreen? = null

    /**
     * Half-folded with the hinge running up the screen (book posture) means
     * the screen is sideways for laptop use: rotate so the hinge runs across
     * it. The SENSOR_ variants let gravity pick which way up, and override a
     * rotation lock. Unfolding or closing hands orientation back to the
     * system. Only while the laptop layout is on in Settings.
     */
    private fun rotateForLaptop(hinge: FoldingFeature?) {
        val halfOpen = hinge?.state == FoldingFeature.State.HALF_OPENED
        val ours = requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
            requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        when {
            halfOpen && hinge?.orientation == FoldingFeature.Orientation.VERTICAL &&
                controller.state.value.tabletopLayout && !conversation.state.value.visible -> {
                val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                requestedOrientation = if (portrait) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
            // Keep our rotation while still half-open (the hinge is now horizontal).
            halfOpen && ours -> {}
            ours -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onDestroy() {
        cover?.stop()
        cover = null
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
