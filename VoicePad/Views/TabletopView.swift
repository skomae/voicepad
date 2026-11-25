import SwiftUI

/// The tabletop layout: what is being said above, the controls below, the way a
/// half-open foldable puts the transcript on the top half and the pad on the
/// bottom. Shown whenever the window is wide enough (the Duo unfolded, an iPad);
/// the notes list is one tap away.
struct TabletopView: View {
    @Environment(NoteStore.self) private var store
    @Environment(RecordingController.self) private var controller

    /// Opens the full notes list; the tabletop layout keeps only the transcript.
    var onShowNotes: () -> Void

    /// nil follows the newest note (the live transcript while recording).
    @State private var browsingID: Note.ID?

    private var finished: [Note] {
        store.notes
            .filter { !$0.archived && $0.id != controller.recordingNoteID && !$0.currentText.isEmpty }
            .sorted { $0.createdAt > $1.createdAt }
    }

    private var browsing: Note? { browsingID.flatMap { id in finished.first { $0.id == id } } }
    private var note: Note? { browsing ?? finished.first }
    private var index: Int { note.flatMap { n in finished.firstIndex { $0.id == n.id } } ?? -1 }
    private var live: Bool { controller.isRecording && browsing == nil }

    private var text: String { live ? controller.liveText : (note?.currentText ?? "") }

    private var translation: (text: String, language: String?)? {
        let source = live ? controller.liveTranslation : note?.translation
        guard let translation = source, translation.language != "en",
              let english = translation.english, !english.trimmed.isEmpty, english.trimmed != text.trimmed
        else { return nil }
        return (english, translation.language?.uppercased())
    }

    var body: some View {
        VStack(spacing: 0) {
            transcript
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            Divider()
            pad
        }
        .background(Color(.systemBackground))
    }

    private var transcript: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    if live {
                        PulsingDot()
                        Text("Recording").font(.subheadline.weight(.semibold)).foregroundStyle(Color.recordingRed)
                        if controller.partNumber > 1 {
                            Text("part \(controller.partNumber)").font(.caption).foregroundStyle(.secondary)
                        }
                    } else if let note {
                        Text(note.createdAt, format: .dateTime.hour().minute())
                            .font(.subheadline.weight(.semibold))
                        if finished.count > 1 {
                            Text("\(index + 1) of \(finished.count)").font(.caption).foregroundStyle(.secondary)
                        }
                    } else {
                        Text("VoicePad").font(.subheadline.weight(.semibold))
                    }
                    Spacer()
                }
                if text.trimmed.isEmpty {
                    Text(controller.isRecording ? "Listening…" : "Tap the microphone and speak.")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .padding(.top, 8)
                } else {
                    Text(text)
                        .font(.system(size: 28, weight: .regular, design: .default))
                        .textSelection(.enabled)
                }
                if let translation {
                    HStack(alignment: .top, spacing: 8) {
                        if let language = translation.language {
                            Text(language)
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 5))
                        }
                        Text(translation.text)
                            .font(.system(size: 22))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.top, 4)
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var pad: some View {
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                Button { onShowNotes() } label: {
                    Label("Notes", systemImage: "list.bullet").labelStyle(.titleAndIcon)
                }
                Spacer()
                Button { step(+1) } label: { Image(systemName: "chevron.up") }
                    .disabled(index <= 0)
                    .accessibilityLabel("Older note")
                Button { step(-1) } label: { Image(systemName: "chevron.down") }
                    .disabled(index < 0 || index >= finished.count - 1)
                    .accessibilityLabel("Newer note")
                if browsing != nil {
                    Button("Latest") { browsingID = nil }
                        .font(.footnote.weight(.semibold))
                }
            }
            .font(.footnote.weight(.semibold))
            .buttonStyle(.borderless)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
        }
        .background(Color(.secondarySystemBackground).opacity(0.5))
    }

    /// Steps through finished notes; `+1` is older (the list is newest first).
    private func step(_ delta: Int) {
        guard !finished.isEmpty else { return }
        let target = min(max(index + delta, 0), finished.count - 1)
        browsingID = finished[target].id
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
