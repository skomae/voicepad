import ActivityKit
import AppIntents
import Foundation

/// The Live Activity shown while recording (Lock Screen and Dynamic Island):
/// the iOS counterpart of the Android app's recording notification. Compiled
/// into both the app and the widget extension.
struct RecordingActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// When the current part started; nil once recording has stopped.
        var startedAt: Date?
        var part: Int
        var backgroundVoices: Bool
        /// The latest transcript words.
        var latest: String
        /// The same line translated into English, for the person opposite: the phone
        /// lying on the table is their script, the way a foldable's cover screen was.
        var other: String?
        /// Detected language of the source line ("zh", "ja"…), for the label.
        var otherLanguage: String?
        /// Stopped or split-off notes still being transcribed.
        var finishing: Int

        var isRecording: Bool { startedAt != nil }
    }
}

/// What the Live Activity's buttons do. Set by the app; Live Activity intents
/// run in the app's process, so these reach the running recorder.
@MainActor
enum RecordingActions {
    static var stop: (() -> Void)?
    static var split: (() -> Void)?
}

struct StopRecordingIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Stop recording"
    static let isDiscoverable = false

    @MainActor
    func perform() async throws -> some IntentResult {
        RecordingActions.stop?()
        return .result()
    }
}

struct SplitRecordingIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Split recording"
    static let isDiscoverable = false

    @MainActor
    func perform() async throws -> some IntentResult {
        RecordingActions.split?()
        return .result()
    }
}
