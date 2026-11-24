import Foundation

extension EngineChoice {
    func makeEngine() -> TranscriptionEngine {
        switch self {
        case .parakeetV3: return ParakeetEngine(version: .v3)
        case .parakeetV2: return ParakeetEngine(version: .v2)
        case .autoAsian: return AutoEngine(parakeet: ParakeetEngine(version: .v3), senseVoice: SenseVoiceEngine())
        case .senseVoice: return SenseVoiceEngine()
        case .whisperTinyEn, .whisperBaseEn, .whisperSmallEn:
            return WhisperEngine(model: rawValue, multilingual: false)
        case .whisperSmall, .whisperLargeV3Turbo:
            return WhisperEngine(model: rawValue, multilingual: true)
        }
    }
}

/// Text plus the spoken language the model reported (e.g. "zh"), if it
/// reports one, and which model produced it.
struct Transcript {
    var text: String
    var language: String?
    var engine: String?

    static let empty = Transcript(text: "", language: nil)
}

/// A speech-to-text model that turns 16 kHz mono Float32 samples into text.
protocol TranscriptionEngine: AnyObject {
    /// How long audio should be grouped before it reaches `transcribe`.
    var grouping: ChunkPlanner.Grouping { get }
    /// Downloads (first run only) and loads the model. `progress` reports 0...1.
    func load(progress: @escaping @Sendable (Double) -> Void) async throws
    func transcribe(_ samples: [Float]) async throws -> Transcript
}
