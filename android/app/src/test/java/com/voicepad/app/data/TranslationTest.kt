package com.voicepad.app.data

import com.ibm.icu.text.Transliterator
import com.voicepad.app.transcription.LanguageTags
import com.voicepad.app.transcription.Romanizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTest {
    // ICU4J runs the same transforms Android's built-in ICU does.
    private val romanizer = Romanizer { id, text -> Transliterator.getInstance(id).transliterate(text) }

    @Test
    fun `pinyin has tone marks and fixes words ICU reads one character at a time`() {
        assertEquals(
            "nǐ hǎo, wǒ men míng tiān qù yín háng kāi huì.",
            romanizer.romanize("zh", "你好，我们明天去银行开会。"),
        )
        assertEquals("wǒ jué de zhè ge jì huà hěn hǎo", romanizer.romanize("zh", "我觉得这个计划很好"))
        assertEquals("wǒ xǐ huan yīn yuè", romanizer.romanize("zh", "我喜欢音乐"))
    }

    @Test
    fun `pinyin keeps numbers and latin words`() {
        assertEquals("kāi fàng shí jiān zǎo shang 9 diǎn", romanizer.romanize("zh", "开放时间早上9点"))
        assertEquals("wǒ yòng iPhone", romanizer.romanize("zh", "我用iPhone"))
    }

    @Test
    fun `other scripts are transliterated, latin-script languages and cantonese are not`() {
        assertEquals("annyeonghaseyo", romanizer.romanize("ko", "안녕하세요"))
        assertEquals("privet", romanizer.romanize("ru", "привет"))
        assertNull(romanizer.romanize("fr", "Bonjour à tous"))
        assertNull(romanizer.romanize("es", "¿Dónde está la biblioteca?"))
        assertNull(romanizer.romanize("yue", "你哋好"))
        assertNull(romanizer.romanize("ja", "こんにちは"))
    }

    @Test
    fun `language tags from speech models are normalised`() {
        assertEquals("zh", LanguageTags.fromModel("<|zh|>"))
        assertEquals("fr", LanguageTags.fromModel(" fr "))
        assertEquals("yue", LanguageTags.fromModel("<|yue|>"))
        assertNull(LanguageTags.fromModel(""))
        assertNull(LanguageTags.fromModel("<|nospeech|>"))
        assertNull(LanguageTags.fromModel("und"))
    }

    @Test
    fun `language decision prefers the speech model and trusts Chinese characters`() {
        assertEquals("fr", LanguageTags.decide("fr", "en", "Bonjour"))
        assertEquals("es", LanguageTags.decide(null, "es", "Hola amigos"))
        // Text detectors often call short Chinese "ja".
        assertEquals("zh", LanguageTags.decide(null, "ja", "你好"))
        assertEquals("ja", LanguageTags.decide(null, "ja", "こんにちは世界"))
        assertEquals("yue", LanguageTags.decide("<|yue|>", "zh", "你哋好"))
        assertEquals("zh", LanguageTags.decide(null, "zh-Latn", "ni hao"))
        assertNull(LanguageTags.decide(null, "und", "..."))
    }

    @Test
    fun `display names`() {
        assertEquals("Mandarin Chinese", LanguageTags.displayName("zh"))
        assertEquals("Cantonese", LanguageTags.displayName("yue"))
        assertEquals("French", LanguageTags.displayName("fr"))
        assertEquals("Spanish", LanguageTags.displayName("es"))
    }

    private fun noteWith(text: String, translation: NoteTranslation? = null): Note {
        val rev = Revision(id = "r1", text = text, source = RevisionSource.TRANSCRIPTION)
        return Note(id = "n", revisions = listOf(rev), translation = translation)
    }

    @Test
    fun `a note needs translating until its current text has a result`() {
        assertTrue(noteWith("你好").needsTranslation(3))
        assertFalse(noteWith("").needsTranslation(3))
        assertFalse(noteWith("你好", NoteTranslation("r1", TranslationStatus.DONE, "zh", "Hello")).needsTranslation(3))
        assertFalse(noteWith("hi", NoteTranslation("r1", TranslationStatus.NOT_NEEDED, "en")).needsTranslation(3))
        // Interrupted mid-way (app killed): try again.
        assertTrue(noteWith("你好", NoteTranslation("r1", TranslationStatus.PENDING, attempts = 1)).needsTranslation(3))
        // Failed: retry until the limit.
        assertTrue(noteWith("你好", NoteTranslation("r1", TranslationStatus.FAILED, attempts = 2)).needsTranslation(3))
        assertFalse(noteWith("你好", NoteTranslation("r1", TranslationStatus.FAILED, attempts = 3)).needsTranslation(3))
    }

    @Test
    fun `editing the text makes the old translation stale`() {
        val old = NoteTranslation("r1", TranslationStatus.DONE, "zh", "Hello")
        val edited = noteWith("你好", old).withRevision("你好吗", RevisionSource.EDIT, null)
        assertNull(edited.currentTranslation)
        assertTrue(edited.needsTranslation(3))
    }

    @Test
    fun `search finds notes by their English translation and by toneless pinyin`() {
        val note = noteWith(
            "我们明天去银行",
            NoteTranslation("r1", TranslationStatus.DONE, "zh", "We are going to the bank tomorrow", "wǒ men míng tiān qù yín háng"),
        )
        assertEquals(1, NoteSearch.search(listOf(note), "bank").size)
        assertEquals(1, NoteSearch.search(listOf(note), "\"yin hang\"").size)
        assertEquals(1, NoteSearch.search(listOf(note), "银行").size)
    }
}
