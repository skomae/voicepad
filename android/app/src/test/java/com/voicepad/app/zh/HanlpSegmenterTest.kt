package com.voicepad.app.zh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HanLP integration, checked on the JVM (the portable jar needs no Android):
 * word boundaries, that the offsets point at the words they came from, and the
 * pinyin fallback that labels hanzi the bundled dictionary doesn't know.
 */
class HanlpSegmenterTest {
    private val segmenter = HanlpSegmenter()

    private fun words(run: String) = segmenter.segment(run).map { (start, end) -> run.substring(start, end) }

    @Test
    fun `keeps a name the dictionary doesn't have as one word`() {
        // 张伟 is not a CC-CEDICT entry, so the dictionary-driven search can only
        // fall back to 张/伟; HanLP's own dictionary tags it nr, a person name.
        assertEquals(listOf("张伟"), words("张伟"))
    }

    @Test
    fun `cuts a compound into the words people read`() {
        // 北京 and 大学生 are both entries, so this is the ordinary case: agree with
        // HanLP rather than glue them into one improbable long word.
        assertEquals(listOf("北京", "大学生"), words("北京大学生"))
    }

    @Test
    fun `offsets point at the words they came from`() {
        // HanLP 1.8.4 reports Term.offset = 0 for every term, so this guards the
        // hand-counted offsets: they must slice out the words HanLP returned.
        for (run in listOf("这是一个测试", "我们吃晚饭了吗", "北京大学生")) {
            val spans = segmenter.segment(run)
            assertTrue("no words for $run", spans.isNotEmpty())
            assertEquals("starts at 0 for $run", 0, spans.first().first)
            assertEquals("ends at the end for $run", run.length, spans.last().second)
            spans.zipWithNext().forEach { (a, b) -> assertEquals("gap in $run", a.second, b.first) }
            spans.forEach { (start, end) ->
                assertTrue("empty or reversed word in $run", end > start)
                assertTrue("out of range in $run", end <= run.length)
                assertTrue("not Chinese at $run[$start,$end)", run.substring(start, end).any { isHan(it) })
            }
        }
        // With other scripts in the run, the Chinese words still follow each other,
        // but the last one ends before the letters do.
        val mixed = segmenter.segment("这是一个 test")
        mixed.zipWithNext().forEach { (a, b) -> assertEquals("gap in mixed run", a.second, b.first) }
        assertTrue("mixed run overran", mixed.last().second < "这是一个 test".length)
    }

    @Test
    fun `reads a single character`() {
        assertEquals("mei2", segmenter.numberedPinyin("没"))
    }

    @Test
    fun `numbers every syllable and never answers none`() {
        assertEquals("tao1 tie4", segmenter.numberedPinyin("饕餮"))
    }

    @Test
    fun `gives up rather than guess when a word is outside the pinyin table`() {
        assertNull(segmenter.numberedPinyin("𝕏"))
    }

    @Test
    fun `keeps non-Chinese out of the words`() {
        // HanLP cuts 这/是/一个 and keeps the space and "test" as terms of their own.
        assertEquals(listOf("这", "是", "一个"), words("这是一个 test"))
    }
}

private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
