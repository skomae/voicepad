import AVFoundation

/// Captures microphone audio, converts it to 16 kHz mono Float32 (what both
/// Whisper and Parakeet expect), keeps it in memory for live transcription
/// and writes it to a WAV file.
final class AudioRecorder {
    enum RecorderError: LocalizedError {
        case noInput
        case converterUnavailable

        var errorDescription: String? {
            switch self {
            case .noInput: return "No microphone input is available."
            case .converterUnavailable: return "Could not convert microphone audio to 16 kHz."
            }
        }
    }

    static let sampleRate: Double = 16_000

    private let engine = AVAudioEngine()
    private let targetFormat = AVAudioFormat(
        commonFormat: .pcmFormatFloat32,
        sampleRate: AudioRecorder.sampleRate,
        channels: 1,
        interleaved: false
    )!

    private var converter: AVAudioConverter?
    private var file: AVAudioFile?

    private let lock = NSLock()
    private var samples: [Float] = []
    private var currentLevel: Float = 0

    private(set) var isRunning = false

    /// Total samples captured so far.
    var sampleCount: Int {
        lock.lock(); defer { lock.unlock() }
        return samples.count
    }

    /// Normalised 0...1 input level of the most recent buffer.
    var level: Float {
        lock.lock(); defer { lock.unlock() }
        return currentLevel
    }

    /// Copy of the captured samples in `range` (clamped to what exists).
    func samples(in range: Range<Int>) -> [Float] {
        lock.lock(); defer { lock.unlock() }
        let lower = max(0, min(range.lowerBound, samples.count))
        let upper = max(lower, min(range.upperBound, samples.count))
        return Array(samples[lower..<upper])
    }

    func start(writingTo url: URL) throws {
        guard !isRunning else { return }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setActive(true)

        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else { throw RecorderError.noInput }
        guard let converter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
            throw RecorderError.converterUnavailable
        }
        self.converter = converter

        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: AudioRecorder.sampleRate,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
        ]
        file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)

        lock.lock()
        samples.removeAll(keepingCapacity: true)
        currentLevel = 0
        lock.unlock()

        input.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, _ in
            self?.process(buffer)
        }
        engine.prepare()
        try engine.start()
        isRunning = true
    }

    func stop() {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        isRunning = false
        file = nil // closes and finalises the WAV header
        converter = nil
        lock.lock(); currentLevel = 0; lock.unlock()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func process(_ buffer: AVAudioPCMBuffer) {
        guard let converter else { return }
        let ratio = targetFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        guard let output = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else { return }

        var consumed = false
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, inputStatus in
            if consumed {
                inputStatus.pointee = .noDataNow
                return nil
            }
            consumed = true
            inputStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, output.frameLength > 0, let channel = output.floatChannelData?[0] else { return }

        try? file?.write(from: output)

        let chunk = Array(UnsafeBufferPointer(start: channel, count: Int(output.frameLength)))
        var sumSquares: Float = 0
        for sample in chunk { sumSquares += sample * sample }
        let rms = sqrt(sumSquares / Float(max(chunk.count, 1)))
        // Map roughly -50 dB...0 dB onto 0...1.
        let db = 20 * log10(max(rms, 1e-6))
        let normalised = max(0, min(1, (db + 50) / 50))

        lock.lock()
        samples.append(contentsOf: chunk)
        currentLevel = normalised
        lock.unlock()
    }
}
