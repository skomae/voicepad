import AVFoundation

/// Captures microphone audio, converts it to 16 kHz mono Float32 (what both
/// Whisper and Parakeet expect), keeps it in memory for live transcription
/// and writes it to a WAV file whose header is kept valid every ~5 s, so a
/// recording survives the app being killed.
final class AudioRecorder: @unchecked Sendable {
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

    /// One engine for the recorder's lifetime. (Making a fresh engine for each
    /// recording made iPhones deliver nothing but digital silence.)
    private let engine = AVAudioEngine()
    private let targetFormat = AVAudioFormat(
        commonFormat: .pcmFormatFloat32,
        sampleRate: AudioRecorder.sampleRate,
        channels: 1,
        interleaved: false
    )!

    private var converter: AVAudioConverter?
    private var writer: WavWriter?

    private let lock = NSLock()
    private var samples: [Float] = []
    /// Position (in the whole recording) of `samples[0]`. Audio already
    /// transcribed is dropped from memory with `discardBefore`; it's on disk.
    private var offset = 0
    private var currentLevel: Float = 0
    private var currentWriteError: String?
    private var signalSeen = false
    private var rawPeak: Float = 0
    private var rawBuffers = 0

    private(set) var isRunning = false

    /// Samples recorded so far (including ones discarded from memory). Positions
    /// run on across `switchFile`, so they count from when the mic started.
    var sampleCount: Int {
        lock.lock(); defer { lock.unlock() }
        return offset + samples.count
    }

    /// Normalised 0...1 input level of the most recent buffer.
    var level: Float {
        lock.lock(); defer { lock.unlock() }
        return currentLevel
    }

    /// Set if the audio file could not be written (e.g. storage full).
    var writeError: String? {
        lock.lock(); defer { lock.unlock() }
        return currentWriteError
    }

    /// Copy of the captured samples in `range` (recording positions), clamped
    /// to what is still in memory.
    func samples(in range: Range<Int>) -> [Float] {
        lock.lock(); defer { lock.unlock() }
        let lower = max(0, min(range.lowerBound - offset, samples.count))
        let upper = max(lower, min(range.upperBound - offset, samples.count))
        return Array(samples[lower..<upper])
    }

    /// Frees memory for audio before `position` (it's already transcribed and
    /// saved in the WAV), so hour-long recordings don't fill RAM.
    func discardBefore(_ position: Int) {
        lock.lock(); defer { lock.unlock() }
        let drop = max(0, min(position - offset, samples.count))
        guard drop >= Int(Self.sampleRate) * 30 else { return } // not worth copying yet
        samples.removeFirst(drop)
        offset += drop
    }

    /// Splits the recording without stopping the microphone: finishes the
    /// current WAV and continues into `url`. Returns the recording position of
    /// the split; audio before it is in the old file, audio after it in the new.
    /// Runs under the same lock as the audio callback, so it lands exactly
    /// between two buffers.
    /// `writeError` is the finished file's problem, if it had one.
    func switchFile(to url: URL) throws -> (position: Int, writeError: String?) {
        let next = try WavWriter(url: url)
        lock.lock(); defer { lock.unlock() }
        var finishedError = currentWriteError
        do {
            try writer?.close()
        } catch {
            finishedError = finishedError ?? error.localizedDescription
        }
        writer = next
        currentWriteError = nil
        return (offset + samples.count, finishedError)
    }

    private var roomCapture = false

    /// Close speech uses iOS's voice processing (`.default`); room capture
    /// (background voices) uses `.measurement`, which turns off the gain and
    /// noise processing tuned for one nearby talker, so a distant voice isn't
    /// treated as noise to remove.
    private static func mode(roomCapture: Bool) -> AVAudioSession.Mode { roomCapture ? .measurement : .default }

    func start(writingTo url: URL, roomCapture: Bool = false) throws {
        guard !isRunning else { return }
        self.roomCapture = roomCapture

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: Self.mode(roomCapture: roomCapture),
                                options: [.defaultToSpeaker, .allowBluetoothHFP, .allowBluetoothA2DP])
        try session.setActive(true)

        let writer = try WavWriter(url: url)

        lock.lock()
        samples.removeAll(keepingCapacity: true)
        offset = 0
        signalSeen = false
        currentLevel = 0
        currentWriteError = nil
        self.writer = writer
        lock.unlock()

        do {
            try startCapture()
        } catch {
            try? writer.close()
            throw error
        }
        isRunning = true
    }

    /// Opens the microphone (its format can change with the session mode)
    /// and feeds it into the recording.
    private func startCapture() throws {
        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else { throw RecorderError.noInput }
        guard let converter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
            throw RecorderError.converterUnavailable
        }
        self.converter = converter
        input.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, _ in
            self?.process(buffer)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            throw error
        }
    }

    /// Reopens the microphone without ending the recording (its file and
    /// positions carry on), e.g. when it delivers only silence.
    func restartCapture() throws {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        engine.reset()
        try AVAudioSession.sharedInstance().setActive(true)
        lock.lock(); signalSeen = false; lock.unlock()
        try startCapture()
    }

    /// One line about the audio route and format, for Documents/diagnostics.log.
    func describeInput() -> String {
        let s = AVAudioSession.sharedInstance()
        let inputs = s.currentRoute.inputs.map { "\($0.portType.rawValue):\($0.portName)" }.joined(separator: ",")
        let outputs = s.currentRoute.outputs.map { "\($0.portType.rawValue):\($0.portName)" }.joined(separator: ",")
        let format = engine.inputNode.outputFormat(forBus: 0)
        return "category=\(s.category.rawValue) mode=\(s.mode.rawValue) inputAvailable=\(s.isInputAvailable) " +
            "inputMuted=\(AVAudioApplication.shared.isInputMuted) permission=\(AVAudioApplication.shared.recordPermission.rawValue) " +
            "inputs=[\(inputs)] outputs=[\(outputs)] preferred=\(s.preferredInput?.portName ?? "nil") " +
            "format=\(format.sampleRate)Hz/\(format.channelCount)ch/\(format.commonFormat.rawValue) gain=\(s.inputGain) running=\(engine.isRunning) " +
            "signal=\(hasSignal) rawBuffers=\(rawBuffers) rawPeak=\(rawPeak) samples=\(sampleCount)"
    }

    /// Whether anything but exact digital silence has arrived since the
    /// recording (or the last restart) began. A real microphone is never
    /// perfectly silent; all zeros means the input isn't really connected.
    var hasSignal: Bool {
        lock.lock(); defer { lock.unlock() }
        return signalSeen
    }

    /// Switches between close-speech and room capture mid-recording (e.g.
    /// background voices on/off). Capture pauses for a moment; the recording,
    /// its file and its positions carry on.
    func setRoomCapture(_ enabled: Bool) throws {
        guard enabled != roomCapture else { return }
        roomCapture = enabled
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        try AVAudioSession.sharedInstance().setMode(Self.mode(roomCapture: enabled))
        try startCapture()
    }

    func stop() {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        isRunning = false
        lock.lock()
        let writer = self.writer
        self.writer = nil
        currentLevel = 0
        lock.unlock()
        do {
            try writer?.close()
        } catch {
            lock.lock(); currentWriteError = error.localizedDescription; lock.unlock()
        }
        converter = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func process(_ buffer: AVAudioPCMBuffer) {
        // Loudest raw microphone sample, before conversion (diagnostics).
        if let raw = buffer.floatChannelData?[0] {
            var peak: Float = 0
            for i in 0..<Int(buffer.frameLength) { peak = max(peak, abs(raw[i])) }
            lock.lock(); rawPeak = max(rawPeak, peak); rawBuffers += 1; lock.unlock()
        }
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

        let chunk = UnsafeBufferPointer(start: channel, count: Int(output.frameLength))
        var sumSquares: Float = 0
        for sample in chunk { sumSquares += sample * sample }
        let rms = sqrt(sumSquares / Float(max(chunk.count, 1)))
        // Map roughly -50 dB...0 dB onto 0...1.
        let db = 20 * log10(max(rms, 1e-6))
        let normalised = max(0, min(1, (db + 50) / 50))
        let pcm = WavFile.pcm16(chunk)

        let silent = chunk.allSatisfy { $0 == 0 }
        lock.lock()
        if !silent { signalSeen = true }
        samples.append(contentsOf: chunk)
        currentLevel = normalised
        if currentWriteError == nil, let writer {
            do {
                try writer.append(pcm)
            } catch {
                // Keep capturing into memory so live text still works.
                currentWriteError = error.localizedDescription
            }
        }
        lock.unlock()
    }
}
