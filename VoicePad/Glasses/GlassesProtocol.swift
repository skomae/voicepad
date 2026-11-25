import Foundation

/// The parts of the Even Realities G1 protocol VoicePad speaks, as documented by
/// their own demo app (github.com/even-realities/EvenDemoApp: the README's protocol
/// section, `proto.dart` and `evenai_proto.dart`).
///
/// Every request is answered on the read characteristic by a packet starting with
/// the same command byte; for most commands the second byte is a status. Pure
/// Swift — `GlassesProtocolTests` covers the byte layout, which is the part that
/// cannot be checked without the glasses in hand.
enum GlassesProtocol {
    static let uartService = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    static let uartWrite = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    static let uartRead = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

    static let exit: UInt8 = 0x18
    static let heartbeatCommand: UInt8 = 0x25
    static let batteryCommand: UInt8 = 0x2C
    static let textCommand: UInt8 = 0x4E

    /// Status byte (index 1) of a response: done.
    static let statusOK: UInt8 = 0xC9
    /// Status byte of a response: received, more expected (a middle packet).
    static let statusContinue: UInt8 = 0xCB
    /// Status byte of a response: refused.
    static let statusFailed: UInt8 = 0xCA

    /// Text bytes per 0x4E packet. The demo app splits at 191 so the 9-byte header
    /// plus data stays at 200 bytes, inside what the glasses accept whatever MTU was
    /// negotiated. A full 5x40 page is ~205 bytes of ASCII and up to ~300 of CJK, so
    /// a page is usually two packets.
    static let textChunk = 191

    /// Screen status for 0x4E: upper nibble 0x70 is "text show" (the mode the demo's
    /// text sender uses, which the glasses leave up until told otherwise), lower
    /// nibble 0x01 is "display new content".
    static let textShowNew = 0x71

    /// Leave whatever is on screen and go back to the dashboard.
    static func exitPacket() -> [UInt8] { [exit] }

    /// Battery level request; answered by `2C 66 <percent> ...`.
    static func battery() -> [UInt8] { [batteryCommand] }

    /// Keep-alive the official app sends both arms every 8 s:
    /// `25 <len lo> <len hi> <seq> 04 <seq>`, answered by `25 .. .. .. 04 ..`.
    static func heartbeat(sequence: Int) -> [UInt8] {
        let length = 6
        let seq = UInt8(sequence % 0xFF)
        return [heartbeatCommand, UInt8(length & 0xFF), UInt8((length >> 8) & 0xFF), seq, 0x04, seq]
    }

    /// One page of `text` as the 0x4E packets that carry it:
    /// `4E <seq> <packets> <index> <screen status> <pos hi> <pos lo> <page> <pages> <data…>`,
    /// the UTF-8 text cut into `textChunk`-byte pieces. All packets of a page share
    /// `seq`, which should change from one page to the next.
    static func text(
        _ text: String,
        sequence: Int,
        page: Int = 1,
        pages: Int = 1,
        screenStatus: Int = textShowNew,
        position: Int = 0
    ) -> [[UInt8]] {
        // Chunk on character boundaries, not byte offsets: 191 bytes is not a
        // multiple of 3, so a byte-wise cut through Chinese text would split a
        // character in half and the glasses would show a replacement mark at the end
        // of every page.
        var chunks: [[UInt8]] = []
        var chunk: [UInt8] = []
        for character in text {
            let bytes = Array(String(character).utf8)
            if !chunk.isEmpty, chunk.count + bytes.count > textChunk {
                chunks.append(chunk)
                chunk = []
            }
            chunk.append(contentsOf: bytes)
        }
        chunks.append(chunk)
        // Nothing to say is still one packet, so the glasses clear the page.
        if chunks.isEmpty { chunks = [[]] }

        let count = chunks.count
        precondition(count <= 255, "Text too long for one page: \(text.utf8.count) bytes")
        return (0..<count).map { index in
            [
                textCommand,
                UInt8(sequence & 0xFF),
                UInt8(count),
                UInt8(index),
                UInt8(screenStatus),
                UInt8((position >> 8) & 0xFF),
                UInt8(position & 0xFF),
                UInt8(page),
                UInt8(pages),
            ] + chunks[index]
        }
    }

    /// Whether `response` acknowledges a packet of a command whose status sits at index 1.
    static func accepted(_ response: [UInt8]?) -> Bool {
        guard let response, response.count > 1 else { return false }
        return response[1] == statusOK || response[1] == statusContinue
    }

    /// Whether `response` answers a heartbeat.
    static func heartbeatAnswered(_ response: [UInt8]?) -> Bool {
        guard let response, response.count > 5 else { return false }
        return response[0] == heartbeatCommand && response[4] == 0x04
    }

    /// The battery percentage in `response`, if it is a battery report.
    static func batteryLevel(_ response: [UInt8]?) -> Int? {
        guard let response, response.count > 2, response[0] == batteryCommand, response[1] == 0x66 else { return nil }
        return Int(response[2])
    }

    static func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined(separator: " ")
    }
}

/// An arm advertises as `G1_<channel>_<L|R>_<serial>`, and some firmware says
/// `Even G1_...`. The prefix has changed between revisions, so parse rather than
/// compare prefixes: a stale prefix makes the glasses invisible.
enum G1Name: Equatable {
    case arm(channel: Int, side: Side, serial: String)
    enum Side: String { case left = "L", right = "R" }

    static func parse(_ raw: String?) -> G1Name? {
        guard var name = raw?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return nil }
        if name.hasPrefix("Even ") { name = String(name.dropFirst("Even ".count)) }
        let parts = name.split(separator: "_")
        guard parts.count >= 4, parts[0] == "G1",
              let channel = Int(parts[1]),
              let side = Side(rawValue: String(parts[2]))
        else { return nil }
        return .arm(channel: channel, side: side, serial: parts[3...].joined(separator: "_"))
    }
}
