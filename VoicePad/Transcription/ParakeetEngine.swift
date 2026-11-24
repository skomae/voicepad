import Foundation
import FluidAudio

/// NVIDIA Parakeet TDT via FluidAudio (CoreML, runs on the Neural Engine).
final class ParakeetEngine: TranscriptionEngine {
    private let version: AsrModelVersion
    private var manager: AsrManager?
    let grouping = ChunkPlanner.Grouping.utterances
    private var name: String { version == .v2 ? "Parakeet v2" : "Parakeet v3" }

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

    func transcribe(_ samples: [Float]) async throws -> Transcript {
        guard let manager else { return .empty }
        // Very short clips just produce noise.
        guard samples.count >= Int(AudioRecorder.sampleRate / 2) else { return .empty }
        // Parakeet needs at least ~1 s of audio; pad short words ("yes",
        // "okay") with silence rather than dropping them.
        var audio = samples
        let minimum = Int(AudioRecorder.sampleRate) + Int(AudioRecorder.sampleRate / 10)
        if audio.count < minimum { audio += [Float](repeating: 0, count: minimum - audio.count) }
        var state = TdtDecoderState.make(decoderLayers: await manager.decoderLayerCount)
        let result = try await manager.transcribe(audio, decoderState: &state)
        // Parakeet doesn't report a language; the text is identified later.
        return Transcript(text: result.text.trimmingCharacters(in: .whitespacesAndNewlines), language: nil, engine: name)
    }
}
