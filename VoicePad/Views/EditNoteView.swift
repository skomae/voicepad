import SwiftUI

struct EditNoteView: View {
    let noteID: Note.ID
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
                            store.addRevision(to: noteID, text: text, source: .edit)
                            dismiss()
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
