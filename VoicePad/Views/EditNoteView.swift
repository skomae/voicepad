import SwiftUI

struct EditNoteView: View {
    let noteID: Note.ID
    /// Called with the text before the edit, so the caller can offer the words
    /// the edit introduced for a dictionary.
    var onSaved: ((_ previousText: String) -> Void)?
    @Environment(NoteStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .focused($focused)
                .padding(.horizontal, 12)
                .navigationTitle("Edit")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            let previous = store.note(id: noteID)?.currentText ?? ""
                            store.addRevision(to: noteID, text: text, source: .edit)
                            dismiss()
                            onSaved?(previous)
                        }
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines) == store.note(id: noteID)?.currentText)
                    }
                    ToolbarItem(placement: .bottomBar) {
                        Text("Saving keeps the previous version in history.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
        }
        .onAppear {
            text = store.note(id: noteID)?.currentText ?? ""
            focused = true
        }
    }
}
