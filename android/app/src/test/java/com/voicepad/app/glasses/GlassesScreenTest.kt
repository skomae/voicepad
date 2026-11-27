package com.voicepad.app.glasses

import com.voicepad.app.conversation.SpeakerRef
import com.voicepad.app.conversation.SpeakerSource
import com.voicepad.app.conversation.Stage
import com.voicepad.app.conversation.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glasses are 5 lines of 40 columns; a page has to fit, whatever is said. */
class GlassesScreenTest {

    private var nextId = 1L

    private fun turn(
        language: String,
        text: String,
        translation: String? = null,
        target: String = if (language == "zh") "en" else "zh",
        romanization: String? = null,
    ) = Turn(
        id = nextId++,
        session = 1,
        speaker = SpeakerRef(SpeakerSource.VOICE, if (language == "zh") 1 else 0),
        language = language,
        text = text,
        target = target,
        translation = translation,
        romanization = romanization,
        start = 0,
        end = 16000,
    )

    private fun page(
        turns: List<Turn>,
        scope: GlassesScope = GlassesScope.THEM,
        listening: Boolean = true,
        // ICU isn't on the JVM test classpath, so pinyin has to be supplied.
        dictionary: (Char) -> String? = { null },
        myLanguage: String = "en",
        theirLanguage: String = "zh",
    ): List<String> =
        GlassesScreen.of(
            stage = Stage(turns.lastOrNull(), Stage.Phase.LANDED, turns.dropLast(1)),
            myLanguage = myLanguage,
            theirLanguage = theirLanguage,
            scope = scope,
            listening = listening,
            syllable = dictionary,
        )

    private fun assertFits(page: List<String>) {
        assertEquals("a page is always five lines", GlassesScreen.LINES, page.size)
        page.forEach { line ->
            assertTrue("line too wide: '$line' is ${GlassesScreen.width(line)}", GlassesScreen.width(line) <= GlassesScreen.COLUMNS)
        }
    }

    @Test
    fun `idle page names the two languages`() {
        val page = page(emptyList())
        assertFits(page)
        assertTrue(page.any { it.contains("ZH → EN") })
        assertTrue(page.any { it.contains("listening") })
    }

    @Test
    fun `paused page says so`() {
        assertTrue(page(emptyList(), listening = false).any { it.contains("paused") })
    }

    @Test
    fun `their speech is shown translated`() {
        val page = page(listOf(turn("zh", "你好，今天天气怎么样", "Hello, how is the weather today")))
        assertFits(page)
        assertEquals("Hello, how is the weather today", page.last().trim())
    }

    @Test
    fun `a translation that hasn't landed leaves the last one up`() {
        val page = page(listOf(turn("zh", "你好")))
        assertFits(page)
        assertEquals("…", page.last().trim())
    }

    @Test
    fun `a failed translation shows what was said`() {
        val failed = turn("zh", "你好").copy(translationFailed = true)
        assertEquals("你好", page(listOf(failed)).last().trim())
    }

    @Test
    fun `only their speech is shown when the scope is them`() {
        val mine = turn("en", "I would like two coffees please", "我想要两杯咖啡")
        val theirs = turn("zh", "好的，马上来", "Sure, right away")
        val page = page(listOf(mine, theirs), GlassesScope.THEM)
        assertFits(page)
        assertTrue(page.none { it.contains("two coffees") })
        assertTrue(page.any { it.contains("Sure, right away") })
    }

    @Test
    fun `both sides are marked with who said them`() {
        val mine = turn("en", "Two coffees please", "两杯咖啡")
        val theirs = turn("zh", "好的", "Sure")
        val page = page(listOf(mine, theirs), GlassesScope.BOTH)
        assertFits(page)
        assertTrue(page.any { it.startsWith("- Two coffees please") })
        assertTrue(page.any { it.startsWith("> Sure") })
    }

    @Test
    fun `a turn still being translated is marked like the rest`() {
        val mine = turn("en", "Two coffees please", "两杯咖啡")
        val pending = turn("zh", "好的", null)
        val page = page(listOf(mine, pending), GlassesScope.BOTH)
        assertEquals("> …", page.last())
    }

    @Test
    fun `a long turn keeps its newest words`() {
        val long = (1..40).joinToString(" ") { "word$it" }
        val page = page(listOf(turn("zh", "很长的句子", long)))
        assertFits(page)
        assertTrue("the end of the turn is what's on screen", page.last().trim().endsWith("word40"))
        assertTrue(page.none { it.contains("word1 ") })
    }

    @Test
    fun `older turns scroll off the top`() {
        val turns = (1..6).map { turn("zh", "句子$it", "Sentence number $it") }
        val page = page(turns)
        assertFits(page)
        assertTrue(page.none { it.contains("Sentence number 1") })
        assertTrue(page.any { it.contains("Sentence number 6") })
    }

    // region Learning

    @Test
    fun `learning shows what it means, then the characters with their pinyin`() {
        val theirs = turn("zh", "晚上好", "Good evening", romanization = "wǎn shàng hǎo")
        val page = page(listOf(theirs), GlassesScope.LEARNING)
        assertFits(page)
        assertEquals("> Good evening", page[3].trim())
        assertEquals("晚wǎn 上shàng 好hǎo", page[4].trim())
    }

    @Test
    fun `learning translates your own words too`() {
        val mine = turn("en", "Two coffees please", "两杯咖啡")
        val page = page(listOf(mine), GlassesScope.LEARNING, dictionary = { c -> if (c == '两') "liǎng" else "bei" })
        assertFits(page)
        assertEquals("- Two coffees please", page[3].trim())
        assertEquals("两liǎng 杯bei 咖bei 啡bei", page[4].trim())
    }

    @Test
    fun `learning marks whose turn it is`() {
        val mine = turn("en", "Two coffees", "两杯咖啡")
        val theirs = turn("zh", "好的", "Sure", romanization = "hǎo de")
        val page = page(listOf(mine, theirs), GlassesScope.LEARNING)
        assertFits(page)
        assertTrue(page.any { it.startsWith("- Two coffees") })
        assertTrue(page.any { it.startsWith("> Sure") })
    }

    @Test
    fun `learning trusts the pinyin written for the sentence`() {
        // 银行 is yín háng here; a per-character lookup says yín xíng.
        val theirs = turn("zh", "银行", "Bank", romanization = "yín háng")
        val page = page(listOf(theirs), GlassesScope.LEARNING, dictionary = { "xíng" })
        assertEquals("银yín 行háng", page.last().trim())
    }

    @Test
    fun `learning looks each character up when the turn has no romanisation`() {
        val theirs = turn("zh", "银行", "Bank")
        val page = page(listOf(theirs), GlassesScope.LEARNING, dictionary = { c -> if (c == '银') "yín" else "háng" })
        assertEquals("银yín 行háng", page.last().trim())
    }

    @Test
    fun `learning keeps punctuation with the word before it`() {
        val theirs = turn("zh", "晚上好！", "Good evening!", romanization = "wǎn shàng hǎo")
        val page = page(listOf(theirs), GlassesScope.LEARNING)
        assertEquals("晚wǎn 上shàng 好hǎo！", page.last().trim())
    }

    @Test
    fun `learning leaves latin words and numbers alone`() {
        val theirs = turn("zh", "去 7-Eleven 吗", "Going to 7-Eleven?", romanization = "qù ma")
        val page = page(listOf(theirs), GlassesScope.LEARNING, dictionary = { "x" })
        assertFits(page)
        assertTrue(page.any { it.contains("7-Eleven") })
        assertTrue(page.none { it.contains("7x") })
    }

    @Test
    fun `learning shows their words when there is no pinyin to add`() {
        val theirs = turn("es", "Buenas noches", "Good evening")
        val page = page(listOf(theirs), GlassesScope.LEARNING)
        assertFits(page)
        assertEquals("> Good evening", page[3].trim())
        assertEquals("Buenas noches", page[4].trim())
    }

    @Test
    fun `a long learning turn still fits five lines of forty columns`() {
        val chinese = "今天我们去看电影吧好吗".repeat(3)
        val theirs = turn("zh", chinese, "Shall we go to the cinema today?")
        val page = page(listOf(theirs), GlassesScope.LEARNING, dictionary = { "shi" })
        assertFits(page)
    }


    @Test
    fun `learning puts the pinyin on the chinese line when you read chinese`() {
        // A Chinese reader, hearing English: the Chinese is the translation.
        val pinyin = mapOf('葡' to "pú", '萄' to "tao", '酒' to "jiǔ", '吗' to "ma")
        val theirs = turn("en", "Wine?", "葡萄酒吗？")
        val page = page(listOf(theirs), GlassesScope.LEARNING, dictionary = { pinyin[it] }, myLanguage = "zh", theirLanguage = "en")
        assertFits(page)
        assertEquals("> 葡pú 萄tao 酒jiǔ 吗ma？", page[3].trim())
        assertEquals("Wine?", page[4].trim())
    }

    @Test
    fun `learning annotates the chinese of your own turn when you read chinese`() {
        val mine = turn("zh", "葡萄酒", "Wine")
        val page = page(listOf(mine), GlassesScope.LEARNING, dictionary = { mapOf('葡' to "pú", '萄' to "tao", '酒' to "jiǔ")[it] }, myLanguage = "zh")
        assertFits(page)
        assertEquals("- 葡pú 萄tao 酒jiǔ", page[3].trim())
        assertEquals("Wine", page[4].trim())
    }

    // endregion

    @Test
    fun `chinese counts two columns, so a line holds twenty characters`() {
        val chinese = "今天我们去看电影吧好吗".repeat(4) // 44 characters
        val lines = GlassesScreen.wrap(chinese, 40)
        assertTrue(lines.size >= 3)
        lines.forEach { assertTrue(GlassesScreen.width(it) <= 40) }
        assertTrue(lines.all { it.length <= 20 })
    }

    @Test
    fun `words are not split when they fit`() {
        val lines = GlassesScreen.wrap("one two three four five six seven", 12)
        assertEquals(listOf("one two", "three four", "five six", "seven"), lines)
    }

    @Test
    fun `a word longer than the line is cut`() {
        assertEquals(listOf("a".repeat(40), "a".repeat(5)), GlassesScreen.wrap("a".repeat(45)))
    }

    @Test
    fun `punctuation is not left at the start of a line`() {
        // "好，" is one token: a closing mark stays with the character before it.
        assertEquals(listOf("你好，", "世界"), GlassesScreen.wrap("你好，世界", 6))
        assertTrue(GlassesScreen.wrap("你好，世界", 4).none { it.startsWith("，") })
    }

    @Test
    fun `runs of spaces and newlines collapse`() {
        assertEquals(listOf("hello world"), GlassesScreen.wrap("  hello\n\n  world  "))
    }
}
