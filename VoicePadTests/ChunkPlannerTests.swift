import XCTest

/// Same cases as the Android app's ChunkPlannerTest.
final class ChunkPlannerTests: XCTestCase {
    typealias Span = ChunkPlanner.Span
    private let sec = 16_000
    private func s(_ from: Double, _ to: Double) -> Span { Span(start: Int(from * Double(sec)), end: Int(to * Double(sec))) }

    private func check(_ chunks: [[Span]], _ speech: [Span], max: Int, file: StaticString = #filePath, line: UInt = #line) {
        // Every chunk fits, chunks don't overlap and come in order.
        for c in chunks { XCTAssertLessThanOrEqual(ChunkPlanner.length(c), max, "chunk too long", file: file, line: line) }
        let flat = chunks.flatMap { $0 }
        for (a, b) in zip(flat, flat.dropFirst()) { XCTAssertLessThanOrEqual(a.end, b.start, "overlap/out of order \(a) \(b)", file: file, line: line) }
        // All speech is kept (possibly across consecutive pieces of a hard cut).
        for sp in speech {
            var covered = sp.start
            for piece in flat where piece.start <= covered && piece.end > covered { covered = piece.end }
            XCTAssertGreaterThanOrEqual(covered, sp.end, "lost speech \(sp)", file: file, line: line)
        }
    }

    func testNoSpeechMeansNothingToTranscribe() {
        XCTAssertTrue(ChunkPlanner.plan([], audioEnd: 60 * sec, maxLength: 25 * sec, pad: sec / 4).isEmpty)
    }

    func testSilenceIsTrimmedButContextKept() {
        // 2 s of speech in the middle of a minute of silence.
        let chunks = ChunkPlanner.plan([s(30, 32)], audioEnd: 60 * sec, maxLength: 25 * sec, pad: sec / 4)
        XCTAssertEqual(chunks, [[s(29.75, 32.25)]])
    }

    func testLongPausesInsideAShortChunkAreDropped() {
        let speech = [s(1, 4), s(14, 17)] // 10 s pause between
        let chunks = ChunkPlanner.plan(speech, audioEnd: 20 * sec, maxLength: 25 * sec, pad: sec / 4)
        XCTAssertEqual(chunks.count, 1)
        XCTAssertEqual(ChunkPlanner.length(chunks[0]), 7 * sec) // 6 s speech + 4 × 0.25 s padding
    }

    func testTooMuchSpeechIsSplitAtTheLongestPauseFirst() {
        // Three 10 s utterances; the pause after the first is 0.6 s, after the second 4 s.
        let speech = [s(0, 10), s(10.6, 20.6), s(24.6, 34.6)]
        let chunks = ChunkPlanner.plan(speech, audioEnd: 40 * sec, maxLength: 25 * sec, pad: sec / 4)
        check(chunks, speech, max: 25 * sec)
        XCTAssertEqual(chunks.count, 2)
        // The cut is in the 4 s pause, not the 0.6 s one.
        XCTAssertEqual(chunks[0].last?.end, s(10.35, 20.85).end)
        XCTAssertEqual(chunks[1].first?.start, s(24.35, 34.85).start)
    }

    func testAnHourOfSpeechWithPausesFits() {
        var speech: [Span] = []
        var t = 2.0
        var i = 0
        while t < 3600 {
            let talk = 3.0 + Double(i * 7 % 11)        // 3–13 s utterances
            let pause = 0.4 + Double(i * 5 % 9) * 0.3  // 0.4–2.8 s pauses
            speech.append(s(t, t + talk))
            t += talk + pause
            i += 1
        }
        let chunks = ChunkPlanner.plan(speech, audioEnd: 3610 * sec, maxLength: 25 * sec, pad: sec / 4)
        check(chunks, speech, max: 25 * sec)
        // Trimming removes most of the silence.
        let kept = chunks.reduce(0) { $0 + ChunkPlanner.length($1) }
        XCTAssertLessThan(kept, speech.last!.end - speech.first!.start)
    }

    func testContinuousSpeechIsCutAtTheQuietestPoint() {
        let speech = [s(0, 60)]
        var cuts: [(Int, Int)] = []
        let chunks = ChunkPlanner.plan(speech, audioEnd: 60 * sec, maxLength: 25 * sec, pad: 0) { from, to in
            cuts.append((from, to)); return to - self.sec
        }
        let byUtterance = ChunkPlanner.plan(speech, audioEnd: 60 * sec, maxLength: 25 * sec, pad: 0, grouping: .utterances) { _, to in to - self.sec }
        XCTAssertEqual(chunks, byUtterance)
        check(chunks, speech, max: 25 * sec)
        XCTAssertEqual(chunks.count, 3)
        XCTAssertEqual(chunks[0].first?.end, 24 * sec)
        XCTAssertEqual(chunks[0].count, 1)
        // The cut window is the last 40 % before the limit.
        XCTAssertEqual(cuts.first?.0, 15 * sec)
        XCTAssertEqual(cuts.first?.1, 25 * sec)
    }

    func testPaddingNeverRunsPastTheAudio() {
        let chunks = ChunkPlanner.plan([s(0, 1), s(9.9, 10)], audioEnd: 10 * sec, maxLength: 25 * sec, pad: sec / 2)
        XCTAssertEqual(chunks.first?.first?.start, 0)
        XCTAssertEqual(chunks.last?.last?.end, 10 * sec)
    }

    func testUtterancesAShortPauseApartStaySeparate() {
        // English then French with only 0.3 s between them: padding must not join them.
        let speech = [s(20, 23.8), s(24.1, 29.1)]
        let chunks = ChunkPlanner.plan(speech, audioEnd: 40 * sec, maxLength: 25 * sec, pad: sec / 4, grouping: .utterances)
        XCTAssertEqual(chunks, [[s(19.75, 23.95)], [s(23.95, 29.35)]])
    }

    func testUtteranceGroupingGivesOneChunkPerUtterance() {
        let speech = [s(1, 3), s(5, 40), s(41, 42)]
        let chunks = ChunkPlanner.plan(speech, audioEnd: 50 * sec, maxLength: 25 * sec, pad: sec / 4, grouping: .utterances)
        check(chunks, speech, max: 25 * sec)
        XCTAssertEqual(chunks.count, 4) // the 35 s utterance is cut once
        for c in chunks { XCTAssertEqual(c.count, 1) }
    }
}
