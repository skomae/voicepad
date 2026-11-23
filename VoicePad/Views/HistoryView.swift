import SwiftUI

struct HistoryView: View {
    let noteID: Note.ID
    let onCopy: (String) -> Void
    @Environment(NoteStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let note = store.note(id: noteID), !note.revisions.isEmpty {
                    List {
                        ForEach(Array(note.revisions.enumerated().reversed()), id: \.element.id) { index, revision in
                            RevisionRow(
                                number: index + 1,
                                revision: revision,
                                isCurrent: index == note.revisions.count - 1,
                                onCopy: { onCopy(revision.text) },
                                onRestore: { store.restore(noteID: noteID, revision: revision) }
                            )
                        }
                    }
                } else {
                    ContentUnavailableView("No revisions yet", systemImage: "clock")
                }
            }
            .navigationTitle("Revision History")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

private struct RevisionRow: View {
    let number: Int
    let revision: Revision
    let isCurrent: Bool
    let onCopy: () -> Void
    let onRestore: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label("v\(number) · \(revision.source.label)", systemImage: revision.source.systemImage)
                    .font(.subheadline.weight(.semibold))
                if isCurrent {
                    Text("Current")
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.15), in: Capsule())
                }
                Spacer()
            }
            Text(revision.date.formatted(date: .abbreviated, time: .standard)
                 + (revision.engine.map { " · \($0)" } ?? ""))
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(revision.text.isEmpty ? "(empty)" : revision.text)
                .font(.body)
                .textSelection(.enabled)
                .padding(.top, 2)
        }
        .padding(.vertical, 4)
        .swipeActions(edge: .trailing) {
            Button("Copy", systemImage: "doc.on.doc", action: onCopy).tint(.blue)
            if !isCurrent {
                Button("Restore", systemImage: "arrow.uturn.backward", action: onRestore).tint(.orange)
            }
        }
        .contextMenu {
            Button("Copy", systemImage: "doc.on.doc", action: onCopy)
            if !isCurrent {
                Button("Restore this version", systemImage: "arrow.uturn.backward", action: onRestore)
            }
        }
    }
}
