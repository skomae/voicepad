import SwiftUI

/// Notes moved out of the main list. Their audio is kept compressed; they can
/// still be played, shared, unarchived, deleted and re-transcribed.
struct ArchivedNotesView: View {
    let actions: BubbleActions
    let player: AudioPlayer

    @Environment(NoteStore.self) private var store
    @Environment(RecordingController.self) private var controller
    @Environment(\.dismiss) private var dismiss
    @State private var editingNoteID: Note.ID?
    @State private var historyNoteID: Note.ID?

    private var notes: [Note] {
        store.notes.filter(\.archived).sorted { ($0.archivedAt ?? $0.createdAt) > ($1.archivedAt ?? $1.createdAt) }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 14) {
                    Text("Audio is kept compressed. Long-press a note to unarchive or delete it.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if notes.isEmpty {
                        ContentUnavailableView("Nothing archived yet", systemImage: "archivebox")
                            .padding(.top, 40)
                    }
                    ForEach(notes) { note in
                        NoteBubble(
                            note: note,
                            engineStatus: controller.engineStatus,
                            isPlaying: player.playingID == note.id,
                            onCopy: { actions.copy(note) },
                            onEdit: { editingNoteID = note.id },
                            onHistory: { historyNoteID = note.id },
                            onRetranscribe: { actions.retranscribe(note) },
                            onTogglePin: { actions.togglePin(note) },
                            onPlay: { actions.play(note) },
                            onShare: { actions.share(note) },
                            onDelete: { actions.delete(note) },
                            onTranslate: { actions.translate(note) },
                            onCopyText: actions.copyText,
                            onArchive: { actions.archive(note) }
                        )
                    }
                }
                .padding(16)
            }
            .navigationTitle("Archived notes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .sheet(item: $editingNoteID) { id in EditNoteView(noteID: id) }
            .sheet(item: $historyNoteID) { id in HistoryView(noteID: id, onCopy: actions.copyText) }
        }
    }
}

/// Archive or delete every unpinned note older than 1, 7 or 30 days.
struct CleanupView: View {
    @Environment(RecordingController.self) private var controller
    @Environment(\.dismiss) private var dismiss
    @State private var action = NoteCleanup.Action.archive
    @State private var days = 7
    @State private var confirmDelete = false

    var body: some View {
        let count = controller.cleanupCandidates(action, days: days).count
        let notes = count == 1 ? "1 note" : "\(count) notes"
        NavigationStack {
            Form {
                Section("Unpinned notes older than") {
                    Picker("Age", selection: $days) {
                        Text("1 day").tag(1)
                        Text("7 days").tag(7)
                        Text("30 days").tag(30)
                    }
                    .pickerStyle(.segmented)
                }
                Section {
                    Picker("Action", selection: $action) {
                        Label("Archive", systemImage: "archivebox").tag(NoteCleanup.Action.archive)
                        Label("Delete", systemImage: "trash").tag(NoteCleanup.Action.delete)
                    }
                    .pickerStyle(.segmented)
                } footer: {
                    Text("Pinned notes, and notes being recorded or transcribed, are never touched.")
                }
                Section {
                    Text(count == 0 ? "No notes match."
                         : action == .archive
                         ? "\(notes) will be archived: hidden from the list, audio compressed to about a tenth of its size."
                         : "\(notes) and their audio will be permanently deleted.")
                        .foregroundStyle(action == .delete && count > 0 ? .red : .primary)
                    Button(action == .archive ? "Archive \(count)" : "Delete \(count)", role: action == .delete ? .destructive : nil) {
                        if action == .delete {
                            confirmDelete = true
                        } else {
                            controller.cleanUp(.archive, days: days)
                            dismiss()
                        }
                    }
                    .disabled(count == 0)
                }
            }
            .navigationTitle("Clean up notes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .confirmationDialog("Delete \(notes)?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete \(count)", role: .destructive) {
                    controller.cleanUp(.delete, days: days)
                    dismiss()
                }
            } message: {
                Text("This permanently deletes their audio and every revision. It can't be undone.")
            }
        }
    }
}

/// Top-left: the model in use. Tap to switch between models already on the
/// phone (both Autos, Whisper…) or toggle background voices.
struct ModelSwitcher: View {
    let color: Color
    let onMoreModels: () -> Void
    @Environment(RecordingController.self) private var controller

    var body: some View {
        Menu {
            Section("Downloaded models") {
                let installed = EngineChoice.allCases.filter { controller.installedEngines.contains($0) }
                if installed.isEmpty {
                    Text("None yet — the first one is downloading.")
                }
                ForEach(installed) { choice in
                    Button {
                        controller.selectModel(choice)
                    } label: {
                        if choice == controller.activeEngine {
                            Label(choice.displayName, systemImage: "checkmark")
                        } else {
                            Text(choice.displayName)
                        }
                        if choice == RecordingController.backgroundEngine { Text("Background voices") }
                    }
                }
            }
            Section {
                Toggle(isOn: Binding(get: { controller.backgroundVoices }, set: { controller.setBackgroundVoices($0) })) {
                    Label("Background voices", systemImage: "ear")
                    Text("Announcements, TV across the room" +
                         (controller.installedEngines.contains(RecordingController.backgroundEngine) ? "" : " · ~630 MB download"))
                }
                Button("More models…", systemImage: "gearshape", action: onMoreModels)
            }
        } label: {
            HStack(spacing: 2) {
                EngineStatusBadge(color: color)
                Image(systemName: "chevron.down").font(.caption2.weight(.semibold)).foregroundStyle(color)
            }
        }
        .accessibilityLabel("Model: \(controller.activeEngine.shortName). Tap to switch.")
    }
}
