import Foundation

/// One saved version of a note's text.
struct Revision: Identifiable, Codable, Equatable {
    enum Source: String, Codable {
        case transcription   // produced by the speech model
        case edit            // typed by the user
        case restore         // user restored an older revision
        case retranscription // re-ran a model over the saved audio

        var label: String {
            switch self {
            case .transcription: return "Transcribed"
            case .edit: return "Edited"
            case .restore: return "Restored"
            case .retranscription: return "Re-transcribed"
            }
        }

        var systemImage: String {
            switch self {
            case .transcription: return "waveform"
            case .edit: return "pencil"
            case .restore: return "arrow.uturn.backward"
            case .retranscription: return "arrow.clockwise"
            }
        }
    }

    var id = UUID()
    var text: String
    var date: Date = .now
    var source: Source
    /// Engine/model that produced the text, when it came from a model.
    var engine: String?
}

/// A recording plus the full history of its transcript.
struct Note: Identifiable, Codable, Equatable {
    var id = UUID()
    var createdAt: Date = .now
    var duration: TimeInterval = 0
    /// File name inside the app's recordings directory.
    var audioFileName: String?
    /// Oldest first. The last element is the current text.
    var revisions: [Revision] = []

    var currentText: String { revisions.last?.text ?? "" }
}
