package com.voicepad.app

import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.voicepad.app.conversation.ConversationController
import com.voicepad.app.conversation.ConversationController.Cover
import com.voicepad.app.ui.CoverStage
import com.voicepad.app.ui.VoicePadTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Shows the other person's side of conversation mode on a foldable's cover
 * screen (the one on the outside, facing away from you) while the inside
 * screen stays yours.
 *
 * This is Android's dual-screen mode: the phone has to offer its rear-facing
 * display to apps, which not every foldable does. What this phone reports is
 * passed to [ConversationController.setCover] and shown in conversation
 * settings; when it isn't offered, nothing here does anything.
 */
@OptIn(ExperimentalWindowApi::class)
class CoverScreen(
    private val activity: ComponentActivity,
    private val conversation: ConversationController,
    private val notes: RecordingController,
) : WindowAreaPresentationSessionCallback {
    private val areas = runCatching { WindowAreaController.getOrCreate() }.getOrNull()
    private var rear: WindowAreaInfo? = null
    private var session: WindowAreaSessionPresenter? = null
    private var starting = false
    /** The phone (or its owner, in the system's prompt) said no: don't ask again until it's wanted afresh. */
    private var refused = false
    private var wanted = false

    fun start() {
        val areas = areas ?: return conversation.setCover(Cover.UNSUPPORTED)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    areas.windowAreaInfos.collect { infos ->
                        rear = infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
                        report()
                        sync()
                    }
                }
                launch {
                    conversation.state
                        .map { it.visible && it.unfolded && it.settings.coverScreen }
                        .distinctUntilChanged()
                        .collect { want ->
                            wanted = want
                            if (!want) refused = false
                            sync()
                        }
                }
            }
        }
    }

    /** The activity is going away (folding recreates it): its presentation goes with it. */
    fun stop() {
        wanted = false
        session?.close()
        session = null
    }

    private fun status(): WindowAreaCapability.Status? =
        rear?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status

    private fun report() {
        conversation.setCover(
            when {
                session != null -> Cover.ACTIVE
                else -> when (status()) {
                    WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE -> Cover.AVAILABLE
                    WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> Cover.AVAILABLE
                    WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNAVAILABLE -> Cover.UNAVAILABLE
                    else -> Cover.UNSUPPORTED
                }
            }
        )
    }

    private fun sync() {
        val current = session
        if (!wanted) {
            current?.close()
            return
        }
        val area = rear ?: return
        if (current != null || starting || refused) return
        if (status() != WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE) return
        starting = true
        runCatching {
            areas?.presentContentOnWindowArea(area.token, activity, ContextCompat.getMainExecutor(activity), this)
        }.onFailure {
            starting = false
            refused = true
        }
    }

    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
        starting = false
        this.session = session
        val compose = ComposeView(session.context).apply {
            setContent {
                val state by notes.state.collectAsStateWithLifecycle()
                VoicePadTheme(state.themeMode) { CoverStage(conversation) }
            }
        }
        // The cover screen's window isn't the activity's, so Compose has to be
        // told whose lifecycle to follow before it attaches.
        val container = FrameLayout(session.context).apply {
            addView(compose)
            adopt(this)
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = adopt(v.rootView)
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
        session.setContentView(container)
        report()
        if (!wanted) session.close()
    }

    private fun adopt(view: View) {
        view.setViewTreeLifecycleOwner(activity)
        view.setViewTreeViewModelStoreOwner(activity)
        view.setViewTreeSavedStateRegistryOwner(activity)
    }

    override fun onSessionEnded(t: Throwable?) {
        starting = false
        session = null
        // Ended while still wanted: the system or its owner closed it. Leave it closed.
        if (wanted) refused = true
        report()
    }

    override fun onContainerVisibilityChanged(isVisible: Boolean) {}
}
