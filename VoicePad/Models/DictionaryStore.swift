import Foundation
import Observation

/// The user's dictionaries, saved as JSON next to the notes. There is always
/// at least one ("My words"), so saving words from an edit has somewhere to go.
@MainActor
@Observable
final class DictionaryStore {
    struct Saved: Codable, Equatable {
        var dictionaries: [UserDictionary]
        var lastUsedId: String?
    }

    private(set) var dictionaries: [UserDictionary]

    /// Called after every saved change (used to rebuild the corrector).
    @ObservationIgnored var onChange: (() -> Void)?

    private let fileURL: URL
    private var backupURL: URL { fileURL.appendingPathExtension("bak") }
    private var saved: Saved

    init(baseURL: URL = NoteStore.defaultBaseURL) {
        let url = baseURL.appendingPathComponent("dictionaries.json")
        fileURL = url
        let loaded = DictionaryStore.load(from: url)
        saved = loaded
        dictionaries = loaded.dictionaries
    }

    /// The dictionary words were last saved into, offered first next time.
    var lastUsedId: String? {
        saved.lastUsedId.flatMap { id in saved.dictionaries.contains { $0.id == id } ? id : nil }
    }

    /// Every word in the enabled dictionaries.
    func activeWords() -> [String] {
        var seen = Set<String>()
        return saved.dictionaries.filter(\.enabled).flatMap(\.words).filter { seen.insert($0).inserted }
    }

    /// Every word in any dictionary, enabled or not.
    func allWords() -> Set<String> {
        Set(saved.dictionaries.flatMap(\.words))
    }

    @discardableResult
    func create(_ name: String) -> UserDictionary {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let dictionary = UserDictionary(name: trimmed.isEmpty ? "New dictionary" : trimmed)
        mutate { $0.dictionaries.append(dictionary) }
        return dictionary
    }

    func rename(_ id: String, _ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        edit(id) { $0.name = trimmed }
    }

    func setEnabled(_ id: String, _ enabled: Bool) {
        edit(id) { $0.enabled = enabled }
    }

    /// Removes the dictionary; the last one left is emptied instead, so there's always one.
    func delete(_ id: String) {
        mutate { state in
            let rest = state.dictionaries.filter { $0.id != id }
            state.dictionaries = rest.isEmpty ? [Self.defaultDictionary()] : rest
        }
    }

    /// Adds `words` to dictionary `id` (skipping ones it already has) and remembers it for next time.
    func addWords(_ id: String, _ words: [String]) {
        mutate { state in
            if let index = state.dictionaries.firstIndex(where: { $0.id == id }) {
                state.dictionaries[index] = state.dictionaries[index].withWords(words)
            }
            state.lastUsedId = id
        }
    }

    func removeWords(_ id: String, _ words: [String]) {
        let removing = Set(words)
        edit(id) { $0.words.removeAll { removing.contains($0) } }
    }

    /// Replaces one entry with its edited spelling, keeping its place in the list.
    func replaceWord(_ id: String, old: String, new: String) {
        edit(id) { dictionary in
            guard let cleaned = DictionaryWords.clean(new) else {
                dictionary.words.removeAll { $0 == old }
                return
            }
            if cleaned != old && dictionary.words.contains(cleaned) {
                dictionary.words.removeAll { $0 == old }
            } else if let index = dictionary.words.firstIndex(of: old) {
                dictionary.words[index] = cleaned
            }
        }
    }

    /// Moves `words` from dictionary `from` to dictionary `to`.
    func moveWords(_ words: [String], from: String, to: String) {
        guard from != to else { return }
        let moving = Set(words)
        mutate { state in
            for index in state.dictionaries.indices {
                switch state.dictionaries[index].id {
                case from: state.dictionaries[index].words.removeAll { moving.contains($0) }
                case to: state.dictionaries[index] = state.dictionaries[index].withWords(words)
                default: break
                }
            }
        }
    }

    // MARK: Persistence

    private func edit(_ id: String, _ transform: (inout UserDictionary) -> Void) {
        mutate { state in
            guard let index = state.dictionaries.firstIndex(where: { $0.id == id }) else { return }
            transform(&state.dictionaries[index])
        }
    }

    private func mutate(_ transform: (inout Saved) -> Void) {
        var updated = saved
        transform(&updated)
        guard updated != saved else { return }
        save(updated)
        saved = updated
        dictionaries = updated.dictionaries
        onChange?()
    }

    private static func load(from url: URL) -> Saved {
        for file in [url, url.appendingPathExtension("bak")] {
            guard let data = try? Data(contentsOf: file),
                  let loaded = try? JSONDecoder().decode(Saved.self, from: data),
                  !loaded.dictionaries.isEmpty else { continue }
            return loaded
        }
        return Saved(dictionaries: [defaultDictionary()], lastUsedId: nil)
    }

    private func save(_ state: Saved) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        guard let data = try? encoder.encode(state) else { return }
        let fm = FileManager.default
        try? fm.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let tmp = fileURL.appendingPathExtension("tmp")
        do {
            fm.createFile(atPath: tmp.path, contents: nil)
            let handle = try FileHandle(forWritingTo: tmp)
            try handle.write(contentsOf: data)
            try handle.synchronize()
            try handle.close()
            if fm.fileExists(atPath: fileURL.path) {
                try? fm.removeItem(at: backupURL)
                try? fm.copyItem(at: fileURL, to: backupURL)
            }
            _ = try fm.replaceItemAt(fileURL, withItemAt: tmp)
        } catch {
            // Last resort: a plain atomic write.
            try? data.write(to: fileURL, options: .atomic)
        }
    }

    private static func defaultDictionary() -> UserDictionary {
        UserDictionary(name: "My words")
    }
}

private extension UserDictionary {
    func withWords(_ new: [String]) -> UserDictionary {
        var seen = Set(words)
        var copy = self
        for word in new.compactMap(DictionaryWords.clean) where seen.insert(word).inserted {
            copy.words.append(word)
        }
        return copy
    }
}
