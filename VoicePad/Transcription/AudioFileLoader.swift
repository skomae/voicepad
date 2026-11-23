import AVFoundation

enum AudioFileLoader {
    /// Reads an audio file and returns 16 kHz mono Float32 samples.
    static func loadSamples(from url: URL) throws -> [Float] {
        let file = try AVAudioFile(forReading: url)
        let sourceFormat = file.processingFormat
        guard let target = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                         sampleRate: AudioRecorder.sampleRate,
                                         channels: 1,
                                         interleaved: false),
              let input = AVAudioPCMBuffer(pcmFormat: sourceFormat, frameCapacity: AVAudioFrameCount(file.length))
        else { return [] }
        try file.read(into: input)

        if sourceFormat.sampleRate == target.sampleRate, sourceFormat.channelCount == 1,
           sourceFormat.commonFormat == .pcmFormatFloat32, let data = input.floatChannelData?[0] {
            return Array(UnsafeBufferPointer(start: data, count: Int(input.frameLength)))
        }

        guard let converter = AVAudioConverter(from: sourceFormat, to: target) else { return [] }
        let capacity = AVAudioFrameCount(Double(input.frameLength) * target.sampleRate / sourceFormat.sampleRate) + 1024
        guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return [] }
        var consumed = false
        var error: NSError?
        converter.convert(to: output, error: &error) { _, status in
            if consumed { status.pointee = .endOfStream; return nil }
            consumed = true
            status.pointee = .haveData
            return input
        }
        if let error { throw error }
        guard let data = output.floatChannelData?[0] else { return [] }
        return Array(UnsafeBufferPointer(start: data, count: Int(output.frameLength)))
    }
}
