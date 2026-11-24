import Foundation
import FluidAudio

/// SenseVoice Small via FluidAudio (CoreML). Recognises Mandarin, Cantonese,
/// English, Japanese and Korean, and reports which one it heard.
final class SenseVoiceEngine: TranscriptionEngine {
    private var manager: SenseVoiceManager?
    let grouping = ChunkPlanner.Grouping.utterances

    func load(progress: @escaping @Sendable (Double) -> Void) async throws {
        manager = try await SenseVoiceManager.load(precision: .fp16) { p in
            progress(p.fractionCompleted)
        }
        progress(1)
    }

    func transcribe(_ samples: [Float]) async throws -> Transcript {
        guard let manager else { return .empty }
        // Very short clips just produce noise or hallucinations.
        guard samples.count >= Int(AudioRecorder.sampleRate / 2) else { return .empty }
        let result = try await manager.transcribeDetailed(audio: samples)
        return Transcript(
            text: result.text.trimmingCharacters(in: .whitespacesAndNewlines),
            language: LanguageTags.fromModel(result.language),
            engine: "SenseVoice"
        )
    }
}
