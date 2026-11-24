package com.voicepad.app.conversation

/**
 * What the conversation screen puts centre stage: the newest thought, big,
 * with everything older receding behind it.
 */
data class Stage(
    /** The newest thought; null before anyone has spoken. */
    val turn: Turn?,
    val phase: Phase,
    /** Everything before [turn], oldest first. */
    val history: List<Turn>,
) {
    enum class Phase {
        /** Nothing said yet. */
        IDLE,
        /** Words are arriving. */
        SPEAKING,
        /** A pause mid-thought: the turn stays open, untranslated, in case the speaker goes on. */
        HOLDING,
        /** The models are working: transcribing the finished utterance or translating it. */
        WORKING,
        /** Translated and settled; it stays on stage until the next turn starts. */
        LANDED,
    }

    companion object {
        fun of(turns: List<Turn>, live: Turn?, working: Boolean): Stage {
            val last = turns.lastOrNull()
            if (live != null) {
                // Speech resumed inside a held turn: show the whole thought, not only its newest piece.
                val resumed = last != null && last.held && last.language == live.language
                val turn = if (resumed) {
                    last.copy(
                        text = Turns.join(last.text, live.text),
                        translation = joinOrNull(last.translation, live.translation),
                        pending = true, held = false,
                    )
                } else live.copy(pending = true)
                return Stage(turn, if (working) Phase.WORKING else Phase.SPEAKING, if (resumed) turns.dropLast(1) else turns)
            }
            if (last == null) return Stage(null, Phase.IDLE, emptyList())
            val phase = when {
                last.held -> Phase.HOLDING
                last.pending || working -> Phase.WORKING
                else -> Phase.LANDED
            }
            return Stage(last, phase, turns.dropLast(1))
        }

        private fun joinOrNull(a: String?, b: String?): String? = when {
            a == null -> b
            b == null -> a
            else -> Turns.join(a, b)
        }
    }
}

/** Stand-in characters shown while a translation is on its way. */
object Glyphs {
    private const val LATIN = "abcdefghijklmnopqrstuvwxyz"
    private const val HAN = "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用样道想作种开美总从无情面最但现前些所同日手又行意动方期它头经长回位分爱老因很给名法间知世什两次使身者被高已亲其进此话常与活正感"
    private const val KANA = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
    private const val HANGUL = "가나다라마바사아자차카타파하고노도로모보소오조초코토포호구누두루무부수우주"
    private const val CYRILLIC = "абвгдежзиклмнопрстуфхцчшыэюя"
    private const val GREEK = "αβγδεζηθικλμνξοπρστυφχψω"
    private const val ARABIC = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"
    private const val HEBREW = "אבגדהוזחטיכלמנסעפצקרשת"
    private const val DEVANAGARI = "अआइईउऊएऐओऔकखगघचछजझटठडढणतथदधनपफबभमयरलवशषसह"
    private const val THAI = "กขคงจฉชซญดตถทนบปผฝพฟภมยรลวศสหอ"

    /** Characters that look like writing in [language]. */
    fun pool(language: String): String = when (language) {
        "zh" -> HAN
        "ja" -> KANA + HAN.take(60)
        "ko" -> HANGUL
        "ru", "uk", "bg", "be", "mk" -> CYRILLIC
        "el" -> GREEK
        "ar", "fa", "ur" -> ARABIC
        "he" -> HEBREW
        "hi", "mr" -> DEVANAGARI
        "th" -> THAI
        else -> LATIN
    }

    /** Whether [language] is written without spaces, a character or two per word. */
    fun isDense(language: String) = language == "zh" || language == "ja"

    /** Roughly how many characters [source] in language [from] comes to in [to]. */
    fun estimateLength(source: String, from: String, to: String): Int {
        val ratio = when {
            isDense(from) && !isDense(to) -> 3.1
            !isDense(from) && isDense(to) -> 0.34
            else -> 1.0
        }
        return (source.length * ratio).toInt().coerceIn(2, 160)
    }

    /** Whether position [index] of a stand-in for [language] is a gap between words. */
    fun isGap(language: String, index: Int) = !isDense(language) && language != "th" && index % 6 == 5
}
