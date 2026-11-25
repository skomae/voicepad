package com.voicepad.app.glasses.basis

/**
 * The name one arm of a pair of Even Realities G1 glasses advertises.
 *
 * Firmware revisions disagree about the prefix: the official demo app pairs
 * `G1_<channel>_<L|R>_<serial>` (e.g. `G1_45_L_92333`), while the Basis
 * library this package is taken from only ever matched `Even G1_...`. Both
 * shapes are accepted here, and any other name carrying "G1" is still
 * reported (without a side), so a firmware change can't make the glasses
 * invisible to the scan.
 */
data class G1Name(
    val raw: String,
    /** The channel both arms of a pair share, e.g. "45". */
    val channel: String?,
    val side: Side?,
    val serial: String?,
) {
    enum class Side(val label: String) { LEFT("L"), RIGHT("R") }

    /** What pairs two arms: the channel, or the whole name when there is none. */
    val pairKey: String get() = channel ?: raw

    companion object {
        fun parse(raw: String?): G1Name? {
            // "Even G1_45_L_123" and "G1_45_L_123" are the same advertisement with
            // different firmware prefixes; and some firmware puts a space in it.
            var name = raw?.trim().orEmpty()
            if (name.uppercase().startsWith("EVEN G1")) name = name.substring("Even ".length).trim()
            if (!name.uppercase().startsWith("G1")) return null
            val tokens = name.split('_').filter { it.isNotBlank() }
            val sideIndex = tokens.indexOfFirst { token ->
                token.uppercase() in setOf("L", "LEFT", "R", "RIGHT")
            }
            if (sideIndex < 0) return G1Name(name, null, null, null)
            val side = if (tokens[sideIndex].uppercase().startsWith("L")) Side.LEFT else Side.RIGHT
            val channel = tokens.getOrNull(sideIndex - 1)?.takeIf { it.all(Char::isDigit) }
            val serial = tokens.drop(sideIndex + 1).joinToString("_").takeIf { it.isNotEmpty() }
            return G1Name(name, channel, side, serial)
        }
    }
}
