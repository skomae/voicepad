package com.voicepad.app.conversation

import kotlin.math.sqrt

/**
 * Tells voices apart as a conversation goes along: each utterance's voice
 * fingerprint (a speaker embedding) joins the most similar speaker heard so
 * far, or starts a new speaker when it resembles none of them.
 *
 * Embeddings of short utterances are noisy, so a speaker's fingerprint is
 * the running average of their utterances, and an utterance in the language
 * a speaker has been speaking gets a small nudge towards them (in a
 * translated conversation, people rarely switch language). Pure Kotlin.
 */
class SpeakerClustering(
    /** Cosine similarity needed to count as a voice already heard. */
    private val threshold: Float = DEFAULT_THRESHOLD,
    /** No more speakers than this; beyond it, utterances join the closest one. */
    private val maxSpeakers: Int = 6,
    /** Added to the similarity when the language matches what that speaker has been speaking. */
    private val languageBonus: Float = 0.06f,
) {
    private class Cluster(val id: Int, var centroid: FloatArray) {
        var count = 0
        val languages = mutableMapOf<String, Int>()
        val mainLanguage: String? get() = languages.maxByOrNull { it.value }?.key
    }

    private val clusters = mutableListOf<Cluster>()

    val speakerCount: Int get() = clusters.size

    /** Speaker index (0, 1, 2…) for an utterance with this [embedding]. */
    fun assign(embedding: FloatArray, language: String? = null): Int {
        val e = normalized(embedding)
        val best = clusters.maxByOrNull { score(it, e, language) }
        val chosen = if (best != null && (score(best, e, language) >= threshold || clusters.size >= maxSpeakers)) {
            best
        } else {
            Cluster(clusters.size, e).also { clusters += it }
        }
        // Later utterances count less once a voice is well known, so it can
        // still drift a little (a speaker getting louder, moving away…).
        if (chosen.count > 0) {
            val weight = 1f / (minOf(chosen.count, 20) + 1)
            val c = chosen.centroid
            for (i in c.indices) c[i] = c[i] * (1 - weight) + e[i] * weight
            chosen.centroid = normalized(c)
        }
        chosen.count++
        if (language != null) chosen.languages.merge(language, 1, Int::plus)
        return chosen.id
    }

    fun reset() = clusters.clear()

    private fun score(c: Cluster, e: FloatArray, language: String?): Float {
        val similarity = dot(c.centroid, e)
        val main = c.mainLanguage
        return if (language != null && main != null && main == language) similarity + languageBonus else similarity
    }

    companion object {
        /**
         * Measured with the CAM++ model on 1–8 s utterances (sherpa-onnx's
         * speaker test recordings, Chinese and English): the same speaker
         * within one recording scored 0.49–0.82, different speakers at most
         * 0.41 (99th percentile; median 0.07). Across different recordings
         * of one person it can drop much lower, so a speaker heard in very
         * different conditions may get a second number rather than be
         * mistaken for someone else.
         */
        const val DEFAULT_THRESHOLD = 0.45f

        fun normalized(v: FloatArray): FloatArray {
            var norm = 0.0
            for (x in v) norm += x * x
            val n = sqrt(norm).toFloat()
            if (n == 0f) return v.copyOf()
            return FloatArray(v.size) { v[it] / n }
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
            return s
        }
    }
}
