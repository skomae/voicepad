import SwiftUI

/// A finished note: transcript bubble with copy / edit / history actions.
struct NoteBubble: View {
    let note: Note
    let isBusy: Bool
    let canRetranscribe: Bool
    let onCopy: () -> Void
    let onEdit: () -> Void
    let onHistory: () -> Void
    let onRetranscribe: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            Group {
                if note.currentText.isEmpty {
                    Text(isBusy ? "Transcribing…" : "No speech detected")
                        .italic()
                        .foregroundStyle(.white.opacity(0.8))
                } else {
                    Text(note.currentText)
                        .foregroundStyle(.white)
                        .textSelection(.enabled)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color.accentColor, in: BubbleShape())
            .overlay(alignment: .topLeading) {
                if isBusy {
                    ProgressView().tint(.white).scaleEffect(0.7).padding(4)
                }
            }
            .contextMenu {
                Button("Copy", systemImage: "doc.on.doc", action: onCopy)
                Button("Edit", systemImage: "pencil", action: onEdit)
                Button("Revision History", systemImage: "clock.arrow.circlepath", action: onHistory)
                Button("Re-transcribe", systemImage: "arrow.clockwise", action: onRetranscribe)
                    .disabled(!canRetranscribe || isBusy)
                Divider()
                Button("Delete", systemImage: "trash", role: .destructive, action: onDelete)
            }
            .onTapGesture(perform: onEdit)

            HStack(spacing: 14) {
                Text(metadata)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Button(action: onCopy) { Image(systemName: "doc.on.doc") }
                    .accessibilityLabel("Copy")
                Button(action: onEdit) { Image(systemName: "pencil") }
                    .accessibilityLabel("Edit")
                Button(action: onHistory) {
                    HStack(spacing: 2) {
                        Image(systemName: "clock.arrow.circlepath")
                        if note.revisions.count > 1 {
                            Text("\(note.revisions.count)").font(.caption2.monospacedDigit())
                        }
                    }
                }
                .accessibilityLabel("Revision history")
            }
            .font(.caption)
            .buttonStyle(.borderless)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 40)
    }

    private var metadata: String {
        var parts = [note.createdAt.formatted(date: .abbreviated, time: .shortened)]
        if note.duration > 0 { parts.append(Duration.seconds(note.duration).formatted(.time(pattern: .minuteSecond))) }
        if let last = note.revisions.last, last.source != .transcription { parts.append(last.source.label.lowercased()) }
        return parts.joined(separator: " · ")
    }
}

/// The bubble for the recording in progress; text streams in live.
struct LiveBubble: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            Text(displayText)
                .italic(controller.liveText.isEmpty)
                .foregroundStyle(controller.liveText.isEmpty ? Color.primary.opacity(0.55) : .primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color(.secondarySystemBackground), in: BubbleShape())
                .overlay(BubbleShape().stroke(Color.accentColor.opacity(0.5), style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])))
                .animation(.default, value: controller.liveText)
            HStack(spacing: 6) {
                if controller.isRecording {
                    Circle().fill(.red).frame(width: 7, height: 7)
                    Text("Live").font(.caption2).foregroundStyle(.secondary)
                } else {
                    ProgressView().scaleEffect(0.6)
                    Text("Finishing transcript…").font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 40)
    }

    private var displayText: String {
        if !controller.liveText.isEmpty { return controller.liveText }
        switch controller.engineStatus {
        case .ready: return "Listening…"
        case .loading(let p): return "Recording… model loading \(Int(p * 100))%"
        case .failed: return "Recording… (model unavailable)"
        case .idle: return "Recording…"
        }
    }
}

struct BubbleShape: Shape {
    func path(in rect: CGRect) -> Path {
        Path(roundedRect: rect, cornerRadii: RectangleCornerRadii(
            topLeading: 18, bottomLeading: 18, bottomTrailing: 4, topTrailing: 18
        ))
    }
}
