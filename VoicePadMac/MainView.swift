import SwiftUI

/// The main window: onboarding the first time, then your notes as a
/// conversation with the live transcript at the bottom.
struct MainView: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        Group {
            if controller.onboardingDone {
                NotesView()
            } else {
                OnboardingView()
            }
        }
        .background { TranslationHost(translator: controller.translator) }
        .preferredColorScheme(controller.themeMode.colorScheme)
    }
}

struct NotesView: View {
    @Environment(RecordingController.self) private var controller
    @Environment(MacSettings.self) private var settings
    @State private var query = ""
    @State private var editing: Note?

    private var store: NoteStore { controller.store }

    private var visibleNotes: [Note] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        if !trimmed.isEmpty { return NoteSearch.search(store.notes, query: trimmed).map(\.note) }
        // The note being recorded is shown as the live bubble instead.
        return store.notes.filter { !$0.archived && $0.id != controller.recordingNoteID }
            .sorted { $0.createdAt < $1.createdAt }
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if visibleNotes.isEmpty && !controller.isRecording {
                            EmptyNotes()
                        }
                        ForEach(visibleNotes) { note in
                            NoteBubble(note: note, onEdit: { editing = note })
                                .id(note.id)
                        }
                        if controller.isRecording && query.isEmpty {
                            LiveBubble().id("live")
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(16)
                }
                .onAppear { proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: controller.liveText) { proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: store.notes.count) { proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: controller.isRecording) { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            Divider()
            RecordBar()
        }
        .overlay(alignment: .top) { Banner() }
        .searchable(text: $query, placement: .toolbar, prompt: "Search notes")
        .toolbar {
            ToolbarItem(placement: .navigation) { ModelStatus() }
        }
        .navigationTitle(controller.isRecording ? "Recording" : "VoicePad")
        .sheet(item: $editing) { note in EditNote(note: note) }
        .frame(minWidth: 420, minHeight: 420)
    }
}

// MARK: - Bubbles

private struct EmptyNotes: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "waveform").font(.system(size: 34)).foregroundStyle(.tertiary)
            Text("Nothing yet").font(.headline)
            Text("Press the red button or \(MacSettings.shortcutLabel) and start talking.")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }
}

struct NoteBubble: View {
    @Environment(RecordingController.self) private var controller
    let note: Note
    let onEdit: () -> Void
    @State private var hovering = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(note.createdAt, format: .dateTime.weekday(.abbreviated).hour().minute())
                if note.duration > 0 { Text("· \(Self.duration(note.duration))") }
                statusBadge
                Spacer()
                if hovering { actions }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(height: 18)

            body(for: note)

            if let translation = note.currentTranslation, translation.status == .done {
                if let romanization = translation.romanization, !romanization.isEmpty {
                    Text(romanization).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                }
                if let english = translation.english, !english.isEmpty {
                    Text(english).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.primary.opacity(0.07))
        }
        .onHover { hovering = $0 }
        .contextMenu { menu }
    }

    @ViewBuilder private func body(for note: Note) -> some View {
        let text = note.currentText.trimmingCharacters(in: .whitespacesAndNewlines)
        switch note.status {
        case .pending, .recording:
            placeholder(note.partialText ?? "Waiting for the speech model…", italic: note.partialText == nil)
        case .inProgress:
            placeholder(note.partialText ?? "Transcribing…", italic: note.partialText == nil)
        case .failed:
            VStack(alignment: .leading, spacing: 6) {
                if let partial = note.partialText { Text(partial).textSelection(.enabled) }
                HStack {
                    Text(note.lastError.map { "Transcription failed: \($0)" } ?? "Transcription failed")
                        .foregroundStyle(.orange).font(.callout)
                    Button("Retry") { controller.retranscribe(note.id) }.controlSize(.small)
                }
            }
        case .done:
            if text.isEmpty {
                placeholder("No speech detected", italic: true)
            } else {
                Text(text).font(.body).textSelection(.enabled)
            }
        }
    }

    private func placeholder(_ text: String, italic: Bool) -> some View {
        Text(text).italic(italic).foregroundStyle(.secondary).textSelection(.enabled)
    }

    @ViewBuilder private var statusBadge: some View {
        if note.status == .inProgress {
            ProgressView().controlSize(.mini)
        } else if note.status == .pending {
            Image(systemName: "clock")
        }
    }

    private var actions: some View {
        HStack(spacing: 10) {
            Button { copy() } label: { Image(systemName: "doc.on.doc") }.help("Copy")
            Button { onEdit() } label: { Image(systemName: "pencil") }.help("Edit")
            Menu { menu } label: { Image(systemName: "ellipsis") }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
        }
        .buttonStyle(.borderless)
    }

    @ViewBuilder private var menu: some View {
        Button("Copy") { copy() }
        if let english = note.currentTranslation?.english, !english.isEmpty {
            Button("Copy English") { Platform.copy(english) }
        }
        Button("Edit…") { onEdit() }
        if note.audioFileName != nil {
            Button("Transcribe Again") { controller.retranscribe(note.id) }
            Button("Show Recording in Finder") {
                if let url = controller.store.audioURL(for: note) { NSWorkspace.shared.activateFileViewerSelecting([url]) }
            }
        }
        Divider()
        if note.archived {
            Button("Unarchive") { controller.unarchive(note.id) }
        } else {
            Button("Archive") { controller.archive([note.id]) }
        }
        Button("Delete", role: .destructive) { controller.delete([note.id]) }
    }

    private func copy() {
        Platform.copy(note.currentText)
        controller.notice = "Copied"
    }

    static func duration(_ seconds: TimeInterval) -> String {
        let s = Int(seconds.rounded())
        return s >= 60 ? "\(s / 60)m \(s % 60)s" : "\(s)s"
    }
}

/// The note being spoken right now.
private struct LiveBubble: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Circle().fill(.red).frame(width: 7, height: 7)
                Text(controller.partNumber > 1 ? "Recording · part \(controller.partNumber)" : "Recording")
                if controller.liveRecordOnly {
                    Text("· text comes when the model is ready")
                }
            }
            .font(.caption)
            .foregroundStyle(.red)

            if controller.liveText.isEmpty {
                Text(controller.noSoundWarning ? "No sound is reaching VoicePad — check the microphone in System Settings → Sound."
                                               : "Listening…")
                    .italic().foregroundStyle(.secondary)
            } else {
                Text(controller.liveText).font(.body)
            }
            if let english = controller.liveTranslation?.english, !english.isEmpty {
                Text(english).font(.callout).foregroundStyle(.secondary)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.red.opacity(0.06), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.red.opacity(0.35))
        }
    }
}

// MARK: - Record bar

private struct RecordBar: View {
    @Environment(RecordingController.self) private var controller
    @Environment(MacSettings.self) private var settings

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                if controller.isRecording, let started = controller.startedAt {
                    HStack(spacing: 6) {
                        TimelineView(.periodic(from: started, by: 1)) { context in
                            Text(FloatingBar.elapsed(from: started, to: context.date)).monospacedDigit()
                        }
                        LevelBars(level: controller.level)
                    }
                    .font(.headline)
                    Text("Recording — \(MacSettings.shortcutLabel) to pause").font(.caption).foregroundStyle(.secondary)
                } else if controller.finishingCount > 0 {
                    Label("Transcribing…", systemImage: "text.bubble").font(.headline)
                    Text(" ").font(.caption)
                } else {
                    Text("Not recording").font(.headline)
                    Text("\(MacSettings.shortcutLabel) from any app").font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button {
                Task { await controller.toggle() }
            } label: {
                ZStack {
                    Circle().strokeBorder(Color.red.opacity(0.35), lineWidth: 3)
                    if controller.isRecording {
                        RoundedRectangle(cornerRadius: 5).fill(.red).frame(width: 20, height: 20)
                    } else {
                        Circle().fill(.red).padding(7)
                    }
                }
                .frame(width: 50, height: 50)
                .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .help(controller.isRecording ? "Pause (⌘R)" : "Record (⌘R)")

            HStack {
                Spacer()
                Button {
                    controller.split()
                } label: {
                    Label("Split", systemImage: "scissors")
                }
                .disabled(!controller.isRecording)
                .help("Finish this note and keep recording into a new one")
                Menu {
                    Picker("Auto-split", selection: Bindable(controller).autoSplit) {
                        ForEach(AutoSplit.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.inline)
                } label: {
                    Image(systemName: controller.autoSplit == .off ? "timer" : "timer.circle.fill")
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .help("Auto-split: \(controller.autoSplit.label)")
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.bar)
    }
}

// MARK: - Toolbar and banners

private struct ModelStatus: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        switch controller.engineStatus {
        case .loading(let progress):
            HStack(spacing: 6) {
                if progress.determinate {
                    ProgressView(value: progress.fraction).frame(width: 60)
                } else {
                    ProgressView().controlSize(.small)
                }
                Text(controller.activeEngine.shortName).font(.caption).foregroundStyle(.secondary)
            }
            .help(progress.stage)
        case .failed(let message):
            Button {
                controller.loadEngine()
            } label: {
                Label("Model failed — retry", systemImage: "exclamationmark.triangle")
            }
            .help(message)
        case .ready, .idle:
            Text(controller.activeEngine.shortName).font(.caption).foregroundStyle(.secondary)
                .help(controller.activeEngine.displayName)
        }
    }
}

/// Errors and short notices at the top of the window.
private struct Banner: View {
    @Environment(RecordingController.self) private var controller
    @Environment(MacSettings.self) private var settings

    var body: some View {
        VStack(spacing: 6) {
            if let error = controller.errorMessage {
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    Text(error).font(.callout)
                    if error.hasPrefix("Microphone access is off") {
                        Button("Open Settings") { settings.openMicrophoneSettings() }
                    }
                    Button { controller.errorMessage = nil } label: { Image(systemName: "xmark") }
                        .buttonStyle(.borderless)
                }
                .padding(10)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
            }
            if let notice = controller.notice {
                Text(notice)
                    .font(.callout)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(.regularMaterial, in: Capsule())
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .padding(.top, 8)
        .animation(.default, value: controller.notice)
    }
}

// MARK: - Edit

private struct EditNote: View {
    @Environment(RecordingController.self) private var controller
    @Environment(\.dismiss) private var dismiss
    let note: Note
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Edit note").font(.headline)
            TextEditor(text: $text)
                .font(.body)
                .frame(minWidth: 420, minHeight: 200)
                .scrollContentBackground(.hidden)
                .padding(6)
                .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
            if note.revisions.count > 1 {
                Text("\(note.revisions.count) versions saved — every edit is kept.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Save") {
                    controller.store.addRevision(to: note.id, text: text, source: .edit)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .onAppear { text = note.currentText }
    }
}
