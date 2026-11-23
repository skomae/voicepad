package com.voicepad.app.transcription

/**
 * Turns non-Latin text into Latin letters: pinyin with tone marks for
 * Mandarin, a general transliteration for scripts like Korean or Cyrillic.
 *
 * The actual ICU transliteration is passed in ([transliterate] takes an ICU
 * transform ID such as "Han-Latin" and the text), so the app can use
 * Android's built-in ICU and the unit tests can use ICU4J.
 */
class Romanizer(private val transliterate: (id: String, text: String) -> String?) {

    /** Romanised text for [language], or null when it wouldn't help. */
    fun romanize(language: String?, text: String): String? {
        if (text.isBlank() || !LanguageTags.hasNonLatinLetters(text)) return null
        return when (language) {
            "zh" -> pinyin(text)
            // Cantonese uses Jyutping, not pinyin, and kanji readings in
            // Japanese differ from Chinese; better none than a wrong guide.
            "yue", "ja" -> null
            else -> transliterate("Any-Latin; NFC", text)?.let(::tidy)
        }
    }

    /**
     * Pinyin with tone marks. ICU reads characters one at a time, which gets
     * common multi-reading words wrong (银行 → "yín xíng"); whole words from
     * [PINYIN_WORDS] are substituted first.
     */
    fun pinyin(text: String): String? {
        val out = StringBuilder()
        val han = StringBuilder()
        val other = StringBuilder()
        fun flushHan() {
            if (han.isEmpty()) return
            transliterate("Han-Latin", han.toString())?.let { out.append(' ').append(it).append(' ') }
            han.clear()
        }
        fun flushOther() {
            if (other.isEmpty()) return
            out.append(' ').append(other).append(' ')
            other.clear()
        }
        var i = 0
        while (i < text.length) {
            val word = (MAX_WORD downTo 2).firstNotNullOfOrNull { len ->
                if (i + len <= text.length) text.substring(i, i + len).takeIf { it in PINYIN_WORDS } else null
            }
            when {
                word != null -> {
                    flushHan(); flushOther()
                    out.append(' ').append(PINYIN_WORDS.getValue(word)).append(' ')
                    i += word.length
                }
                LanguageTags.containsHan(text[i].toString()) -> {
                    flushOther(); han.append(text[i]); i++
                }
                else -> {
                    // Numbers, Latin words and punctuation pass through untouched.
                    flushHan(); other.append(text[i]); i++
                }
            }
        }
        flushHan(); flushOther()
        return tidy(out.toString()).ifBlank { null }
    }

    /** Collapses spacing and turns full-width CJK punctuation into plain punctuation. */
    private fun tidy(text: String): String = text
        .replace('，', ',').replace('。', '.').replace('？', '?').replace('！', '!')
        .replace('：', ':').replace('；', ';').replace('、', ',')
        .replace(Regex("\\s+([,.?!:;])"), "$1")
        .replace(Regex("([,.?!:;])(?=\\S)"), "$1 ")
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        /** Everyday words whose reading differs from the characters' most common reading. */
        val PINYIN_WORDS: Map<String, String> = mapOf(
            "银行" to "yín háng", "行业" to "háng yè", "一行" to "yì háng",
            "觉得" to "jué de", "知道" to "zhī dao", "东西" to "dōng xi", "时候" to "shí hou",
            "这个" to "zhè ge", "那个" to "nà ge", "一个" to "yí gè", "哪个" to "nǎ ge",
            "什么" to "shén me", "怎么" to "zěn me", "为什么" to "wèi shén me", "多少" to "duō shao",
            "谢谢" to "xiè xie", "喜欢" to "xǐ huan", "朋友" to "péng you", "先生" to "xiān sheng",
            "音乐" to "yīn yuè", "长大" to "zhǎng dà", "成长" to "chéng zhǎng", "校长" to "xiào zhǎng",
            "重新" to "chóng xīn", "还是" to "hái shi", "还有" to "hái yǒu", "得到" to "dé dào",
            "睡觉" to "shuì jiào", "觉醒" to "jué xǐng", "地方" to "dì fang", "明白" to "míng bai",
            "妈妈" to "mā ma", "爸爸" to "bà ba", "哥哥" to "gē ge", "姐姐" to "jiě jie",
            "弟弟" to "dì di", "妹妹" to "mèi mei", "孩子" to "hái zi",
            "了解" to "liǎo jiě", "为了" to "wèi le", "因为" to "yīn wèi", "不客气" to "bú kè qi",
            "不是" to "bú shì", "不要" to "bú yào", "不会" to "bú huì", "不对" to "bú duì",
            "一样" to "yí yàng", "一下" to "yí xià", "一定" to "yí dìng", "一起" to "yì qǐ",
            "早上" to "zǎo shang", "晚上" to "wǎn shang", "上午" to "shàng wǔ",
            "数据" to "shù jù", "会计" to "kuài jì", "调查" to "diào chá", "空调" to "kōng tiáo",
        )

        private val MAX_WORD = PINYIN_WORDS.keys.maxOf { it.length }
    }
}
