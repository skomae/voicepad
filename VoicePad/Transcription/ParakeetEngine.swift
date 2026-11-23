import Foundation
import FluidAudio

/// NVIDIA Parakeet TDT via FluidAudio (CoreML, runs on the Neural Engine).
final class ParakeetEngine: TranscriptionEngine {
    private let version: AsrModelVersion
    private var manager: AsrManager?

    init(version: AsrModelVersion) {
        self.version = version
    }

    func load(progress: @escaping @Sendable (Double) -> Void) async throws {
        let models = try await AsrModels.downloadAndLoad(version: version) { p in
            progress(p.fractionCompleted)
        }
        let manager = AsrManager(config: .default)
        try await manager.loadModels(models)
        self.manager = manager
        progress(1)
    }

    func transcribe(_ samples: [Float]) async throws -> String {
        guard let manager else { return "" }
        // Parakeet needs at least ~1 s of audio.
        guard samples.count >= Int(AudioRecorder.sampleRate) else { return "" }
        var state = TdtDecoderState.make(decoderLayers: await manager.decoderLayerCount)
        let result = try await manager.transcribe(samples, decoderState: &state)
        return result.text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
