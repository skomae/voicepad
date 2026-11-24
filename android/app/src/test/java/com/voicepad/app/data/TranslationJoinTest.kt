package com.voicepad.app.data

import com.voicepad.app.data.TranslationJoin.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationJoinTest {
    @Test
    fun `mixed Mandarin and English reads as one English text`() {
        val t = TranslationJoin.combine(
            listOf(
                Part("我们明天去银行", "zh", "We're going to the bank tomorrow", "wǒ men míng tiān qù yín háng", true),
                Part("and then lunch", "en", "and then lunch", null, true),
                Part("你好", "zh", "Hello", "nǐ hǎo", true),
            ),
            "r1",
        )!!
        assertEquals(TranslationStatus.DONE, t.status)
        assertEquals("zh", t.language)
        assertEquals("We're going to the bank tomorrow and then lunch Hello", t.english)
        assertEquals("wǒ men míng tiān qù yín háng nǐ hǎo", t.romanization)
    }

    @Test
    fun `all English needs no translation`() {
        val t = TranslationJoin.combine(listOf(Part("hi", "en", "hi", null, true)), "r1")!!
        assertEquals(TranslationStatus.NOT_NEEDED, t.status)
    }

    @Test
    fun `shows translating until the first part arrives, then partial English`() {
        val waiting = TranslationJoin.combine(listOf(Part("你好", "zh", null, null, false)), "live")!!
        assertEquals(TranslationStatus.PENDING, waiting.status)
        val partial = TranslationJoin.combine(
            listOf(Part("你好", "zh", "Hello", "nǐ hǎo", true), Part("谢谢", "zh", null, null, false)),
            "live",
        )!!
        assertEquals(TranslationStatus.DONE, partial.status)
        assertEquals("Hello", partial.english)
    }

    @Test
    fun `nothing spoken yet`() {
        assertNull(TranslationJoin.combine(emptyList(), "live"))
    }
}
