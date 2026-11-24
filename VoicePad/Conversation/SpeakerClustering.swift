import Foundation

/// Tells voices apart as a conversation goes along: each utterance's voice
/// fingerprint (a speaker embedding) joins the most similar speaker heard so
/// far, or starts a new speaker when it resembles none of them.
///
/// Embeddings of short utterances are noisy, so a speaker's fingerprint is
/// the running average of their utterances, and an utterance in the language
/// a speaker has been speaking gets a small nudge towards them (in a
/// translated conversation, people rarely switch language). Pure Swift.
final class SpeakerClustering {
    private final class Cluster {
        let id: Int
        var centroid: [Float]
        var count = 0
        var languages: [String: Int] = [:]
        var mainLanguage: String? { languages.max { $0.value < $1.value }?.key }
        init(id: Int, centroid: [Float]) { self.id = id; self.centroid = centroid }
    }

    /// Cosine similarity needed to count as a voice already heard.
    private let threshold: Float
    /// No more speakers than this; beyond it, utterances join the closest one.
    private let maxSpeakers: Int
    /// Added to the similarity when the language matches what that speaker has been speaking.
    private let languageBonus: Float
    private var clusters: [Cluster] = []

    /// FluidAudio's WeSpeaker embeddings: its own diarizer treats a cosine
    /// distance under ~0.6 as the same speaker (similarity above 0.4). Not yet
    /// tuned on recordings for this app; a speaker heard in very different
    /// conditions may get a second number rather than be mistaken for someone else.
    static let defaultThreshold: Float = 0.45

    init(threshold: Float = SpeakerClustering.defaultThreshold, maxSpeakers: Int = 6, languageBonus: Float = 0.06) {
        self.threshold = threshold
        self.maxSpeakers = maxSpeakers
        self.languageBonus = languageBonus
    }

    var speakerCount: Int { clusters.count }

    /// Speaker index (0, 1, 2…) for an utterance with this `embedding`.
    func assign(_ embedding: [Float], language: String? = nil) -> Int {
        let e = Self.normalized(embedding)
        let best = clusters.max { score($0, e, language) < score($1, e, language) }
        let chosen: Cluster
        if let best, score(best, e, language) >= threshold || clusters.count >= maxSpeakers {
            chosen = best
        } else {
            chosen = Cluster(id: clusters.count, centroid: e)
            clusters.append(chosen)
        }
        // Later utterances count less once a voice is well known, so it can
        // still drift a little (a speaker getting louder, moving away…).
        if chosen.count > 0 {
            let weight = 1 / Float(min(chosen.count, 20) + 1)
            chosen.centroid = Self.normalized(zip(chosen.centroid, e).map { $0 * (1 - weight) + $1 * weight })
        }
        chosen.count += 1
        if let language { chosen.languages[language, default: 0] += 1 }
        return chosen.id
    }

    func reset() { clusters.removeAll() }

    private func score(_ c: Cluster, _ e: [Float], _ language: String?) -> Float {
        let similarity = Self.dot(c.centroid, e)
        if let language, let main = c.mainLanguage, main == language { return similarity + languageBonus }
        return similarity
    }

    static func normalized(_ v: [Float]) -> [Float] {
        let n = v.reduce(0) { $0 + $1 * $1 }.squareRoot()
        return n == 0 ? v : v.map { $0 / n }
    }

    static func dot(_ a: [Float], _ b: [Float]) -> Float { zip(a, b).reduce(0) { $0 + $1.0 * $1.1 } }
}
