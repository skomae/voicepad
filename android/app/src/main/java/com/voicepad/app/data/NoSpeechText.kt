package com.voicepad.app.data

/**
 * Decides whether a transcript is worth keeping, for audio the speech detector found
 * nothing in.
 *
 * Asked to transcribe silence, the model does not return nothing — it returns ".", "。 。 。",
 * "Yeah." or "Thank you." Those are what it does when there is nothing to hear, so a note
 * only survives the second check if its audio produced actual words. Pure Kotlin so the
 * rule can be tested without a model.
 */
object NoSpeechText {
    /** What the model says when asked to transcribe silence. */
    private val FILLERS = setOf(
        "yeah", "yes", "thank you", "thanks", "okay", "ok", "bye", "you", "your",
        "hmm", "uh", "um", "oh", "ah",
        "啊", "嗯", "哎", "哦", "呃",
    )

    /**
     * At least two letters or Hanzi, and not one of the fillers. Two Hanzi carry a word
     * ("你好"); two Latin letters usually don't ("ok"), so Latin needs three.
     */
    fun isSubstantive(text: String): Boolean {
        val trimmed = text.trim()
        val letters = trimmed.count { it.isLetter() }
        val hanzi = trimmed.count { it in '\u4e00'..'\u9fff' }
        if (letters < 2 || (letters < 3 && hanzi == 0)) return false
        return trimmed.lowercase().trim('.', '，', '。', '!', '?', ' ') !in FILLERS
    }
}
