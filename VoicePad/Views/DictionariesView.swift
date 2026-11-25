import SwiftUI

/// Settings: each dictionary with an on/off switch; tap one to edit its words.
struct DictionariesSection: View {
    @Environment(RecordingController.self) private var controller
    @State private var creating = false
    @State private var newName = ""

    private var store: DictionaryStore { controller.dictionaries }

    var body: some View {
        Section {
            ForEach(store.dictionaries) { dictionary in
                HStack {
                    NavigationLink {
                        DictionaryEditorView(dictionaryID: dictionary.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(dictionary.name)
                            Text(subtitle(dictionary))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    Toggle("", isOn: Binding(
                        get: { dictionary.enabled },
                        set: { store.setEnabled(dictionary.id, $0) }
                    ))
                    .labelsHidden()
                    .accessibilityLabel(dictionary.enabled ? "Turn \(dictionary.name) off" : "Turn \(dictionary.name) on")
                }
            }
            Button("New dictionary", systemImage: "plus") {
                newName = ""
                creating = true
            }
        } header: {
            Text("Dictionaries")
        } footer: {
            Text("Names, jargon and other words the models get wrong. Words in enabled dictionaries are "
                 + "spelled your way in every transcript (and so in translations). When you edit a note, "
                 + "VoicePad offers to save the new words.")
        }
        .alert("New dictionary", isPresented: $creating) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) {}
            Button("Create") { store.create(newName) }
        }
    }

    private func subtitle(_ dictionary: UserDictionary) -> String {
        let count = dictionary.words.count
        let words = dictionary.words.prefix(4).joined(separator: ", ")
        let countText = count == 1 ? "1 word" : "\(count) words"
        return words.isEmpty ? countText : "\(countText) · \(words)"
    }
}

/// One dictionary: add, edit and delete words, rename it, turn it on or off,
/// and move selected words to another dictionary.
struct DictionaryEditorView: View {
    let dictionaryID: String
    @Environment(RecordingController.self) private var controller
    @Environment(\.dismiss) private var dismiss

    @State private var newWord = ""
    @State private var selection = Set<String>()
    @State private var editingWord: String?
    @State private var editedText = ""
    @State private var renaming = false
    @State private var renamedText = ""
    @State private var confirmDelete = false
    @State private var moving = false
    @FocusState private var adding: Bool

    private var store: DictionaryStore { controller.dictionaries }
    private var dictionary: UserDictionary? { store.dictionaries.first { $0.id == dictionaryID } }
    private var others: [UserDictionary] { store.dictionaries.filter { $0.id != dictionaryID } }
    /// Words removed elsewhere (moved, deleted) drop out of the selection.
    private var selected: Set<String> { selection.intersection(Set(dictionary?.words ?? [])) }

    var body: some View {
        Group {
            if let dictionary {
                List(selection: $selection) {
                    Section {
                        Text(dictionary.enabled
                             ? "On: these words are used when transcribing."
                             : "Off: these words are kept but not used.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Section {
                        HStack {
                            TextField("Add a word or phrase", text: $newWord)
                                .focused($adding)
                                .submitLabel(.done)
                                .onSubmit(addTyped)
                            Button("Add", systemImage: "plus", action: addTyped)
                                .labelStyle(.iconOnly)
                                .disabled(DictionaryWords.clean(newWord) == nil)
                        }
                    } footer: {
                        Text("Commas or new lines separate several words; spaces stay inside a phrase.")
                    }

                    Section {
                        if dictionary.words.isEmpty {
                            Text("No words yet. Add them here, or save them when you edit a note.")
                                .foregroundStyle(.secondary)
                        }
                        ForEach(dictionary.words, id: \.self) { word in
                            Text(word)
                                .tag(word)
                                .swipeActions(edge: .trailing) {
                                    Button("Delete", systemImage: "trash", role: .destructive) {
                                        store.removeWords(dictionary.id, [word])
                                    }
                                }
                                .contextMenu {
                                    Button("Edit", systemImage: "pencil") {
                                        editedText = word
                                        editingWord = word
                                    }
                                    if !others.isEmpty {
                                        Menu("Move to", systemImage: "folder") {
                                            ForEach(others) { other in
                                                Button(other.name) {
                                                    store.moveWords([word], from: dictionary.id, to: other.id)
                                                }
                                            }
                                        }
                                    }
                                    Button("Delete", systemImage: "trash", role: .destructive) {
                                        store.removeWords(dictionary.id, [word])
                                    }
                                }
                        }
                        .onDelete { offsets in
                            store.removeWords(dictionary.id, offsets.map { dictionary.words[$0] })
                        }
                    } header: {
                        HStack {
                            Text(dictionary.words.isEmpty ? "Words" : dictionary.words.count == 1 ? "1 word" : "\(dictionary.words.count) words")
                            Spacer()
                            if dictionary.words.count > 1 {
                                Button(selected.count == dictionary.words.count ? "Uncheck all" : "Check all") {
                                    selection = selected.count == dictionary.words.count ? [] : Set(dictionary.words)
                                }
                                .font(.caption.weight(.semibold))
                                .buttonStyle(.borderless)
                            }
                        }
                    }

                    if !selected.isEmpty {
                        Section {
                            Button("Move \(selected.count == 1 ? "1 word" : "\(selected.count) words") to…", systemImage: "folder") {
                                moving = true
                            }
                            .disabled(others.isEmpty)
                            Button("Delete \(selected.count == 1 ? "1 word" : "\(selected.count) words")", systemImage: "trash", role: .destructive) {
                                store.removeWords(dictionary.id, Array(selected))
                                selection = []
                            }
                        }
                    }
                }
                .environment(\.editMode, .constant(.active))
                .navigationTitle(dictionary.name)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Toggle("Use this dictionary", isOn: Binding(
                            get: { dictionary.enabled },
                            set: { store.setEnabled(dictionary.id, $0) }
                        ))
                        .labelsHidden()
                        .accessibilityLabel("Use this dictionary")
                    }
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Button("Rename", systemImage: "pencil") {
                                renamedText = dictionary.name
                                renaming = true
                            }
                            Button(others.isEmpty ? "Clear dictionary" : "Delete dictionary",
                                   systemImage: "trash", role: .destructive) {
                                confirmDelete = true
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
                .confirmationDialog(
                    moving ? "Move words to" : "",
                    isPresented: $moving,
                    titleVisibility: .visible
                ) {
                    ForEach(others) { other in
                        Button(other.name) {
                            store.moveWords(Array(selected), from: dictionary.id, to: other.id)
                            selection = []
                        }
                    }
                }
                .alert("Rename dictionary", isPresented: $renaming) {
                    TextField("Name", text: $renamedText)
                    Button("Cancel", role: .cancel) {}
                    Button("Rename") { store.rename(dictionary.id, renamedText) }
                }
                .alert("Edit word", isPresented: Binding(get: { editingWord != nil }, set: { if !$0 { editingWord = nil } })) {
                    TextField("Word", text: $editedText)
                    Button("Cancel", role: .cancel) { editingWord = nil }
                    Button("Save") {
                        if let editingWord { store.replaceWord(dictionary.id, old: editingWord, new: editedText) }
                        editingWord = nil
                    }
                }
                .alert(others.isEmpty ? "Clear “\(dictionary.name)”?" : "Delete “\(dictionary.name)”?",
                       isPresented: $confirmDelete) {
                    Button("Cancel", role: .cancel) {}
                    Button(others.isEmpty ? "Clear" : "Delete", role: .destructive) {
                        if others.isEmpty {
                            store.removeWords(dictionary.id, dictionary.words)
                        } else {
                            store.delete(dictionary.id)
                            dismiss()
                        }
                    }
                } message: {
                    Text(others.isEmpty
                         ? "Its \(dictionary.words.count == 1 ? "1 word" : "\(dictionary.words.count) words") will be removed."
                         : "The dictionary and its words will be removed. To keep some, move them to another dictionary first.")
                }
            } else {
                ContentUnavailableView("Dictionary deleted", systemImage: "character.book.closed")
            }
        }
    }

    private func addTyped() {
        let words = newWord.split(whereSeparator: { $0 == "," || $0 == "\n" }).compactMap { DictionaryWords.clean(String($0)) }
        if !words.isEmpty { store.addWords(dictionaryID, words) }
        newWord = ""
    }
}

/// Asked after an edit is saved: which of the words the edit introduced should
/// go into a dictionary. Every word is its own checkbox (all ticked to start),
/// with Check all / Uncheck all, and a choice of dictionary when there's more
/// than one.
struct SaveWordsSheet: View {
    let words: [String]
    @Environment(RecordingController.self) private var controller
    @Environment(\.dismiss) private var dismiss

    @State private var unchecked = Set<String>()
    @State private var targetID: String?

    private var store: DictionaryStore { controller.dictionaries }
    private var chosen: [String] { words.filter { !unchecked.contains($0) } }
    private var target: UserDictionary? {
        store.dictionaries.first { $0.id == (targetID ?? store.lastUsedId) } ?? store.dictionaries.first
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(words, id: \.self) { word in
                        Button {
                            if unchecked.contains(word) { unchecked.remove(word) } else { unchecked.insert(word) }
                        } label: {
                            HStack {
                                Image(systemName: unchecked.contains(word) ? "square" : "checkmark.square.fill")
                                    .foregroundStyle(unchecked.contains(word) ? Color.secondary : Color.accentColor)
                                Text(word).foregroundStyle(.primary)
                            }
                        }
                    }
                } header: {
                    HStack {
                        Text("Add new words to a dictionary?")
                        Spacer()
                        Button(chosen.count == words.count ? "Uncheck all" : "Check all") {
                            unchecked = chosen.count == words.count ? Set(words) : []
                        }
                        .font(.caption.weight(.semibold))
                        .textCase(nil)
                    }
                } footer: {
                    Text("Dictionary words are spelled your way in future transcripts.")
                }

                Section("Save to") {
                    if store.dictionaries.count > 1 {
                        ForEach(store.dictionaries) { dictionary in
                            Button {
                                targetID = dictionary.id
                            } label: {
                                HStack {
                                    Text(dictionary.name + (dictionary.enabled ? "" : " (off)"))
                                        .foregroundStyle(.primary)
                                    Spacer()
                                    if dictionary.id == target?.id {
                                        Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                                    }
                                }
                            }
                        }
                    } else if let target {
                        Text("Saved to “\(target.name)”").foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("New words")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Not now") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(chosen.count == 1 ? "Add 1 word" : "Add \(chosen.count) words") {
                        if let target { store.addWords(target.id, chosen) }
                        dismiss()
                    }
                    .disabled(chosen.isEmpty)
                }
            }
        }
    }
}
