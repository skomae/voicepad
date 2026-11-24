import Foundation
import FluidAudio

/// The Silero voice-activity model (via FluidAudio, Core ML). Loaded once and
/// shared: each audio stream keeps its own `SpeechDetector` state.
///
/// Android bundles the model; here FluidAudio downloads it (about 2 MB) the
/// first time, alongside the speech model. Until it is available, audio is
/// handled in whole stretches, as before.
enum VoiceActivity {
    static let config = VadConfig(defaultThreshold: 0.5)
    static let segmentation = VadSegmentationConfig(
        minSpeechDuration: 0.25,
        // A short gap ends an utterance: short enough to keep a switch between
        // languages out of one model call, long enough not to cut between words.
        minSilenceDuration: 0.25,
        maxSpeechDuration: 20,
        // ChunkPlanner adds the context padding itself.
        speechPadding: 0
    )

    static func load() async throws -> VadManager {
        try await VadManager(config: config)
    }
}

/// Finds speech in a stream of 16 kHz audio. Feed audio in order with `feed`;
/// it returns each utterance as soon as the pause after it is long enough,
/// with positions counted from the first sample fed.
///
/// Not thread-safe: one instance per stream, used from one task at a time.
final class SpeechDetector {
    typealias Span = ChunkPlanner.Span

    private let vad: VadManager
    private var state: VadStreamState
    /// Audio not yet a whole model window (4096 samples, 256 ms).
    private var leftover: [Float] = []
    /// Where the utterance in progress began, if one is open.
    private var openStart: Int?
    /// Utterances shorter than this are noise.
    private let minSpeech = Int(0.25 * AudioRecorder.sampleRate)

    init(vad: VadManager) {
        self.vad = vad
        state = VadStreamState.initial()
    }

    /// True while the detector is inside speech that hasn't ended yet.
    var inSpeech: Bool { openStart != nil }

    /// Accepts any amount of audio; whole windows are passed to the model.
    func feed(_ samples: [Float]) async throws -> [Span] {
        leftover += samples
        let window = VadManager.chunkSize
        var out: [Span] = []
        var at = 0
        while leftover.count - at >= window {
            try await step(Array(leftover[at..<(at + window)]), into: &out)
            at += window
        }
        leftover.removeFirst(at)
        return out
    }

    /// Ends the stream, returning any utterance still open.
    func flush() async throws -> [Span] {
        var out: [Span] = []
        let end = state.processedSamples + leftover.count
        if !leftover.isEmpty {
            try await step(leftover, into: &out)
            leftover = []
        }
        if let start = openStart {
            openStart = nil
            if end - start >= minSpeech { out.append(Span(start: start, end: end)) }
        }
        return out
    }

    private func step(_ chunk: [Float], into out: inout [Span]) async throws {
        let result = try await vad.processStreamingChunk(chunk, state: state, config: VoiceActivity.segmentation)
        state = result.state
        guard let event = result.event else { return }
        if event.isStart {
            openStart = event.sampleIndex
        } else if event.isEnd, let start = openStart {
            openStart = nil
            if event.sampleIndex - start >= minSpeech { out.append(Span(start: start, end: event.sampleIndex)) }
        }
    }
}
