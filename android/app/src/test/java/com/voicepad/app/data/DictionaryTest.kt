package com.voicepad.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionaryTest {
    @get:Rule val tmp = TemporaryFolder()

    // region New words from an edit

    @Test fun `offers only words the edit introduced, in order, once`() {
        assertEquals(
            listOf("Kubernetes", "Priya"),
            DictionaryWords.newWords(
                "I set up cooper net ease with pria today",
                "I set up Kubernetes with Priya today, Kubernetes with Priya.",
            ),
        )
    }

    @Test fun `a case fix counts as a new word`() {
        assertEquals(listOf("iPhone"), DictionaryWords.newWords("my iphone broke", "my iPhone broke"))
    }

    @Test fun `numbers, punctuation and possessives`() {
        assertEquals(listOf("Anthropic"), DictionaryWords.newWords("call at 5", "call Anthropic's at 10!"))
    }

    @Test fun `words already in a dictionary are not offered again`() {
        assertEquals(listOf("Grafana"), DictionaryWords.newWords("and", "Grafana and Kubernetes", known = listOf("Kubernetes")))
    }

    @Test fun `nothing new, nothing offered`() {
        assertEquals(listOf("hello"), DictionaryWords.newWords("Hello there, world", "hello world there"))
        assertTrue(DictionaryWords.newWords("hello there world", "world. hello, there").isEmpty())
    }

    // endregion

    // region Correcting transcripts

    private fun fix(text: String, vararg words: String) = VocabularyCorrector(words.toList()).correct(text)

    @Test fun `fixes case`() {
        assertEquals("I bought an iPhone today.", fix("I bought an iphone today.", "iPhone"))
    }

    @Test fun `joins words the model split`() {
        assertEquals("Ask ChatGPT about it", fix("Ask chat GPT about it", "ChatGPT"))
        assertEquals("Open VoicePad now", fix("Open voice-pad now", "VoicePad"))
    }

    @Test fun `matches phrases and prefers the longer one`() {
        assertEquals("I use Claude Code daily", fix("I use claude code daily", "Claude", "Claude Code"))
        assertEquals("Claude said hi", fix("claude said hi", "Claude", "Claude Code"))
    }

    @Test fun `fixes a near miss on a long word`() {
        assertEquals("deploy to Kubernetes now", fix("deploy to kubernetis now", "Kubernetes"))
        assertEquals("Dr Okonkwo called", fix("Dr Okonqwo called", "Okonkwo"))
    }

    @Test fun `leaves ordinary short words alone`() {
        assertEquals("he parked the car", fix("he parked the car", "Parker"))
        assertEquals("Sarah and Sara", fix("Sarah and Sara", "Sarah"))
    }

    @Test fun `keeps the possessive and punctuation`() {
        assertEquals("That's Priya's idea, right?", fix("That's priya's idea, right?", "Priya"))
    }

    @Test fun `a lower-case entry fixes spelling but not case`() {
        assertEquals("Kubectl apply", fix("Kubectl apply", "kubectl"))
        assertEquals("run kubectl apply", fix("run kube ctl apply", "kubectl"))
    }

    @Test fun `does not join across punctuation`() {
        assertEquals("chat. GPT", fix("chat. GPT", "ChatGPT"))
    }

    @Test fun `empty dictionary changes nothing`() {
        assertEquals("anything at all", fix("anything at all"))
    }

    // endregion

    // region Store

    @Test fun `store keeps dictionaries, moves words and only uses enabled ones`() {
        val dir = tmp.newFolder()
        val store = DictionaryStore(dir)
        val mine = store.dictionaries.value.single()
        val work = store.create("Work")
        store.addWords(mine.id, listOf("Priya", "Kubernetes", "Priya"))
        store.moveWords(listOf("Kubernetes"), from = mine.id, to = work.id)
        store.setEnabled(work.id, false)

        val reloaded = DictionaryStore(dir)
        assertEquals(listOf("Priya"), reloaded.dictionaries.value.first { it.id == mine.id }.words)
        assertEquals(listOf("Kubernetes"), reloaded.dictionaries.value.first { it.id == work.id }.words)
        assertEquals(listOf("Priya"), reloaded.activeWords())
        assertEquals(setOf("Priya", "Kubernetes"), reloaded.allWords())
        assertEquals(mine.id, reloaded.lastUsedId)
    }

    @Test fun `there is always one dictionary`() {
        val store = DictionaryStore(tmp.newFolder())
        store.delete(store.dictionaries.value.single().id)
        assertEquals(1, store.dictionaries.value.size)
    }

    @Test fun `editing a word keeps its place`() {
        val store = DictionaryStore(tmp.newFolder())
        val id = store.dictionaries.value.single().id
        store.addWords(id, listOf("alpha", "Betta", "gamma"))
        store.replaceWord(id, "Betta", " Beta ")
        assertEquals(listOf("alpha", "Beta", "gamma"), store.dictionaries.value.single().words)
    }

    // endregion
}
