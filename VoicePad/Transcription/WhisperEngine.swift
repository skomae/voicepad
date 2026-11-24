import Foundation
import WhisperKit

/// Whisper via WhisperKit (CoreML, runs on the Neural Engine).
final class WhisperEngine: TranscriptionEngine {
    private let model: String
    private let multilingual: Bool
    private var whisperKit: WhisperKit?
    let grouping = ChunkPlanner.Grouping.longestPauses
    private let name: String

    init(model: String, multilingual: Bool) {
        self.model = model
        self.multilingual = multilingual
        name = EngineChoice(rawValue: model)?.shortName ?? "Whisper"
    }

    func load(progress: @escaping @Sendable (Double) -> Void) async throws {
        let folder = try await WhisperKit.download(variant: model) { p in
            progress(p.fractionCompleted)
        }
        let config = WhisperKitConfig(
            model: model,
            modelFolder: folder.path,
            verbose: false,
            prewarm: true,
            load: true,
            download: false
        )
        whisperKit = try await WhisperKit(config)
        progress(1)
    }

    func transcribe(_ samples: [Float]) async throws -> Transcript {
        guard let whisperKit else { return .empty }
        // Whisper hallucinates on near-silence; skip very short clips.
        guard samples.count > Int(AudioRecorder.sampleRate * 0.5) else { return .empty }
        let options = DecodingOptions(
            task: .transcribe,
            temperatureFallbackCount: 2,
            // Without this WhisperKit assumes English, so multilingual models
            // would never hear (or report) another language.
            detectLanguage: multilingual,
            skipSpecialTokens: true,
            withoutTimestamps: true,
            chunkingStrategy: .vad
        )
        let results = try await whisperKit.transcribe(audioArray: samples, decodeOptions: options)
        let text = results
            .map(\.text)
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return Transcript(text: text, language: multilingual ? LanguageTags.fromModel(results.first?.language) : "en", engine: name)
    }
}
