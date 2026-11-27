package com.voicepad.app.glasses

import android.content.Context
import com.voicepad.app.conversation.ConversationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a pair of Even Realities G1 glasses showing the conversation.
 *
 * Conversation mode does the listening, transcribing and translating; this
 * turns its stage into pages of five 40-column lines ([GlassesScreen]) and
 * writes each page once, only when it changes. The newest words win: a page
 * still on its way is dropped rather than queued behind a newer one.
 */
class GlassesController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val link: GlassesLink = BasisGlassesLink(context, scope)

    val status: StateFlow<GlassesStatus> = link.status

    /** Collecting the conversation and writing pages; null while the glasses are off. */
    private var session: Job? = null
    /** Clearing the display and letting the glasses go, before the next session starts. */
    private var closing: Job? = null
    /** The page already on the glasses, so the same one isn't written twice. */
    private var sent: List<String>? = null

    /** Whether the app still needs permission to use Bluetooth. */
    fun needsPermission(): Boolean = !BasisGlassesLink.hasPermission(context)

    /** The permissions to ask for (an empty array on Android 11 and older). */
    fun permissions(): Array<String> = BasisGlassesLink.permissions()

    /** Starts showing [conversation] on the glasses, connecting them if need be. */
    fun follow(conversation: StateFlow<ConversationController.UiState>) {
        if (session?.isActive == true) {
            // Already showing: still worth reconnecting (permission just granted, or asked to look again).
            link.connect()
            return
        }
        sent = null
        val previousClose = closing
        session = scope.launch {
            // A stop still finishing would otherwise disconnect the link just
            // after this session connected it.
            previousClose?.join()
            link.connect()
            combine(
                conversation.map { page(it) }.distinctUntilChanged(),
                link.status,
            ) { page, status -> page to (status is GlassesStatus.Connected) }
                .conflate()
                .collect { (page, connected) ->
                    if (!connected) {
                        // Whatever was on the glasses is gone with the link.
                        sent = null
                        return@collect
                    }
                    if (page == null || page == sent) return@collect
                    if (link.show(page)) sent = page
                }
        }
    }

    /** Looks for the glasses again after they weren't found, were turned off, or were refused. */
    fun retry() {
        link.connect()
    }

    /** Stops showing anything and lets the glasses go. */
    fun stop() {
        session?.cancel()
        session = null
        sent = null
        closing = scope.launch {
            link.clear()
            link.close()
        }
    }

    private fun page(state: ConversationController.UiState): List<String>? {
        if (!state.visible) return null
        val settings = state.settings
        return GlassesScreen.of(
            stage = state.stage,
            myLanguage = settings.myLanguage,
            theirLanguage = settings.theirLanguage,
            scope = settings.glassesScope,
            listening = state.listening,
        )
    }

}
