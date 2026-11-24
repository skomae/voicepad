import AVFoundation

/// Compresses recordings for the archive: 16 kHz mono WAV (~1.9 MB a minute)
/// to AAC in an .m4a file at 24 kbit/s (~0.18 MB a minute), which still plays
/// anywhere and transcribes fine. Decoding goes the other way, so an archived
/// note can still be re-transcribed.
enum AudioArchive {
    enum ArchiveError: LocalizedError {
        case wrongLength(expected: Double, actual: Double)

        var errorDescription: String? {
            switch self {
            case let .wrongLength(expected, actual):
                return String(format: "Compressed audio is %.1f s, expected %.1f s.", actual, expected)
            }
        }
    }

    private static let block: AVAudioFrameCount = 16_000

    /// Encodes `wav` into `out` and checks the result plays back at the same
    /// length. Throws (and leaves no `out`) if anything is off, so the caller
    /// keeps the WAV.
    static func compress(_ wav: URL, to out: URL) throws {
        // AVAudioFile picks the container from the extension, so keep ".m4a".
        let tmp = out.deletingPathExtension().appendingPathExtension("part.m4a")
        try? FileManager.default.removeItem(at: tmp)
        do {
            let expected = WavFile.duration(wav)
            try encode(wav, to: tmp)
            let check = try AVAudioFile(forReading: tmp)
            let actual = Double(check.length) / check.fileFormat.sampleRate
            guard abs(actual - expected) <= max(0.5, expected * 0.02) else {
                throw ArchiveError.wrongLength(expected: expected, actual: actual)
            }
            try? FileManager.default.removeItem(at: out)
            try FileManager.default.moveItem(at: tmp, to: out)
        } catch {
            try? FileManager.default.removeItem(at: tmp)
            throw error
        }
    }

    private static func encode(_ wav: URL, to url: URL) throws {
        let input = try AVAudioFile(forReading: wav)
        let format = input.processingFormat
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 24_000,
        ]
        // Scoped so the file is finished (closed) before it is checked.
        let output = try AVAudioFile(forWriting: url, settings: settings, commonFormat: format.commonFormat, interleaved: format.isInterleaved)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: block) else { return }
        while input.framePosition < input.length {
            try input.read(into: buffer, frameCount: block)
            if buffer.frameLength == 0 { break }
            try output.write(from: buffer)
        }
    }

    /// Decodes a compressed recording to a 16 kHz mono WAV, for transcription.
    static func decodeToWav(_ url: URL, to wav: URL) throws {
        let input = try AVAudioFile(forReading: url)
        guard let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(WavFile.sampleRate), channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: input.processingFormat, to: target),
              let inBuffer = AVAudioPCMBuffer(pcmFormat: input.processingFormat, frameCapacity: block),
              let outBuffer = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: block * 2)
        else { throw CocoaError(.fileReadCorruptFile) }
        let writer = try WavWriter(url: wav)
        var finished = false
        while !finished {
            outBuffer.frameLength = 0
            var readError: Error?
            let status = converter.convert(to: outBuffer, error: nil) { _, inputStatus in
                do {
                    guard input.framePosition < input.length else {
                        inputStatus.pointee = .endOfStream
                        return nil
                    }
                    try input.read(into: inBuffer, frameCount: block)
                    inputStatus.pointee = inBuffer.frameLength > 0 ? .haveData : .endOfStream
                    return inBuffer
                } catch {
                    readError = error
                    inputStatus.pointee = .endOfStream
                    return nil
                }
            }
            if let readError { throw readError }
            if status == .error { throw CocoaError(.fileReadCorruptFile) }
            if outBuffer.frameLength > 0, let channel = outBuffer.floatChannelData?[0] {
                try writer.append(WavFile.pcm16(UnsafeBufferPointer(start: channel, count: Int(outBuffer.frameLength))))
            }
            finished = status == .endOfStream || (status == .inputRanDry && outBuffer.frameLength == 0)
        }
        try writer.close()
    }
}
