import Foundation

/// Decides how to feed long audio to a speech model. Pure Swift so it can be
/// unit-tested.
///
/// Input is where the voice-activity detector heard speech. Output is a list
/// of chunks, each a list of audio spans to join together:
/// - silence is trimmed: only speech (plus a little padding) is kept, so long
///   pauses and empty stretches never reach the model (and Whisper can't
///   "hallucinate" text into silence);
/// - with `.utterances` every utterance (speech between pauses) is its own
///   chunk. Best for models whose cost grows with audio length (Parakeet,
///   SenseVoice), and it keeps one language per model call, which matters:
///   Parakeet can drop a sentence when English and French share one call;
/// - with `.longestPauses` utterances are packed together, and a stretch too
///   long for one model call is split at its **longest pause**, then the next
///   longest, and so on, until every chunk fits. Best for Whisper, which costs
///   the same for 1 s or 30 s of audio;
/// - continuous speech longer than the limit, with no pause at all, is cut by
///   `plan`'s `cutInside` (the quietest moment in a window), as a last resort.
enum ChunkPlanner {
    /// Samples [start, end).
    struct Span: Equatable, CustomStringConvertible {
        var start: Int
        var end: Int
        var length: Int { end - start }
        var description: String { "[\(start), \(end))" }
    }

    enum Grouping { case utterances, longestPauses }

    static func length(_ chunk: [Span]) -> Int { chunk.reduce(0) { $0 + $1.length } }

    /// - Parameters:
    ///   - speech: speech regions from the VAD, in order.
    ///   - audioEnd: total samples available (padding is clamped to it).
    ///   - maxLength: longest chunk, in samples, one model call should get.
    ///   - pad: samples of context kept either side of each speech region.
    ///   - cutInside: picks a cut point in [from, to) for speech with no pause.
    static func plan(
        _ speech: [Span],
        audioEnd: Int,
        maxLength: Int,
        pad: Int,
        grouping: Grouping = .longestPauses,
        cutInside: (_ from: Int, _ to: Int) -> Int = { ($0 + $1) / 2 }
    ) -> [[Span]] {
        let padded = padWithoutJoining(speech, audioEnd: audioEnd, pad: pad)
        if padded.isEmpty { return [] }
        switch grouping {
        case .longestPauses:
            return split(padded, max: maxLength, cutInside: cutInside)
        case .utterances:
            return padded.flatMap { split([$0], max: maxLength, cutInside: cutInside) }
        }
    }

    /// Adds up to `pad` of context either side of each utterance, but never
    /// more than half the pause to its neighbour, so two utterances separated
    /// by a short pause stay separate.
    private static func padWithoutJoining(_ speech: [Span], audioEnd: Int, pad: Int) -> [Span] {
        let sorted = speech.filter { $0.length > 0 }.sorted { $0.start < $1.start }
        var merged: [Span] = []
        for s in sorted {
            if let last = merged.last, s.start <= last.end {
                merged[merged.count - 1] = Span(start: last.start, end: max(last.end, s.end))
            } else {
                merged.append(s)
            }
        }
        return merged.indices.map { i in
            let s = merged[i]
            let before = i == 0 ? pad : min(pad, (s.start - merged[i - 1].end) / 2)
            let after = i == merged.count - 1 ? pad : min(pad, (merged[i + 1].start - s.end) / 2)
            return Span(start: max(0, s.start - before), end: min(audioEnd, s.end + after))
        }
    }

    private static func split(_ group: [Span], max: Int, cutInside: (Int, Int) -> Int) -> [[Span]] {
        if length(group) <= max { return [group] }
        if group.count == 1 {
            // One unbroken stretch of speech: cut near the limit, at the quietest point.
            let s = group[0]
            let from = s.start + max * 6 / 10
            let to = s.start + max
            let cut = Swift.min(Swift.max(cutInside(from, to), from), to)
            return [[Span(start: s.start, end: cut)]] + split([Span(start: cut, end: s.end)], max: max, cutInside: cutInside)
        }
        // Split at the longest pause between speech regions.
        let at = (0..<(group.count - 1)).max { group[$0 + 1].start - group[$0].end < group[$1 + 1].start - group[$1].end }!
        return split(Array(group[0...at]), max: max, cutInside: cutInside)
            + split(Array(group[(at + 1)...]), max: max, cutInside: cutInside)
    }
}
