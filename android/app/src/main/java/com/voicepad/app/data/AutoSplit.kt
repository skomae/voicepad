package com.voicepad.app.data

/**
 * When to start a new note automatically while recording. Pure Kotlin so the
 * rules can be unit-tested.
 */
enum class AutoSplit(val label: String, val badge: String?, val silenceSeconds: Int?, val everyMinutes: Int?) {
    OFF("Off", null, null, null),
    SILENCE_2S("After 2 s of silence", "2s", 2, null),
    SILENCE_3S("After 3 s of silence", "3s", 3, null),
    SILENCE_5S("After 5 s of silence", "5s", 5, null),
    SILENCE_10S("After 10 s of silence", "10s", 10, null),
    SILENCE_30S("After 30 s of silence", "30s", 30, null),
    EVERY_5_MIN("Every 5 minutes", "5m", null, 5),
    EVERY_15_MIN("Every 15 minutes", "15m", null, 15),
    EVERY_30_MIN("Every 30 minutes", "30m", null, 30);

    /**
     * Whether to split now. Positions are in samples at [rate].
     *
     * @param noteStart where the current note began.
     * @param now how much has been recorded.
     * @param speaking the voice detector hears speech right now.
     * @param lastVoiceAt last moment speech was heard in this note (null: none yet).
     */
    fun shouldSplit(noteStart: Int, now: Int, speaking: Boolean, lastVoiceAt: Int?, rate: Int): Boolean {
        // Never split off a note with nothing said in it.
        if (lastVoiceAt == null) return false
        if (speaking) return everyMinutes != null && overdue(noteStart, now, rate)
        silenceSeconds?.let { return now - lastVoiceAt >= it * rate }
        everyMinutes?.let {
            // At the first pause after the interval (no word gets cut)…
            return now - noteStart >= it * 60 * rate && now - lastVoiceAt >= PAUSE_SECONDS * rate
        }
        return false
    }

    /** …or regardless, if nobody has paused for a minute past the interval. */
    private fun overdue(noteStart: Int, now: Int, rate: Int): Boolean =
        now - noteStart >= (everyMinutes!! * 60 + GRACE_SECONDS) * rate

    companion object {
        /** A pause at least this long counts as a good place to split. */
        const val PAUSE_SECONDS = 0.7
        const val GRACE_SECONDS = 60
    }
}
