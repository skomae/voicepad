import Foundation
import WhisperKit

/// Whisper via WhisperKit (CoreML, runs on the Neural Engine).
final class WhisperEngine: TranscriptionEngine {
    private let model: String
    private var whisperKit: WhisperKit?

    init(model: String) {
        self.model = model
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

    func transcribe(_ samples: [Float]) async throws -> String {
        guard let whisperKit else { return "" }
        // Whisper hallucinates on near-silence; skip very short clips.
        guard samples.count > Int(AudioRecorder.sampleRate * 0.5) else { return "" }
        let options = DecodingOptions(
            task: .transcribe,
            temperatureFallbackCount: 2,
            skipSpecialTokens: true,
            withoutTimestamps: true,
            chunkingStrategy: .vad
        )
        let results = try await whisperKit.transcribe(audioArray: samples, decodeOptions: options)
        return results
            .map(\.text)
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
