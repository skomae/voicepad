package com.voicepad.app.glasses

import kotlinx.coroutines.flow.StateFlow

/** Where the glasses are in being found and connected. */
sealed interface GlassesStatus {
    /** Not in use. */
    data object Off : GlassesStatus
    /** The app may not scan for or connect to Bluetooth devices yet. */
    data object NeedsPermission : GlassesStatus
    data object Scanning : GlassesStatus
    /** Looked, and found nothing: they're off, or another app has them. */
    data object NotFound : GlassesStatus
    data class Connecting(val name: String) : GlassesStatus
    /** [battery] is the weaker arm's percentage, when the glasses report it. */
    data class Connected(val name: String, val battery: Int?) : GlassesStatus
    data object Disconnected : GlassesStatus
    data class Failed(val message: String) : GlassesStatus
}

/** A pair of glasses that can be sent pages of text. */
interface GlassesLink {
    val status: StateFlow<GlassesStatus>

    /** Looks for a pair and connects, unless already connected or looking. */
    fun connect()

    /** Puts [page] on the display; false if it couldn't be shown right now. */
    suspend fun show(page: List<String>): Boolean

    /** Leaves the glasses back on their own idle screen. */
    suspend fun clear()

    /** Stops looking and disconnects. */
    suspend fun close()
}
