import XCTest

/// Nothing here can be checked without a pair of G1s, so the byte layout is pinned
/// against Even Realities' documented protocol instead.
final class GlassesProtocolTests: XCTestCase {
    func testTextPacketHeader() {
        let packets = GlassesProtocol.text("hello", sequence: 7)
        XCTAssertEqual(packets.count, 1)
        let packet = packets[0]
        XCTAssertEqual(packet[0], 0x4E)
        XCTAssertEqual(packet[1], 7)            // sequence
        XCTAssertEqual(packet[2], 1)            // one packet
        XCTAssertEqual(packet[3], 0)            // index
        XCTAssertEqual(packet[4], 0x71)         // text show, new content
        XCTAssertEqual(packet[5], 0)            // position high
        XCTAssertEqual(packet[6], 0)            // position low
        XCTAssertEqual(packet[7], 1)            // page
        XCTAssertEqual(packet[8], 1)            // pages
        XCTAssertEqual(Array(packet[9...]), Array("hello".utf8))
    }

    func testLongTextIsSplitIntoAcknowledgedChunks() {
        let text = String(repeating: "a", count: 400)
        let packets = GlassesProtocol.text(text, sequence: 1)
        XCTAssertEqual(packets.count, 3)        // 191 + 191 + 18
        XCTAssertEqual(packets[0][2], 3)        // packet count in every header
        XCTAssertEqual(packets[2][3], 2)        // index
        XCTAssertEqual(packets.map { $0.count - 9 }.reduce(0, +), 400)
    }

    func testWideTextSplitsByBytesNotCharacters() {
        // 100 Han characters are 300 UTF-8 bytes, so they do not fit one 191-byte
        // packet — and the cut must not land inside a character, or the glasses show
        // a replacement mark at the end of the page.
        let text = String(repeating: "中", count: 100)
        let packets = GlassesProtocol.text(text, sequence: 1)
        XCTAssertEqual(packets.count, 2)
        for packet in packets {
            XCTAssertLessThanOrEqual(packet.count - 9, GlassesProtocol.textChunk)
            let data = Data(packet[9...])
            XCTAssertNotNil(String(data: data, encoding: .utf8), "a character was split across packets")
        }
        let joined = packets.compactMap { String(data: Data($0[9...]), encoding: .utf8) }.joined()
        XCTAssertEqual(joined, text)
    }

    func testHeartbeatBytes() {
        XCTAssertEqual(GlassesProtocol.heartbeat(sequence: 3), [0x25, 6, 0, 3, 0x04, 3])
    }

    func testExitAndBattery() {
        XCTAssertEqual(GlassesProtocol.exitPacket(), [0x18])
        XCTAssertEqual(GlassesProtocol.battery(), [0x2C])
    }

    func testAcknowledgedStatuses() {
        XCTAssertTrue(GlassesProtocol.accepted([0x4E, 0xC9]))
        XCTAssertTrue(GlassesProtocol.accepted([0x4E, 0xCB]))
        XCTAssertFalse(GlassesProtocol.accepted([0x4E, 0xCA]))
        XCTAssertFalse(GlassesProtocol.accepted([0x4E]))
        XCTAssertFalse(GlassesProtocol.accepted(nil))
    }

    func testHeartbeatAnswer() {
        XCTAssertTrue(GlassesProtocol.heartbeatAnswered([0x25, 0, 0, 3, 0x04, 3]))
        XCTAssertFalse(GlassesProtocol.heartbeatAnswered([0x25, 0x01]))
        XCTAssertFalse(GlassesProtocol.heartbeatAnswered(nil))
    }

    func testBatteryLevel() {
        XCTAssertEqual(GlassesProtocol.batteryLevel([0x2C, 0x66, 82]), 82)
        XCTAssertNil(GlassesProtocol.batteryLevel([0x2C, 0xC9, 82]))
        XCTAssertNil(GlassesProtocol.batteryLevel([0x2C]))
    }

    func testArmNamesFromBothFirmwarePrefixes() {
        XCTAssertEqual(G1Name.parse("G1_85_L_89ECF2"), .arm(channel: 85, side: .left, serial: "89ECF2"))
        XCTAssertEqual(G1Name.parse("Even G1_85_R_3DAF8A"), .arm(channel: 85, side: .right, serial: "3DAF8A"))
        XCTAssertNil(G1Name.parse("AirPods"))
        XCTAssertNil(G1Name.parse("G1_85_X_000001"))
        XCTAssertNil(G1Name.parse(nil))
    }
}
