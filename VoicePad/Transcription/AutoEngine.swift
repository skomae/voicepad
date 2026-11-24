import Foundation

/// "Auto: Asian + English": for each utterance, SenseVoice decides whether it
/// is Mandarin, Cantonese, Japanese or Korean (keeps its own text) or English
/// (transcribed again by Parakeet, which is stronger on English).
///
/// SenseVoice's language tag is reliable for that choice — it doesn't mistake
/// English and gets Chinese, Japanese and Korean right from about two seconds
/// of speech — though not for European languages (it labels Spanish
/// "Japanese"); those belong in "Auto: Western" (Parakeet alone).
///
/// Works per utterance, so a recording that switches language at a pause gets
/// the right model for each part.
final class AutoEngine: TranscriptionEngine {
    static let eastAsian: Set<String> = ["zh", "yue", "ja", "ko"]

    private let parakeet: TranscriptionEngine
    private let senseVoice: TranscriptionEngine
    let grouping = ChunkPlanner.Grouping.utterances

    init(parakeet: TranscriptionEngine, senseVoice: TranscriptionEngine) {
        self.parakeet = parakeet
        self.senseVoice = senseVoice
    }

    /// Downloads and loads both models; progress covers the two together.
    func load(progress: @escaping @Sendable (Double) -> Void) async throws {
        try await senseVoice.load { p in progress(p / 2) }
        try await parakeet.load { p in progress(0.5 + p / 2) }
        progress(1)
    }

    func transcribe(_ samples: [Float]) async throws -> Transcript {
        let first = try await senseVoice.transcribe(samples)
        if let language = first.language, Self.eastAsian.contains(language) { return first }
        var english = try await parakeet.transcribe(samples)
        english.language = "en"
        return english
    }
}
