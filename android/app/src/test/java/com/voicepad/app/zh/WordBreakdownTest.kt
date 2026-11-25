package com.voicepad.app.zh

import com.voicepad.app.conversation.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordBreakdownTest {
    // A few real CC-CEDICT lines, with jieba frequencies merged in as the build does.
    private val dictionary = ZhDictionary.parse(
        """
        # CC-CEDICT excerpt
        22128	行 行 [hang2] /(bound form) row; line/(bound form) line of business; trade; profession/classifier for rows or lines/
        22128	行 行 [xing2] /(bound form) to walk; to go; to travel/(literary) trip; journey; visit/capable; competent/all right; OK!; will do/
        13	銀 银 [yin2] /silver/silver-colored/relating to money or currency/surname Yin/
        7684	銀行 银行 [yin2 hang2] /bank/CL:家[jia1],個|个[ge4]/
        1	銀行 银行 [Yin2 hang2] /Yinhang (place name)/
        2900	昨天 昨天 [zuo2 tian1] /yesterday/
        1200	昨 昨 [zuo2] /yesterday/
        3000	天 天 [tian1] /day/sky/heaven/
        8000	我 我 [wo3] /I; me; my/
        9000	去 去 [qu4] /to go/to go to (a place)/to leave/
        500	錢 钱 [qian2] /coin; money/CL:筆|笔[bi3]/surname Qian/
        2	一行 一行 [yi1 xing2] /a group traveling together/
        883634	了 了 [le5] /(completed action marker)/(modal particle indicating change of state, situation now)/
        883634	了 了 [liao3] /to finish/(literary) (usually followed by a negative) fully; clearly/to understand clearly/unofficial variant of 瞭[liao4]/
        """.trimIndent()
    )
    private val breakdown = WordBreakdown(dictionary)

    @Test fun pinyinMarks() {
        assertEquals("yín háng", Pinyin.marks("yin2 hang2"))
        assertEquals("nǚ", Pinyin.marks("nu:3"))
        assertEquals("ma", Pinyin.marks("ma5"))
        assertEquals("zhōu", Pinyin.marks("zhou1"))
        assertEquals("guì", Pinyin.marks("gui4"))
        assertEquals("Běi jīng", Pinyin.marks("Bei3 jing1"))
        assertEquals("r", Pinyin.marks("r5"))
        assertEquals(3, Pinyin.tone("wo3"))
    }

    @Test fun parsesBothScriptsAndFrequency() {
        assertEquals(14, dictionary.size)
        assertEquals(2, dictionary.lookup("银行").size)
        assertEquals(2, dictionary.lookup("銀行").size)
        assertEquals(7684, dictionary.frequency("银行"))
        assertEquals(listOf("bank", "CL:家[jia1],個|个[ge4]"), dictionary.lookup("银行").first().definitions)
    }

    @Test fun plainCedictLinesLoadWithoutFrequency() {
        val plain = ZhDictionary.parse("銀行 银行 [yin2 hang2] /bank/\n")
        assertEquals(0, plain.frequency("银行"))
        assertEquals("bank", plain.lookup("银行").single().definitions.single())
    }

    @Test fun segmentsIntoTheLikeliestWords() {
        val text = "我昨天去银行。"
        val words = breakdown.segment(text).map { (s, e) -> text.substring(s, e) }
        assertEquals(listOf("我", "昨天", "去", "银行"), words)
    }

    @Test fun unknownCharactersBecomeTheirOwnWords() {
        val text = "我嗯去"
        assertEquals(listOf("我", "嗯", "去"), breakdown.segment(text).map { (s, e) -> text.substring(s, e) })
        val card = breakdown.analyse(text).cards[1]
        assertTrue(card.senses.isEmpty())
        assertNull(card.pinyin)
    }

    @Test fun latinWordsAndPunctuationAreSkipped() {
        val text = "我去 iPhone 银行!"
        val words = breakdown.segment(text).map { (s, e) -> text.substring(s, e) }
        assertEquals(listOf("我", "去", "银行"), words)
    }

    @Test fun scoresSumToOneAndCommonMeaningsComeFirst() {
        val senses = breakdown.senses("银行")
        assertEquals(1.0, senses.sumOf { it.score }, 1e-9)
        assertEquals("bank", senses.first().gloss)
        assertEquals("yín háng", senses.first().pinyin)
        // Classifier notes aren't meanings; the place name is unlikely.
        assertTrue(senses.none { it.gloss.startsWith("CL:") })
        assertTrue(senses.last().gloss.contains("place name"))
    }

    @Test fun theTranslationPicksTheMeaningAndTheReading() {
        // On its own, 行 most likely reads xíng ("to walk")…
        val alone = breakdown.analyse("行").cards.single()
        assertEquals("xíng", alone.pinyin)
        // …but next to "a row of" in the translation, the háng meaning wins.
        val inContext = breakdown.analyse("行", "a row of trees").cards.single()
        assertEquals("háng", inContext.pinyin)
        assertTrue(inContext.best!!.matched)
        assertEquals(2, inContext.readings.size)
    }

    @Test fun theUsualReadingBreaksTies() {
        // liǎo has more meanings, so without a hint it would win…
        assertEquals("liǎo", breakdown.analyse("了").cards.single().pinyin)
        // …but ICU's usual reading for 了 is le, the particle.
        val withHint = WordBreakdown(dictionary, usualReading = { if (it == "了") "le" else null })
        assertEquals("le", withHint.analyse("了").cards.single().pinyin)
    }

    @Test fun linksTranslationWordsToCards() {
        val result = breakdown.analyse("我昨天去银行", "Yesterday I went to the bank.")
        val byWord = result.translationWords.associate { it.text to it.card?.let { i -> result.cards[i].text } }
        assertEquals("昨天", byWord["Yesterday"])
        assertEquals("我", byWord["I"])
        assertEquals("去", byWord["went"])
        assertEquals("银行", byWord["bank"])
        assertNull(byWord["the"])
        assertEquals(3, result.cardAt(4)) // 银 → 银行
        assertEquals(1, result.translationWordAt(result.translationWords[1].start))
    }

    @Test fun multiCharacterWordsListTheirCharacters() {
        val card = breakdown.analyse("银行").cards.single()
        assertEquals(listOf("银", "行"), card.characters.map { it.char })
        assertEquals("yín", card.characters[0].pinyin)
        assertEquals("silver", card.characters[0].gloss)
    }

    @Test fun englishToChinese() {
        val candidates = breakdown.englishCandidates("banks")
        assertEquals("银行", candidates.first().word)
        assertEquals(1.0, candidates.sumOf { it.score }, 1e-9)
        assertTrue(breakdown.englishCandidates("the").isEmpty())
        // "money" is in both 钱 and 银; 钱 is the commoner word and says just that.
        assertEquals("钱", breakdown.englishCandidates("money").first().word)
    }

    @Test fun stemming() {
        assertEquals(WordBreakdown.stem("bank"), WordBreakdown.stem("banks"))
        assertEquals(WordBreakdown.stem("go"), WordBreakdown.stem("went"))
        assertEquals(WordBreakdown.stem("make"), WordBreakdown.stem("made"))
        assertEquals(WordBreakdown.stem("run"), WordBreakdown.stem("running"))
        assertEquals(WordBreakdown.stem("city"), WordBreakdown.stem("cities"))
    }

    @Test fun requestForAConversationTurn() {
        val spokenChinese = Turn(1, 0, null, "zh", "我去银行", "en", translation = "I'm going to the bank", start = 0, end = 0)
        val tapChinese = BreakdownRequest.forTurn(spokenChinese, tappedTranslation = false, offset = 2)!!
        assertEquals("我去银行", tapChinese.chinese)
        assertEquals("I'm going to the bank", tapChinese.translation)
        assertTrue(tapChinese.translationIsEnglish)
        assertEquals(BreakdownRequest.Focus.Chinese(2), tapChinese.focus)
        assertEquals(BreakdownRequest.Focus.Translation(5), BreakdownRequest.forTurn(spokenChinese, true, 5)!!.focus)

        // English spoken, Chinese translation: the Chinese side is the translation.
        val spokenEnglish = Turn(2, 0, null, "en", "the bank", "zh", translation = "银行", start = 0, end = 0)
        val r = BreakdownRequest.forTurn(spokenEnglish, tappedTranslation = true, offset = 1)!!
        assertEquals("银行", r.chinese)
        assertEquals("the bank", r.translation)
        assertEquals(BreakdownRequest.Focus.Chinese(1), r.focus)

        // No Chinese on either side, or not translated yet.
        assertNull(BreakdownRequest.forTurn(Turn(3, 0, null, "en", "hi", "es", translation = "hola", start = 0, end = 0), false, 0))
        assertNull(BreakdownRequest.forTurn(Turn(4, 0, null, "en", "hi", "zh", start = 0, end = 0), false, 0))
    }
}
