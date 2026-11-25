import CoreGraphics
import XCTest

/// The posture decides which layout the app shows, so the numbers are the contract:
/// the Duo's outer display must never be read as wide, and its inner one always.
final class DuoPostureTests: XCTestCase {
    func testFoldedPhoneIsCompact() {
        XCTAssertEqual(DuoPosture.of(CGSize(width: 466, height: 678)), .compact)
    }

    func testUnfoldedDuoIsWide() {
        XCTAssertEqual(DuoPosture.of(CGSize(width: 669, height: 951)), .wide)
    }

    func testUnfoldedDuoLandscapeIsWide() {
        XCTAssertEqual(DuoPosture.of(CGSize(width: 951, height: 669)), .wide)
    }

    func testFoldedDuoLandscapeIsStillCompact() {
        // Rotating the outer display must not turn into the tabletop layout: the
        // short side stays 466 pt.
        XCTAssertEqual(DuoPosture.of(CGSize(width: 678, height: 466)), .compact)
    }

    func testIPadIsWide() {
        XCTAssertEqual(DuoPosture.of(CGSize(width: 1024, height: 768)), .wide)
    }

    func testUnmeasuredWindowIsCompact() {
        // SwiftUI proposes .zero before the first layout; showing the tabletop
        // layout for one frame would flash.
        XCTAssertEqual(DuoPosture.of(.zero), .compact)
    }

    func testLandscape() {
        XCTAssertTrue(DuoPosture.isLandscape(CGSize(width: 951, height: 669)))
        XCTAssertFalse(DuoPosture.isLandscape(CGSize(width: 669, height: 951)))
    }
}
