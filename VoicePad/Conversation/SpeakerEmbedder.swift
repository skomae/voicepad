import FluidAudio
import Foundation

/// Turns a stretch of speech into a voice fingerprint (speaker embedding)
/// with FluidAudio's diarization models (pyannote segmentation + WeSpeaker,
/// Core ML, downloaded once). Android uses 3D-Speaker's CAM++ instead; the
/// clustering on top is the same.
final class SpeakerEmbedder: @unchecked Sendable {
    /// Shorter speech gives fingerprints too noisy to trust.
    static let minSamples = Int(AudioRecorder.sampleRate)

    private let manager: DiarizerManager
    /// The manager isn't thread-safe; callers may come from any task.
    private let lock = NSLock()

    private init(manager: DiarizerManager) { self.manager = manager }

    /// Downloads the models if needed, then loads them. `progress` reports 0...1.
    static func load(progress: @escaping @Sendable (Double) -> Void) async throws -> SpeakerEmbedder {
        let models = try await DiarizerModels.downloadIfNeeded { p in progress(p.fractionCompleted) }
        let manager = DiarizerManager()
        manager.initialize(models: models)
        return SpeakerEmbedder(manager: manager)
    }

    /// The fingerprint of `samples` (16 kHz mono), or nil if too short to tell.
    func embed(_ samples: [Float]) -> [Float]? {
        guard samples.count >= Self.minSamples else { return nil }
        lock.lock(); defer { lock.unlock() }
        return try? manager.extractSpeakerEmbedding(from: samples)
    }
}
