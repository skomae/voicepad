import FluidAudio
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

/// How far a model has got with downloading and getting ready, for progress bars.
struct LoadProgress: Equatable, Sendable {
    /// 0...1 overall.
    var fraction: Double
    /// What's happening, e.g. "Downloading SenseVoice — file 3 of 7".
    var stage: String
    /// False while there's nothing to measure (e.g. compiling for this
    /// iPhone): show an indeterminate bar.
    var determinate: Bool

    static let starting = LoadProgress(fraction: 0, stage: "Starting…", determinate: false)

    /// From FluidAudio's progress reports.
    static func from(_ p: DownloadProgress, model: String) -> LoadProgress {
        switch p.phase {
        case .listing:
            return LoadProgress(fraction: p.fractionCompleted, stage: "Checking \(model)…", determinate: false)
        case let .downloading(completed, total):
            let files = total > 0 ? " — file \(min(completed + 1, total)) of \(total)" : ""
            return LoadProgress(fraction: p.fractionCompleted, stage: "Downloading \(model)\(files)", determinate: true)
        case .compiling:
            return LoadProgress(fraction: p.fractionCompleted,
                                stage: "Preparing \(model) for this iPhone (the first time can take a few minutes)…", determinate: false)
        }
    }

    /// This progress as part `index` (0-based) of `count` steps.
    func step(_ index: Int, of count: Int) -> LoadProgress {
        LoadProgress(fraction: (Double(index) + fraction) / Double(count),
                     stage: "Step \(index + 1) of \(count): \(stage)", determinate: determinate)
    }
}

/// A speech-to-text model that turns 16 kHz mono Float32 samples into text.
protocol TranscriptionEngine: AnyObject {
    /// How long audio should be grouped before it reaches `transcribe`.
    var grouping: ChunkPlanner.Grouping { get }
    /// Downloads (first run only) and loads the model. `progress` reports 0...1.
    func load(progress: @escaping @Sendable (LoadProgress) -> Void) async throws
    func transcribe(_ samples: [Float]) async throws -> Transcript
}
