import Foundation

/// Full-text search over notes: current text, text recovered from unfinished
/// transcriptions, and every earlier revision.
///
/// The query is split into terms; "quoted phrases" stay together. A note
/// matches when every term appears somewhere in it. Matching ignores case and
/// accents ("cafe" finds "Café").
enum NoteSearch {
    enum MatchedIn { case current, earlierRevision }

    struct Result: Identifiable {
        let note: Note
        let matchedIn: MatchedIn
        let score: Int
        var id: Note.ID { note.id }
    }

    static func terms(_ query: String) -> [String] {
        let regex = try! NSRegularExpression(pattern: "\"([^\"]+)\"|(\\S+)")
        let ns = query as NSString
        return regex.matches(in: query, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            let range = m.range(at: 1).location != NSNotFound ? m.range(at: 1) : m.range(at: 2)
            let term = ns.substring(with: range).trimmingCharacters(in: .whitespaces)
            return term.isEmpty ? nil : fold(term)
        }
    }

    static func search(_ notes: [Note], query: String) -> [Result] {
        let terms = terms(query)
        guard !terms.isEmpty else { return [] }
        return notes.compactMap { note -> Result? in
            let current = fold([note.currentText, note.partialText].compactMap { $0 }.joined(separator: "\n"))
            let everything = fold((note.revisions.map(\.text) + [note.partialText].compactMap { $0 }).joined(separator: "\n"))
            if terms.allSatisfy({ current.contains($0) }) {
                return Result(note: note, matchedIn: .current, score: score(current, terms))
            }
            if terms.allSatisfy({ everything.contains($0) }) {
                return Result(note: note, matchedIn: .earlierRevision, score: score(everything, terms) / 2)
            }
            return nil
        }
        .sorted { a, b in
            if (a.matchedIn == .current) != (b.matchedIn == .current) { return a.matchedIn == .current }
            if a.note.pinned != b.note.pinned { return a.note.pinned }
            if a.score != b.score { return a.score > b.score }
            return a.note.createdAt > b.note.createdAt
        }
    }

    /// Ranges in `text` matching any term, for highlighting. Folding is done
    /// one Character at a time so positions line up with the original text.
    static func matchRanges(in text: String, terms: [String]) -> [Range<String.Index>] {
        guard !terms.isEmpty, !text.isEmpty else { return [] }
        let chars = Array(text)
        let folded = chars.map(foldCharacter)
        // Index of each original Character, plus endIndex.
        var indices = Array(text.indices)
        indices.append(text.endIndex)

        var found: [(Int, Int)] = []
        for term in terms {
            let needle = Array(term).map(foldCharacter)
            guard !needle.isEmpty, needle.count <= folded.count else { continue }
            for start in 0...(folded.count - needle.count) where Array(folded[start..<(start + needle.count)]) == needle {
                found.append((start, start + needle.count))
            }
        }
        found.sort { $0.0 < $1.0 }
        var merged: [(Int, Int)] = []
        for r in found {
            if let last = merged.last, r.0 <= last.1 {
                merged[merged.count - 1].1 = max(last.1, r.1)
            } else {
                merged.append(r)
            }
        }
        return merged.map { indices[$0.0]..<indices[$0.1] }
    }

    /// Lowercase and accent-free, one output Character per input Character.
    static func fold(_ text: String) -> String {
        String(text.map(foldCharacter))
    }

    private static func foldCharacter(_ c: Character) -> Character {
        let folded = String(c).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).lowercased()
        return folded.count == 1 ? folded.first! : Character(String(c).lowercased().count == 1 ? String(c).lowercased() : String(c))
    }

    private static func score(_ haystack: String, _ terms: [String]) -> Int {
        terms.reduce(0) { total, term in
            total + haystack.components(separatedBy: term).count.advanced(by: -1) * term.count
        }
    }
}
