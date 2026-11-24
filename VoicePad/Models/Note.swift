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
    /// Spoken language the model reported (e.g. "zh"), if any.
    var language: String?
}

enum TranslationStatus: String, Codable {
    case pending, done, failed, notNeeded
}

/// English translation (and romanisation) of one revision of a note.
struct NoteTranslation: Codable, Equatable {
    /// The revision this was made from; a newer revision makes it stale.
    var revisionID: UUID
    var status: TranslationStatus
    /// Detected language code, e.g. "zh", "fr", "es".
    var language: String?
    var english: String?
    /// Pinyin for Mandarin, Latin letters for other non-Latin scripts.
    var romanization: String?
    var attempts = 0
    var error: String?
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
    var translation: NoteTranslation?
    /// Recorded in background-voices mode: its audio is normalised before transcription.
    var backgroundVoices = false
    /// Moved out of the main list, with its audio compressed (see AudioArchive).
    var archived = false
    var archivedAt: Date?

    var currentText: String { revisions.last?.text ?? "" }

    /// The translation, if it matches the current text.
    var currentTranslation: NoteTranslation? {
        guard let translation, translation.revisionID == revisions.last?.id else { return nil }
        return translation
    }

    /// True when the current text has no finished translation yet. Stops
    /// retrying a failing translation after `maxAttempts`.
    func needsTranslation(maxAttempts: Int) -> Bool {
        guard let current = revisions.last, !current.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        guard let t = translation, t.revisionID == current.id else { return true }
        return t.status == .pending || (t.status == .failed && t.attempts < maxAttempts)
    }

    /// True when this note's audio still needs a successful model run.
    var needsTranscription: Bool { status == .pending || status == .failed }

    /// Returns the note with a new revision appended, unless the text is unchanged.
    func withRevision(_ text: String, source: Revision.Source, engine: String?, language: String? = nil) -> Note {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !revisions.isEmpty, currentText == trimmed { return self }
        var copy = self
        copy.revisions.append(Revision(text: trimmed, source: source, engine: engine, language: language))
        return copy
    }

    /// Adds a model's transcription without ever losing what the user wrote:
    /// - an empty result never replaces existing text;
    /// - an automatic result (after stop/split, or a queued retry) that arrives
    ///   after the user edited the note goes into the history *behind* their
    ///   edits, so their text stays current. A re-transcription they asked for
    ///   (`userRequested`) becomes current as usual.
    func withTranscription(_ text: String, source: Revision.Source, engine: String?, language: String?,
                           userRequested: Bool = false) -> Note {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty, !currentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return self }
        let userEdits = revisions.reversed().prefix { $0.source == .edit || $0.source == .restore }.count
        if userRequested || userEdits == 0 { return withRevision(trimmed, source: source, engine: engine, language: language) }
        var copy = self
        let machine = Revision(text: trimmed, source: source, engine: engine, language: language)
        copy.revisions.insert(machine, at: revisions.count - userEdits)
        return copy
    }

    /// Text the user wrote or restored is final: marks the note done, so no
    /// pending or retried transcription comes along later and replaces it.
    mutating func settle() {
        status = .done
        lastError = nil
        partialText = nil
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
        case id, createdAt, duration, audioFileName, revisions, status, attempts, lastError, partialText, pinned, pinnedAt, translation
        case backgroundVoices, archived, archivedAt
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
        translation = try? c.decodeIfPresent(NoteTranslation.self, forKey: .translation)
        backgroundVoices = try c.decodeIfPresent(Bool.self, forKey: .backgroundVoices) ?? false
        archived = try c.decodeIfPresent(Bool.self, forKey: .archived) ?? false
        archivedAt = try c.decodeIfPresent(Date.self, forKey: .archivedAt)
    }
}
