import SwiftUI

struct ContentView: View {
    @Environment(NoteStore.self) private var store
    @Environment(RecordingController.self) private var controller

    @State private var editingNoteID: Note.ID?
    @State private var historyNoteID: Note.ID?
    @State private var confirmDeleteID: Note.ID?
    @State private var shareItem: ShareItem?
    @State private var showSettings = false
    @State private var toast: String?
    @State private var query = ""
    @State private var player = AudioPlayer()

    var body: some View {
        NavigationStack {
            NotesList(
                query: query,
                player: player,
                bubbleActions: bubbleActions
            )
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search all notes and revisions")
            .safeAreaInset(edge: .bottom) { RecordBar() }
            .navigationTitle("VoicePad")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { EngineStatusBadge() }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Settings")
                }
            }
            .sheet(item: $editingNoteID) { id in EditNoteView(noteID: id) }
            .sheet(item: $historyNoteID) { id in HistoryView(noteID: id, onCopy: copy) }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .sheet(item: $shareItem) { item in ShareSheet(items: item.items).presentationDetents([.medium, .large]) }
            .confirmationDialog(
                "Delete recording?",
                isPresented: Binding(get: { confirmDeleteID != nil }, set: { if !$0 { confirmDeleteID = nil } }),
                titleVisibility: .visible,
                presenting: confirmDeleteID
            ) { id in
                Button("Delete", role: .destructive) {
                    if player.playingID == id { player.stop() }
                    store.delete(id)
                }
            } message: { _ in
                Text("This permanently deletes the audio and every revision of its transcript.")
            }
            .overlay(alignment: .top) { ToastView(text: toast) }
        }
    }

    private var bubbleActions: BubbleActions {
        BubbleActions(
            copy: { note in copy(note.currentText.isEmpty ? note.partialText ?? "" : note.currentText) },
            edit: { editingNoteID = $0.id },
            history: { historyNoteID = $0.id },
            retranscribe: { controller.retranscribe($0.id) },
            togglePin: { store.setPinned($0.id, !$0.pinned) },
            play: { note in
                if let error = player.toggle(note.id, url: store.audioURL(for: note)) { show(error) }
            },
            share: { note in
                guard let url = store.audioURL(for: note), FileManager.default.fileExists(atPath: url.path) else {
                    show("Audio file is missing.")
                    return
                }
                var items: [Any] = [url]
                if !note.currentText.isEmpty { items.append(note.currentText) }
                shareItem = ShareItem(items: items)
            },
            delete: { confirmDeleteID = $0.id }
        )
    }

    private func copy(_ text: String) {
        UIPasteboard.general.string = text
        show("Copied")
    }

    private func show(_ message: String) {
        withAnimation { toast = message }
        Task {
            try? await Task.sleep(for: .seconds(1.4))
            withAnimation { if toast == message { toast = nil } }
        }
    }
}

struct BubbleActions {
    let copy: (Note) -> Void
    let edit: (Note) -> Void
    let history: (Note) -> Void
    let retranscribe: (Note) -> Void
    let togglePin: (Note) -> Void
    let play: (Note) -> Void
    let share: (Note) -> Void
    let delete: (Note) -> Void
}

/// The scrolling list: search results, or the Pinned and All notes sections
/// with the live bubble at the bottom.
private struct NotesList: View {
    let query: String
    let player: AudioPlayer
    let bubbleActions: BubbleActions

    @Environment(NoteStore.self) private var store
    @Environment(RecordingController.self) private var controller
    @Environment(\.isSearching) private var isSearching

    private let bottomID = "bottom"

    private var searching: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty }
    private var visibleNotes: [Note] { store.notes.filter { $0.id != controller.recordingNoteID } }
    private var showLive: Bool { controller.isRecording || controller.recordingNoteID != nil }

    var body: some View {
        let visible = visibleNotes
        let pinned = visible.filter(\.pinned).sorted { ($0.pinnedAt ?? .distantPast) > ($1.pinnedAt ?? .distantPast) }
        let others = visible.filter { !$0.pinned }
        let needingText = visible.filter(\.needsTranscription).count

        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 14) {
                    if searching {
                        searchResults(visible)
                    } else {
                        if visible.isEmpty && !showLive {
                            EmptyStateView().padding(.top, 80)
                        }
                        if needingText > 0 {
                            RetryBanner(count: needingText, ready: controller.isEngineReady, onRetryAll: controller.retryAll)
                        }
                        if !pinned.isEmpty {
                            SectionHeader(title: "Pinned", systemImage: "pin.fill")
                            ForEach(pinned) { bubble($0) }
                            if !others.isEmpty || showLive { SectionHeader(title: "All notes") }
                        }
                        ForEach(others) { bubble($0) }
                        if showLive { LiveBubble() }
                    }
                    Color.clear.frame(height: 1).id(bottomID)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            // Follow new notes and live text to the bottom, except while searching.
            .onChange(of: controller.liveText) { _, _ in
                guard !searching else { return }
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(bottomID, anchor: .bottom) }
            }
            .onChange(of: store.notes.count) { _, _ in
                guard !searching else { return }
                withAnimation { proxy.scrollTo(bottomID, anchor: .bottom) }
            }
            .onAppear { proxy.scrollTo(bottomID, anchor: .bottom) }
        }
    }

    @ViewBuilder
    private func searchResults(_ notes: [Note]) -> some View {
        let results = NoteSearch.search(notes, query: query)
        let terms = NoteSearch.terms(query)
        if results.isEmpty {
            Text("No notes match “\(query.trimmingCharacters(in: .whitespaces))”.")
                .foregroundStyle(.secondary)
                .padding(.top, 80)
        } else {
            SectionHeader(title: results.count == 1 ? "1 result" : "\(results.count) results")
            ForEach(results) { result in
                bubble(result.note, highlight: terms, matchedEarlier: result.matchedIn == .earlierRevision)
            }
        }
    }

    private func bubble(_ note: Note, highlight: [String] = [], matchedEarlier: Bool = false) -> some View {
        let a = bubbleActions
        return NoteBubble(
            note: note,
            engineStatus: controller.engineStatus,
            isPlaying: player.playingID == note.id,
            highlightTerms: highlight,
            matchedInEarlierRevision: matchedEarlier,
            onCopy: { a.copy(note) },
            onEdit: { a.edit(note) },
            onHistory: { a.history(note) },
            onRetranscribe: { a.retranscribe(note) },
            onTogglePin: { a.togglePin(note) },
            onPlay: { a.play(note) },
            onShare: { a.share(note) },
            onDelete: { a.delete(note) }
        )
    }
}

private struct SectionHeader: View {
    let title: String
    var systemImage: String?

    var body: some View {
        HStack(spacing: 6) {
            if let systemImage { Image(systemName: systemImage).font(.caption) }
            Text(title).font(.subheadline.weight(.semibold))
            Spacer()
        }
        .foregroundStyle(Color.accentColor)
    }
}

/// Shown above the list while any recording still needs text.
private struct RetryBanner: View {
    let count: Int
    let ready: Bool
    let onRetryAll: () -> Void

    var body: some View {
        HStack {
            Text(count == 1 ? "1 recording is saved and waiting for text." : "\(count) recordings are saved and waiting for text.")
                .font(.subheadline)
            Spacer()
            Button("Retry all", action: onRetryAll)
                .font(.subheadline.weight(.semibold))
                .disabled(!ready)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct ToastView: View {
    let text: String?

    var body: some View {
        if let text {
            Text(text)
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(.thinMaterial, in: Capsule())
                .transition(.move(edge: .top).combined(with: .opacity))
                .padding(.top, 4)
        }
    }
}

extension UUID: @retroactive Identifiable {
    public var id: UUID { self }
}

private struct EmptyStateView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "mic.circle")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text("Opening VoicePad starts recording.\nYour words appear here as you speak.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
        }
    }
}
