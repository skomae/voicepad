import SwiftUI

struct ContentView: View {
    @Environment(NoteStore.self) private var store
    @Environment(RecordingController.self) private var controller
    @Environment(ConversationController.self) private var conversation

    @State private var editingNoteID: Note.ID?
    @State private var editedNote: (id: Note.ID, before: String)?
    @State private var wordsToSave: PendingWords?
    @State private var historyNoteID: Note.ID?
    @State private var confirmDeleteID: Note.ID?
    @State private var shareItem: ShareItem?
    @State private var showSettings = false
    @State private var showArchive = false
    @State private var showCleanup = false
    @State private var showExport = false
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
            // Unmistakable: a red frame around the notes while the mic is on.
            .overlay {
                if controller.isRecording {
                    Rectangle().strokeBorder(Color.recordingRed.opacity(0.6), lineWidth: 3).allowsHitTesting(false)
                }
            }
            .safeAreaInset(edge: .bottom) { RecordBar() }
            .navigationTitle("VoicePad")
            .navigationBarTitleDisplayMode(.inline)
            // The whole bar turns red while recording.
            .toolbarBackground(Color.recordingRed, for: .navigationBar)
            .toolbarBackground(controller.isRecording ? .visible : .automatic, for: .navigationBar)
            .toolbarColorScheme(controller.isRecording ? .dark : nil, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    ModelSwitcher(color: controller.isRecording ? .white : .secondary) { showSettings = true }
                }
                ToolbarItem(placement: .principal) {
                    if controller.isRecording { RecordingTitle() } else { IdleTitle() }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { conversation.open() } label: {
                        Image(systemName: "bubble.left.and.text.bubble.right")
                    }
                    .tint(controller.isRecording ? .white : nil)
                    .accessibilityLabel("Conversation mode")
                    Menu {
                        Toggle(isOn: Binding(get: { controller.backgroundVoices }, set: { controller.setBackgroundVoices($0) })) {
                            Label("Background voices", systemImage: "ear")
                        }
                        Button("Settings", systemImage: "gearshape") { showSettings = true }
                        Button("Archived notes (\(store.notes.filter(\.archived).count))", systemImage: "archivebox") { showArchive = true }
                        Button("Clean up…", systemImage: "sparkles") { showCleanup = true }
                        Button("Export all notes…", systemImage: "square.and.arrow.up.on.square") { showExport = true }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .tint(controller.isRecording ? .white : nil)
                    .accessibilityLabel("More")
                }
            }
            .sheet(item: $editingNoteID, onDismiss: offerNewWords) { id in
                EditNoteView(noteID: id) { previous in editedNote = (id, previous) }
            }
            .sheet(item: $wordsToSave) { pending in SaveWordsSheet(words: pending.words) }
            .sheet(item: $historyNoteID) { id in HistoryView(noteID: id, onCopy: copy) }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .sheet(isPresented: $showArchive) { ArchivedNotesView(actions: bubbleActions, player: player) }
            .sheet(isPresented: $showCleanup) { CleanupView().presentationDetents([.medium, .large]) }
            .sheet(isPresented: $showExport) { ExportView().presentationDetents([.medium, .large]) }
            .fullScreenCover(isPresented: Binding(get: { !controller.onboardingDone }, set: { _ in })) {
                OnboardingView()
            }
            .fullScreenCover(isPresented: Binding(get: { conversation.visible }, set: { if !$0 { conversation.close() } })) {
                ConversationView()
            }
            .sheet(item: $shareItem) { item in ShareSheet(items: item.items).presentationDetents([.medium, .large]) }
            .confirmationDialog(
                "Delete recording?",
                isPresented: Binding(get: { confirmDeleteID != nil }, set: { if !$0 { confirmDeleteID = nil } }),
                titleVisibility: .visible,
                presenting: confirmDeleteID
            ) { id in
                Button("Delete", role: .destructive) {
                    if player.playingID == id { player.stop() }
                    controller.delete([id])
                }
            } message: { _ in
                Text("This permanently deletes the audio and every revision of its transcript.")
            }
            .overlay(alignment: .top) { ToastView(text: toast) }
            .onChange(of: controller.notice) { _, message in
                guard let message else { return }
                show(message)
                controller.notice = nil
            }
        }
        .background { TranslationHost(translator: controller.translator) }
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
            delete: { confirmDeleteID = $0.id },
            archive: { note in note.archived ? controller.unarchive(note.id) : controller.archive([note.id]) },
            translate: { controller.translateNow($0.id) },
            copyText: { copy($0) }
        )
    }

    private func copy(_ text: String) {
        UIPasteboard.general.string = text
        show("Copied")
    }

    /// After an edit: offer the words it introduced for a dictionary.
    private func offerNewWords() {
        guard let editedNote, let note = store.note(id: editedNote.id) else { return }
        self.editedNote = nil
        let words = DictionaryWords.newWords(before: editedNote.before, after: note.currentText,
                                             known: controller.dictionaries.allWords())
        if !words.isEmpty { wordsToSave = PendingWords(words: words) }
    }

    private func show(_ message: String) {
        withAnimation { toast = message }
        Task {
            try? await Task.sleep(for: .seconds(1.4))
            withAnimation { if toast == message { toast = nil } }
        }
    }
}

/// Words an edit introduced, waiting to be offered for a dictionary.
struct PendingWords: Identifiable {
    let id = UUID()
    let words: [String]
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
    let archive: (Note) -> Void
    let translate: (Note) -> Void
    let copyText: (String) -> Void
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
    /// The main list leaves out archived notes; search still finds them.
    private var visibleNotes: [Note] { store.notes.filter { $0.id != controller.recordingNoteID && !$0.archived } }
    private var searchableNotes: [Note] { store.notes.filter { $0.id != controller.recordingNoteID } }
    private var showLive: Bool { controller.isRecording }

    var body: some View {
        let visible = visibleNotes
        let pinned = visible.filter(\.pinned).sorted { ($0.pinnedAt ?? .distantPast) > ($1.pinnedAt ?? .distantPast) }
        let others = visible.filter { !$0.pinned }
        let needingText = visible.filter(\.needsTranscription).count

        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 14) {
                    if searching {
                        searchResults(searchableNotes)
                    } else {
                        if visible.isEmpty && !showLive {
                            EmptyStateView().padding(.top, 80)
                        }
                        ModelLoadingBanner()
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
            onDelete: { a.delete(note) },
            onTranslate: { a.translate(note) },
            onCopyText: a.copyText,
            onArchive: { a.archive(note) }
        )
    }
}

/// Top bar title while recording: pulsing dot, REC, elapsed time, part number.
private struct RecordingTitle: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        HStack(spacing: 8) {
            PulsingDot(color: .white, size: 11)
            HStack(spacing: 6) {
                Text("REC")
                ElapsedTime(start: controller.startedAt).monospacedDigit()
            }
            .font(.headline.weight(.bold))
            if controller.partNumber > 1 {
                Text("· part \(controller.partNumber)").font(.subheadline)
            }
        }
        .foregroundStyle(.white)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Recording" + (controller.partNumber > 1 ? ", part \(controller.partNumber)" : ""))
    }
}

/// Top bar title when the mic is off.
private struct IdleTitle: View {
    var body: some View {
        VStack(spacing: 0) {
            Text("VoicePad").font(.headline)
            HStack(spacing: 3) {
                Image(systemName: "mic.slash")
                Text("Not recording")
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
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
