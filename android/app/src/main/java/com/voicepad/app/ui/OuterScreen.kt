package com.voicepad.app.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import com.voicepad.app.CrashGuard
import com.voicepad.app.conversation.ConversationController
import com.voicepad.app.conversation.ConversationController.OuterScreen
import com.voicepad.app.conversation.ConversationLayout
import com.voicepad.app.conversation.LanguageNames
import com.voicepad.app.conversation.Turn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Puts the other person's side on a foldable's outer screen while it's
 * unfolded (Android's rear-display presentation, e.g. Pixel Fold's dual
 * screen), whenever conversation mode uses [ConversationLayout.OUTER_SCREEN].
 *
 * Reports what the outer screen can do to the [conversation] so the inner
 * screen can explain when it isn't available.
 */
@OptIn(ExperimentalWindowApi::class)
class OuterScreenPresenter(
    private val activity: ComponentActivity,
    private val conversation: ConversationController,
) {
    private val areas = WindowAreaController.getOrCreate()
    private var session: WindowAreaSessionPresenter? = null
    private var starting = false

    fun start() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val rear = areas.windowAreaInfos.map { infos -> infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING } }
                val wanted = conversation.state.map {
                    it.visible && it.layout == ConversationLayout.OUTER_SCREEN && CrashGuard.Risky.OUTER_SCREEN !in it.crashedDuring
                }.distinctUntilChanged()
                rear.combine(wanted) { info, want -> info to want }.collect { (info, want) -> update(info, want) }
            }
        }
    }

    private fun update(info: WindowAreaInfo?, want: Boolean) {
        val status = info?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status
        conversation.setOuterScreen(
            when (status) {
                null, WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED -> OuterScreen.UNSUPPORTED
                WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> if (session != null) OuterScreen.SHOWING else OuterScreen.UNAVAILABLE
                WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE -> OuterScreen.AVAILABLE
                else -> OuterScreen.UNAVAILABLE
            }
        )
        if (!want) {
            endSession()
            return
        }
        if (session != null || starting || info == null || status != WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE) return
        starting = true
        // Marked until the session ends: a crash while it's up points here.
        CrashGuard.enter(CrashGuard.Risky.OUTER_SCREEN)
        marked = true
        areas.presentContentOnWindowArea(info.token, activity, ContextCompat.getMainExecutor(activity), object : WindowAreaPresentationSessionCallback {
            override fun onSessionStarted(session: WindowAreaSessionPresenter) {
                starting = false
                this@OuterScreenPresenter.session = session
                session.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // The outer screen's window isn't part of the activity's views, so
                // Compose needs to be told whose lifecycle and state it follows.
                val view = ComposeView(session.context).apply {
                    setViewTreeLifecycleOwner(activity)
                    setViewTreeViewModelStoreOwner(activity)
                    setViewTreeSavedStateRegistryOwner(activity)
                    setContent { OuterScreenContent(conversation) }
                }
                session.setContentView(view)
                conversation.setOuterScreen(OuterScreen.SHOWING)
            }

            override fun onSessionEnded(t: Throwable?) {
                starting = false
                session = null
                unmark()
                if (conversation.state.value.outerScreen == OuterScreen.SHOWING) conversation.setOuterScreen(OuterScreen.UNAVAILABLE)
            }

            override fun onContainerVisibilityChanged(isVisible: Boolean) {}
        })
    }

    private var marked = false

    private fun unmark() {
        if (marked) {
            marked = false
            CrashGuard.exit(CrashGuard.Risky.OUTER_SCREEN)
        }
    }

    private fun endSession() {
        session?.close()
        session = null
        if (!starting) unmark()
    }

    /** Ends the outer-screen session (the activity is going away). */
    fun stop() = endSession()
}

/**
 * The outer screen: the last two turns in the other person's language, as
 * big as fits, newest at the bottom. White on black, readable at arm's
 * length and easy on the battery of an OLED cover screen.
 */
@Composable
private fun OuterScreenContent(conversation: ConversationController) {
    val state by conversation.state.collectAsState()
    val language = state.settings.theirLanguage
    val shown = (state.turns + listOfNotNull(state.live)).takeLast(2)
    val size = (state.settings.textSize * 1.3f).coerceAtLeast(32f)
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(20.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(LanguageNames.native(language), color = Color(0xFF9E9E9E), fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (state.listening) "…" else "", color = Color.White, fontSize = size.sp)
            }
        }
        shown.forEachIndexed { i, turn ->
            OuterTurn(turn, language, size, newest = i == shown.lastIndex)
            if (i < shown.lastIndex) Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun OuterTurn(turn: Turn, language: String, size: Float, newest: Boolean) {
    val isLive = turn.id < 0
    val full = turn.textIn(language) ?: if (turn.translationFailed) turn.text else "…"
    // A long turn shows its end: the words just said matter most.
    val limit = if (newest) 220 else 90
    val text = if (full.length > limit) "…" + full.takeLast(limit) else full
    val color = speakerColor(turn.speaker)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(ConversationController.speakerLabel(turn), color = color, fontSize = 15.sp)
            Text(
                text,
                color = Color.White.copy(alpha = if (newest) 1f else 0.65f),
                fontSize = (if (newest) size else size * 0.7f).sp,
                lineHeight = ((if (newest) size else size * 0.7f) * 1.15f).sp,
                fontWeight = if (newest) FontWeight.SemiBold else FontWeight.Normal,
                fontStyle = if (isLive) FontStyle.Italic else FontStyle.Normal,
            )
        }
    }
}
