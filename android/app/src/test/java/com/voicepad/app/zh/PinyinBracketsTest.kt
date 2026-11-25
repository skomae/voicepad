package com.voicepad.app.zh

import org.junit.Assert.assertEquals
import org.junit.Test

/** Glosses come from CC-CEDICT with numbered pinyin in brackets; the sheet shows tone marks. */
class PinyinBracketsTest {

    @Test
    fun `marks a cited word the way the characters are shown`() {
        assertEquals(
            "you (informal, as opposed to courteous 您[nín])",
            Pinyin.marksInBrackets("you (informal, as opposed to courteous 您[nin2])"),
        )
    }

    @Test
    fun `marks every syllable of a multi-syllable cite, neutral tone included`() {
        assertEquals(
            "relation [méi guān xi]",
            Pinyin.marksInBrackets("relation [mei2 guan1 xi5]"),
        )
    }

    @Test
    fun `leaves brackets without a tone digit alone`() {
        assertEquals("see [sic] and [3]", Pinyin.marksInBrackets("see [sic] and [3]"))
        assertEquals("no brackets here", Pinyin.marksInBrackets("no brackets here"))
    }
}
