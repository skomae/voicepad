import Foundation

/// When to start a new note automatically while recording. Pure Swift so the
/// rules can be unit-tested.
enum AutoSplit: String, CaseIterable, Identifiable, Codable {
    case off
    case silence2s, silence3s, silence5s, silence10s, silence30s
    case every5min, every15min, every30min

    var id: String { rawValue }

    var label: String {
        switch self {
        case .off: return "Off"
        case .silence2s: return "After 2 s of silence"
        case .silence3s: return "After 3 s of silence"
        case .silence5s: return "After 5 s of silence"
        case .silence10s: return "After 10 s of silence"
        case .silence30s: return "After 30 s of silence"
        case .every5min: return "Every 5 minutes"
        case .every15min: return "Every 15 minutes"
        case .every30min: return "Every 30 minutes"
        }
    }

    /// Short form for the badge on the scissors.
    var badge: String? {
        switch self {
        case .off: return nil
        case .silence2s: return "2s"
        case .silence3s: return "3s"
        case .silence5s: return "5s"
        case .silence10s: return "10s"
        case .silence30s: return "30s"
        case .every5min: return "5m"
        case .every15min: return "15m"
        case .every30min: return "30m"
        }
    }

    var silenceSeconds: Int? {
        switch self {
        case .silence2s: return 2
        case .silence3s: return 3
        case .silence5s: return 5
        case .silence10s: return 10
        case .silence30s: return 30
        default: return nil
        }
    }

    var everyMinutes: Int? {
        switch self {
        case .every5min: return 5
        case .every15min: return 15
        case .every30min: return 30
        default: return nil
        }
    }

    /// A pause at least this long counts as a good place to split.
    static let pauseSeconds = 0.7
    static let graceSeconds = 60

    /// Until the user picks one: a new note after each 3-second pause.
    static let `default`: AutoSplit = .silence3s

    /// Whether to split now. Positions are in samples at `rate`.
    /// - Parameters:
    ///   - noteStart: where the current note began.
    ///   - now: how much has been recorded.
    ///   - speaking: the voice detector hears speech right now.
    ///   - lastVoiceAt: last moment speech was heard in this note (nil: none yet).
    func shouldSplit(noteStart: Int, now: Int, speaking: Bool, lastVoiceAt: Int?, rate: Int) -> Bool {
        // Never split off a note with nothing said in it.
        guard let lastVoiceAt else { return false }
        if speaking {
            // …or regardless, if nobody has paused for a minute past the interval.
            guard let minutes = everyMinutes else { return false }
            return now - noteStart >= (minutes * 60 + Self.graceSeconds) * rate
        }
        if let seconds = silenceSeconds { return now - lastVoiceAt >= seconds * rate }
        if let minutes = everyMinutes {
            // At the first pause after the interval (no word gets cut).
            return now - noteStart >= minutes * 60 * rate && Double(now - lastVoiceAt) >= Self.pauseSeconds * Double(rate)
        }
        return false
    }
}
