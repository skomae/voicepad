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

/// Where a note's audio is in the transcription pipeline. Saved with the note
/// so that a crash, a failed model or a killed app never loses track of a
/// recording that still needs text.
enum TranscriptionStatus: String, Codable {
    /// Microphone is (or was, if the app died) writing this note's audio.
    case recording
    /// Audio is saved and waiting for a model.
    case pending
    /// A model is working on it right now.
    case inProgress
    /// The current text came from a successful model run (or the user).
    case done
    /// The last attempt failed; the audio is kept for a retry.
    case failed
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
    var status: TranscriptionStatus = .done
    /// Model attempts so far; limits automatic retries of a note that keeps failing.
    var attempts = 0
    var lastError: String?
    /// Text recovered so far while recording, kept if the final pass fails.
    var partialText: String?
    var pinned = false
    /// When it was pinned; pinned notes are listed most recently pinned first.
    var pinnedAt: Date?

    var currentText: String { revisions.last?.text ?? "" }

    /// True when this note's audio still needs a successful model run.
    var needsTranscription: Bool { status == .pending || status == .failed }

    /// Returns the note with a new revision appended, unless the text is unchanged.
    func withRevision(_ text: String, source: Revision.Source, engine: String?) -> Note {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !revisions.isEmpty, currentText == trimmed { return self }
        var copy = self
        copy.revisions.append(Revision(text: trimmed, source: source, engine: engine))
        return copy
    }

    init(id: UUID = UUID(), createdAt: Date = .now, audioFileName: String? = nil, status: TranscriptionStatus = .done) {
        self.id = id
        self.createdAt = createdAt
        self.audioFileName = audioFileName
        self.status = status
    }

    // Notes saved by older versions lack the newer fields; default them rather
    // than failing to decode (which would lose every note).
    enum CodingKeys: String, CodingKey {
        case id, createdAt, duration, audioFileName, revisions, status, attempts, lastError, partialText, pinned, pinnedAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? .now
        duration = try c.decodeIfPresent(TimeInterval.self, forKey: .duration) ?? 0
        audioFileName = try c.decodeIfPresent(String.self, forKey: .audioFileName)
        revisions = try c.decodeIfPresent([Revision].self, forKey: .revisions) ?? []
        status = (try? c.decodeIfPresent(TranscriptionStatus.self, forKey: .status)) ?? .done
        attempts = try c.decodeIfPresent(Int.self, forKey: .attempts) ?? 0
        lastError = try c.decodeIfPresent(String.self, forKey: .lastError)
        partialText = try c.decodeIfPresent(String.self, forKey: .partialText)
        pinned = try c.decodeIfPresent(Bool.self, forKey: .pinned) ?? false
        pinnedAt = try c.decodeIfPresent(Date.self, forKey: .pinnedAt)
    }
}
