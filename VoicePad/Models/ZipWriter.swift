import Foundation

/// Writes a ZIP archive with stored (uncompressed) entries, streaming file
/// contents so large recordings never sit in memory. Audio is already
/// compressed (or barely compressible), so storing costs little. Pure
/// Foundation so it can be unit-tested.
final class ZipWriter {
    private struct Entry {
        let name: Data
        let crc: UInt32
        let size: UInt32
        let offset: UInt32
        let time: UInt16
        let date: UInt16
    }

    private let handle: FileHandle
    private var entries: [Entry] = []
    private var offset: UInt64 = 0

    init(url: URL) throws {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        handle = try FileHandle(forWritingTo: url)
        try handle.truncate(atOffset: 0)
    }

    func add(_ name: String, data: Data, modified: Date = .now) throws {
        try add(name, size: data.count, crc: CRC32.of(data), modified: modified) { try self.write(data) }
    }

    func add(_ name: String, file: URL, modified: Date = .now) throws {
        let size = WavFile.fileSize(file)
        // First pass: checksum; second pass: copy.
        var crc = CRC32()
        try Self.chunks(of: file) { crc.update($0) }
        try add(name, size: size, crc: crc.value, modified: modified) {
            try Self.chunks(of: file) { try self.write($0) }
        }
    }

    /// Writes the central directory. The writer can't be used afterwards.
    func finish() throws {
        let start = offset
        for e in entries {
            var h = Data()
            h.u32(0x0201_4b50); h.u16(20); h.u16(20); h.u16(0x0800); h.u16(0)
            h.u16(e.time); h.u16(e.date); h.u32(e.crc); h.u32(e.size); h.u32(e.size)
            h.u16(UInt16(e.name.count)); h.u16(0); h.u16(0); h.u16(0); h.u16(0); h.u32(0); h.u32(e.offset)
            h.append(e.name)
            try write(h)
        }
        var end = Data()
        end.u32(0x0605_4b50); end.u16(0); end.u16(0)
        end.u16(UInt16(entries.count)); end.u16(UInt16(entries.count))
        end.u32(UInt32(offset - start)); end.u32(UInt32(start)); end.u16(0)
        try write(end)
        try handle.close()
    }

    private func add(_ name: String, size: Int, crc: UInt32, modified: Date, body: () throws -> Void) throws {
        guard size < Int(UInt32.max), offset < UInt64(UInt32.max) else { throw CocoaError(.fileWriteFileExists) }
        let nameData = Data(name.utf8)
        let (time, date) = Self.dosTime(modified)
        let entry = Entry(name: nameData, crc: crc, size: UInt32(size), offset: UInt32(offset), time: time, date: date)
        var h = Data()
        // Bit 11: names are UTF-8.
        h.u32(0x0403_4b50); h.u16(20); h.u16(0x0800); h.u16(0)
        h.u16(time); h.u16(date); h.u32(crc); h.u32(UInt32(size)); h.u32(UInt32(size))
        h.u16(UInt16(nameData.count)); h.u16(0)
        h.append(nameData)
        try write(h)
        try body()
        entries.append(entry)
    }

    private func write(_ data: Data) throws {
        try handle.write(contentsOf: data)
        offset += UInt64(data.count)
    }

    private static func chunks(of url: URL, _ body: (Data) throws -> Void) throws {
        let input = try FileHandle(forReadingFrom: url)
        defer { try? input.close() }
        while let chunk = try input.read(upToCount: 1 << 20), !chunk.isEmpty { try body(chunk) }
    }

    private static func dosTime(_ date: Date) -> (UInt16, UInt16) {
        let c = Calendar(identifier: .gregorian).dateComponents(in: .current, from: date)
        let time = UInt16((c.hour ?? 0) << 11 | (c.minute ?? 0) << 5 | (c.second ?? 0) / 2)
        let day = UInt16(max(0, (c.year ?? 1980) - 1980) << 9 | (c.month ?? 1) << 5 | (c.day ?? 1))
        return (time, day)
    }
}

struct CRC32 {
    private static let table: [UInt32] = (0..<256).map { i in
        var c = UInt32(i)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    private var crc: UInt32 = 0xFFFF_FFFF
    var value: UInt32 { crc ^ 0xFFFF_FFFF }

    mutating func update(_ data: Data) {
        for byte in data { crc = Self.table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8) }
    }

    static func of(_ data: Data) -> UInt32 {
        var c = CRC32()
        c.update(data)
        return c.value
    }
}

private extension Data {
    mutating func u16(_ v: UInt16) { Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) } }
    mutating func u32(_ v: UInt32) { Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) } }
}
