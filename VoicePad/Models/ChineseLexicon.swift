import Foundation
import Observation

/// The bundled Chinese dictionary, parsed once in the background, and the word
/// breakdown built on it. A tap must never wait for a 125k-entry parse.
@MainActor
@Observable
final class ChineseLexicon {
    static let shared = ChineseLexicon()

    /// True while the dictionary is being read.
    private(set) var loading = false
    /// True when this build has no dictionary resource (the sheet says so).
    private(set) var unavailable = false

    @ObservationIgnored private var breakdown: WordBreakdown?
    @ObservationIgnored private var task: Task<Void, Never>?

    /// Reads and parses the dictionary, once.
    func prepare() {
        guard breakdown == nil, task == nil else { return }
        loading = true
        task = Task { [weak self] in
            let parsed = await Task.detached(priority: .utility) {
                BundledChineseDictionary.load()
            }.value
            guard let self else { return }
            self.loading = false
            self.task = nil
            if let parsed {
                // NLTokenizer segments words; ICU readings fill in rare hanzi.
                self.breakdown = WordBreakdown(dictionary: parsed,
                                               usualReading: ChineseSegmenter.usualReading,
                                               segmenter: ChineseSegmenter())
                self.unavailable = false
            } else {
                self.unavailable = true
            }
        }
    }

    /// The breakdown for a tap, once the dictionary is ready.
    func analyse(_ request: BreakdownRequest) async -> WordBreakdown.Result? {
        prepare()
        await task?.value
        guard let breakdown else { return nil }
        let english = request.translationIsEnglish ? request.translation : nil
        return breakdown.analyse(request.chinese, translation: english)
    }

    /// Chinese words meaning an English word, for the sheet's reverse lookup.
    func candidates(for english: String) -> [WordBreakdown.Candidate] {
        breakdown?.englishCandidates(english) ?? []
    }

    /// The breakdown engine, if it's already loaded (for synchronous callers).
    var engine: WordBreakdown? { breakdown }
}
