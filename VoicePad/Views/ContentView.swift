import SwiftUI

struct ContentView: View {
    @Environment(NoteStore.self) private var store
    @Environment(RecordingController.self) private var controller

    @State private var editingNoteID: Note.ID?
    @State private var historyNoteID: Note.ID?
    @State private var showSettings = false
    @State private var toast: String?

    private let bottomID = "bottom"

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 14) {
                        if store.notes.isEmpty && !controller.isRecording {
                            EmptyStateView()
                                .padding(.top, 80)
                        }
                        ForEach(store.notes.filter { $0.id != controller.recordingNoteID }) { note in
                            NoteBubble(
                                note: note,
                                isBusy: controller.busyNoteIDs.contains(note.id),
                                canRetranscribe: controller.engineStatus == .ready,
                                onCopy: { copy(note.currentText) },
                                onEdit: { editingNoteID = note.id },
                                onHistory: { historyNoteID = note.id },
                                onRetranscribe: { Task { await controller.retranscribe(note.id) } },
                                onDelete: { store.delete(note.id) }
                            )
                        }
                        if controller.isRecording || controller.recordingNoteID != nil {
                            LiveBubble()
                        }
                        Color.clear.frame(height: 1).id(bottomID)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: controller.liveText) { _, _ in
                    withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(bottomID, anchor: .bottom) }
                }
                .onChange(of: store.notes.count) { _, _ in
                    withAnimation { proxy.scrollTo(bottomID, anchor: .bottom) }
                }
                .onAppear { proxy.scrollTo(bottomID, anchor: .bottom) }
            }
            .safeAreaInset(edge: .bottom) {
                RecordBar()
            }
            .navigationTitle("VoicePad")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    EngineStatusBadge()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Settings")
                }
            }
            .sheet(item: $editingNoteID) { id in
                EditNoteView(noteID: id)
            }
            .sheet(item: $historyNoteID) { id in
                HistoryView(noteID: id, onCopy: copy)
            }
            .sheet(isPresented: $showSettings) {
                SettingsView()
            }
            .overlay(alignment: .top) {
                if let toast {
                    Text(toast)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(.thinMaterial, in: Capsule())
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .padding(.top, 4)
                }
            }
        }
    }

    private func copy(_ text: String) {
        UIPasteboard.general.string = text
        withAnimation { toast = "Copied" }
        Task {
            try? await Task.sleep(for: .seconds(1.2))
            withAnimation { toast = nil }
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
