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

/// Archive, compress or delete notes by age.
struct CleanupView: View {
    @Environment(RecordingController.self) private var controller
    @Environment(\.dismiss) private var dismiss
    @State private var action = NoteCleanup.Action.archive
    @State private var days = 7
    @State private var confirmDelete = false

    var body: some View {
        let candidates = controller.cleanupCandidates(action, days: days)
        let count = candidates.count
        let notes = count == 1 ? "1 note" : "\(count) notes"
        let deleting = action == .delete || action == .deleteEmpty
        NavigationStack {
            Form {
                Section {
                    Picker("Action", selection: $action) {
                        ForEach(NoteCleanup.Action.allCases) { Text($0.label).tag($0) }
                    }
                }
                Section("Notes older than") {
                    Picker("Age", selection: $days) {
                        Text("Any age").tag(0)
                        Text("1 day").tag(1)
                        Text("7 days").tag(7)
                        Text("30 days").tag(30)
                    }
                    .pickerStyle(.segmented)
                }
                Section {
                    Text(summary(count: count, notes: notes, candidates: candidates))
                        .foregroundStyle(deleting && count > 0 ? .red : .primary)
                    Button("\(action.label) \(count)", role: deleting ? .destructive : nil) {
                        if deleting {
                            confirmDelete = true
                        } else {
                            controller.cleanUp(action, days: days)
                            dismiss()
                        }
                    }
                    .disabled(count == 0)
                } footer: {
                    Text(action == .compress
                         ? "Compress keeps notes in the list, pinned ones included, just smaller."
                         : "Pinned notes, and notes being recorded or transcribed, are never touched.")
                }
            }
            .navigationTitle("Clean up notes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .confirmationDialog("Delete \(notes)?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete \(count)", role: .destructive) {
                    controller.cleanUp(action, days: days)
                    dismiss()
                }
            } message: {
                Text("This permanently deletes their audio and every revision. It can't be undone.")
            }
        }
    }

    private func summary(count: Int, notes: String, candidates: [Note]) -> String {
        guard count > 0 else { return "No notes match." }
        switch action {
        case .archive:
            return "\(notes) will be archived: hidden from the list, audio compressed to about a tenth of its size."
        case .compress:
            let sizes = controller.audioSizes(candidates)
            let fmt = ByteCountFormatter()
            return "\(notes): audio goes from \(fmt.string(fromByteCount: Int64(sizes.now))) to about \(fmt.string(fromByteCount: Int64(sizes.after)))."
        case .deleteEmpty:
            return "\(notes) with no speech, and their audio, will be permanently deleted."
        case .delete:
            return "\(notes) and their audio will be permanently deleted."
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

/// Export every note as a ZIP of open formats (JSON, Markdown, and the
/// recordings if wanted), then save it to Files, AirDrop it, etc.
struct ExportView: View {
    @Environment(RecordingController.self) private var controller
    @Environment(\.dismiss) private var dismiss
    @State private var includeAudio = false
    @State private var includeArchived = true
    @State private var working = false
    @State private var error: String?
    @State private var shareItem: ShareItem?

    var body: some View {
        let count = controller.exportCandidates(includeArchived: includeArchived).count
        let bytes = controller.exportAudioBytes(includeArchived: includeArchived)
        NavigationStack {
            Form {
                Section {
                    Toggle(isOn: $includeAudio) {
                        Text("Include recordings")
                        Text("WAV / M4A · \(ByteCountFormatter().string(fromByteCount: Int64(bytes)))")
                    }
                    Toggle("Include archived notes", isOn: $includeArchived)
                } footer: {
                    Text("Makes a ZIP with notes.json (every note with its transcript history, translations and dates) and notes.md (readable text). Both are open formats any app can read.")
                }
                Section {
                    Button {
                        Task { await export() }
                    } label: {
                        HStack {
                            Text(count == 1 ? "Export 1 note…" : "Export \(count) notes…")
                            if working { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(count == 0 || working)
                    if let error { Text(error).foregroundStyle(.red).font(.footnote) }
                }
            }
            .navigationTitle("Export all notes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
            }
            .sheet(item: $shareItem) { item in ShareSheet(items: item.items).presentationDetents([.medium, .large]) }
        }
    }

    private func export() async {
        working = true
        defer { working = false }
        do {
            let result = try await controller.exportNotes(includeAudio: includeAudio, includeArchived: includeArchived)
            error = nil
            shareItem = ShareItem(items: [result.url])
        } catch {
            self.error = "Export failed: \(error.localizedDescription)"
        }
    }
}
