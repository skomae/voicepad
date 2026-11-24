package com.voicepad.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers which risky native feature was running when the app died.
 *
 * A native crash kills the process without running any handler, so before
 * each such step the feature's name is written to disk (synchronously) and
 * removed afterwards. Whatever is still written at the next launch was
 * running when the app closed — the likely culprit — and the user is asked
 * whether to turn it off before it runs again.
 */
object CrashGuard {
    enum class Risky(val doing: String, val offer: String, val keep: String) {
        MULTI_CHANNEL("recording several microphone channels", "Use 1 channel", "Keep several"),
        VOICE_ID("telling speakers apart by voice", "Turn it off", "Keep it on"),
        OUTER_SCREEN("showing the conversation on the outer screen", "Stop using it", "Keep using it"),
        LOCAL_VOICE("speaking with an on-device voice", "Use the phone's voice", "Keep on-device voices"),
    }

    private const val KEY = "running"
    private var prefs: SharedPreferences? = null
    private val counts = mutableMapOf<Risky, Int>()

    /** What was running when the app last closed unexpectedly (most important first). */
    var crashedDuring: List<Risky> = emptyList()
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("crashGuard", Context.MODE_PRIVATE)
        prefs = p
        val left = p.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { runCatching { Risky.valueOf(it) }.getOrNull() }.toMutableSet()
        // The earlier, channel-only marker.
        val legacy = context.getSharedPreferences("conversation", Context.MODE_PRIVATE)
        if (legacy.getInt("multiChannelRecording", 0) > 1) left += Risky.MULTI_CHANNEL
        legacy.edit().remove("multiChannelRecording").apply()
        crashedDuring = Risky.entries.filter { it in left }
        p.edit().remove(KEY).commit()
    }

    /** The user answered about [risky] (or it no longer matters). */
    fun resolved(risky: Risky) {
        crashedDuring = crashedDuring - risky
    }

    @Synchronized fun enter(risky: Risky) {
        counts[risky] = (counts[risky] ?: 0) + 1
        save()
    }

    @Synchronized fun exit(risky: Risky) {
        val n = (counts[risky] ?: 0) - 1
        if (n <= 0) counts.remove(risky) else counts[risky] = n
        save()
    }

    /** Runs [block] marked as [risky]. */
    inline fun <T> during(risky: Risky, block: () -> T): T {
        enter(risky)
        try {
            return block()
        } finally {
            exit(risky)
        }
    }

    private fun save() {
        // commit(), not apply(): the write must be on disk before a crash can happen.
        prefs?.edit()?.putStringSet(KEY, counts.keys.map { it.name }.toSet())?.commit()
    }
}
