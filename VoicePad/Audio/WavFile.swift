import Foundation

/// 16 kHz, mono, 16-bit PCM WAV helpers. No AVFoundation, so unit-testable and
/// usable on a file the app was killed while writing.
enum WavFile {
    static let sampleRate = 16_000
    static let headerBytes = 44

    static func header(dataBytes: Int) -> Data {
        let data = UInt32(clamping: max(0, min(dataBytes, Int(UInt32.max) - 36)))
        var out = Data(capacity: headerBytes)
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: $0) } }
        out.append(contentsOf: Array("RIFF".utf8)); u32(36 + data)
        out.append(contentsOf: Array("WAVE".utf8))
        out.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1)
        u32(UInt32(sampleRate)); u32(UInt32(sampleRate * 2)); u16(2); u16(16)
        out.append(contentsOf: Array("data".utf8)); u32(data)
        return out
    }

    /// Rewrites the header in place, leaving the handle at the end of the file.
    static func writeHeader(_ handle: FileHandle, dataBytes: Int) throws {
        try handle.seek(toOffset: 0)
        try handle.write(contentsOf: header(dataBytes: dataBytes))
        try handle.seekToEnd()
    }

    static func fileSize(_ url: URL) -> Int {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
    }

    /// Makes a WAV readable after the app died mid-recording: sizes the header
    /// from whatever audio reached the disk. Returns the duration in seconds.
    @discardableResult
    static func repair(_ url: URL) -> Double {
        let size = fileSize(url)
        guard FileManager.default.fileExists(atPath: url.path), size >= headerBytes else { return 0 }
        let dataBytes = (size - headerBytes) / 2 * 2 // whole samples only
        guard let handle = try? FileHandle(forUpdating: url) else { return 0 }
        defer { try? handle.close() }
        do {
            try handle.truncate(atOffset: UInt64(headerBytes + dataBytes))
            try writeHeader(handle, dataBytes: dataBytes)
            try handle.synchronize()
        } catch {
            return 0
        }
        return Double(dataBytes) / 2 / Double(sampleRate)
    }

    static func duration(_ url: URL) -> Double {
        let size = fileSize(url)
        return size <= headerBytes ? 0 : Double((size - headerBytes) / 2) / Double(sampleRate)
    }

    /// Reads samples as floats in -1...1, ignoring the header's size fields.
    static func read(_ url: URL) throws -> [Float] {
        let bytes = try Data(contentsOf: url)
        guard bytes.count > headerBytes else { return [] }
        let n = (bytes.count - headerBytes) / 2
        return bytes.withUnsafeBytes { raw -> [Float] in
            let base = raw.baseAddress!.advanced(by: headerBytes)
            return (0..<n).map { i in
                let lo = UInt16(base.load(fromByteOffset: 2 * i, as: UInt8.self))
                let hi = UInt16(base.load(fromByteOffset: 2 * i + 1, as: UInt8.self))
                return Float(Int16(bitPattern: lo | hi << 8)) / 32768
            }
        }
    }

    /// Converts float samples to 16-bit little-endian PCM bytes.
    static func pcm16(_ samples: UnsafeBufferPointer<Float>) -> Data {
        var data = Data(count: samples.count * 2)
        data.withUnsafeMutableBytes { raw in
            let out = raw.bindMemory(to: Int16.self)
            for (i, s) in samples.enumerated() {
                out[i] = Int16(max(-1, min(1, s)) * Float(Int16.max)).littleEndian
            }
        }
        return data
    }
}

/// Appends 16 kHz mono PCM to a WAV file, keeping the header valid on disk
/// every few seconds so a killed app still leaves a playable file.
final class WavWriter {
    private let handle: FileHandle
    private(set) var dataBytes = 0
    private var bytesSinceCheckpoint = 0
    private let checkpointBytes = WavFile.sampleRate * 2 * 5 // ~5 s

    init(url: URL) throws {
        FileManager.default.createFile(atPath: url.path, contents: WavFile.header(dataBytes: 0))
        handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd()
    }

    func append(_ pcm: Data) throws {
        try handle.write(contentsOf: pcm)
        dataBytes += pcm.count
        bytesSinceCheckpoint += pcm.count
        if bytesSinceCheckpoint >= checkpointBytes {
            bytesSinceCheckpoint = 0
            try? WavFile.writeHeader(handle, dataBytes: dataBytes)
            try? handle.synchronize()
        }
    }

    func close() throws {
        defer { try? handle.close() }
        try WavFile.writeHeader(handle, dataBytes: dataBytes)
        try handle.synchronize()
    }
}
