import SwiftUI

/// A saved note. Shows its transcript, or where its audio is in the
/// transcription pipeline, with copy / edit / history / pin / play / share
/// actions and a Retry button when a model run failed.
struct NoteBubble: View {
    let note: Note
    let engineStatus: RecordingController.EngineStatus
    let isPlaying: Bool
    /// Search terms to highlight (already folded by NoteSearch).
    var highlightTerms: [String] = []
    /// The search matched an earlier version, not the current text.
    var matchedInEarlierRevision = false
    let onCopy: () -> Void
    let onEdit: () -> Void
    let onHistory: () -> Void
    let onRetranscribe: () -> Void
    let onTogglePin: () -> Void
    let onPlay: () -> Void
    let onShare: () -> Void
    let onDelete: () -> Void
    var onTranslate: () -> Void = {}
    var onCopyText: (String) -> Void = { _ in }

    private var hasText: Bool { !note.currentText.isEmpty }
    private var working: Bool { note.status == .inProgress }
    /// Text in a failed or waiting note is only what was recovered so far.
    private var bodyText: String { hasText ? note.currentText : note.partialText ?? "" }
    private var solid: Bool { hasText || note.status == .done }
    private var textColor: Color { solid ? .white : .primary }
    private var engineReady: Bool { engineStatus == .ready }

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            bubble
            actionRow
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 40)
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !bodyText.isEmpty {
                TextWithTranslation(
                    text: bodyText,
                    translation: hasText ? note.currentTranslation : nil,
                    color: textColor,
                    highlight: highlighted,
                    onRetry: onTranslate
                )
            } else if note.status == .done {
                Text("No speech detected")
                    .italic()
                    .foregroundStyle(textColor.opacity(0.8))
            }
            if !hasText || working {
                StatusLine(note: note, engineStatus: engineStatus, color: textColor, onRetry: onRetranscribe)
            }
            if matchedInEarlierRevision {
                Button(action: onHistory) {
                    Text("Matched an earlier version · open history")
                        .font(.caption2)
                        .italic()
                        .foregroundStyle(textColor.opacity(0.8))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(solid ? Color.accentColor : Color(.secondarySystemBackground), in: BubbleShape())
        .overlay {
            if note.status == .failed {
                BubbleShape().stroke(Color.red, lineWidth: 1.5)
            }
        }
        .contentShape(BubbleShape())
        .onTapGesture(perform: onEdit)
        .contextMenu { menu }
    }

    @ViewBuilder
    private var menu: some View {
        Button("Copy", systemImage: "doc.on.doc", action: onCopy)
        if let translated = note.currentTranslation, translated.status == .done {
            Button("Copy Translation", systemImage: "translate") {
                onCopyText([translated.romanization, translated.english].compactMap { $0 }.joined(separator: "\n"))
            }
        } else if hasText {
            Button("Translate to English", systemImage: "translate", action: onTranslate)
        }
        Button(note.pinned ? "Unpin" : "Pin", systemImage: note.pinned ? "pin.slash" : "pin", action: onTogglePin)
        Button("Edit", systemImage: "pencil", action: onEdit)
        Button("Revision History", systemImage: "clock.arrow.circlepath", action: onHistory)
        Button(isPlaying ? "Stop Playback" : "Play Recording", systemImage: isPlaying ? "stop.fill" : "play.fill", action: onPlay)
        Button("Share Recording", systemImage: "square.and.arrow.up", action: onShare)
        Button(hasText ? "Re-transcribe" : "Retry Transcription", systemImage: "arrow.clockwise", action: onRetranscribe)
            .disabled(!engineReady || working)
        Divider()
        Button("Delete", systemImage: "trash", role: .destructive, action: onDelete)
    }

    private var actionRow: some View {
        HStack(spacing: 14) {
            Text(metadata)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Button(action: onTogglePin) {
                Image(systemName: note.pinned ? "pin.fill" : "pin")
                    .foregroundStyle(note.pinned ? Color.accentColor : .secondary)
            }
            .accessibilityLabel(note.pinned ? "Unpin" : "Pin")
            Button(action: onPlay) { Image(systemName: isPlaying ? "stop.fill" : "play.fill") }
                .accessibilityLabel(isPlaying ? "Stop" : "Play")
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
        .foregroundStyle(.secondary)
        .buttonStyle(.borderless)
    }

    /// `text` with every search-term match emphasised.
    private func highlighted(_ text: String) -> AttributedString {
        var attributed = AttributedString(text)
        for range in NoteSearch.matchRanges(in: text, terms: highlightTerms) {
            guard let lower = AttributedString.Index(range.lowerBound, within: attributed),
                  let upper = AttributedString.Index(range.upperBound, within: attributed) else { continue }
            attributed[lower..<upper].backgroundColor = Color.yellow.opacity(0.6)
            attributed[lower..<upper].foregroundColor = .black
            attributed[lower..<upper].font = .body.weight(.semibold)
        }
        return attributed
    }

    private var metadata: String {
        var parts = [note.createdAt.formatted(date: .abbreviated, time: .shortened)]
        if note.duration > 0 { parts.append(Duration.seconds(note.duration).formatted(.time(pattern: .minuteSecond))) }
        if let last = note.revisions.last, last.source != .transcription { parts.append(last.source.label.lowercased()) }
        return parts.joined(separator: " · ")
    }
}

/// "Waiting for model", "Transcribing…" or "Failed · Retry" under a bubble's text.
private struct StatusLine: View {
    let note: Note
    let engineStatus: RecordingController.EngineStatus
    let color: Color
    let onRetry: () -> Void

    var body: some View {
        switch note.status {
        case .inProgress:
            HStack(spacing: 6) {
                ProgressView().controlSize(.mini).tint(color)
                Text("Transcribing…").italic()
            }
            .font(.caption)
            .foregroundStyle(color)
        case .pending, .recording:
            Label("Audio saved · waiting (\(waitReason))", systemImage: "clock")
                .font(.caption)
                .foregroundStyle(color)
        case .failed:
            VStack(alignment: .leading, spacing: 2) {
                Text("Transcription failed · audio saved")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.red)
                if let error = note.lastError {
                    Text(error).font(.caption2).foregroundStyle(color).lineLimit(2)
                }
                Button(action: onRetry) {
                    Label(engineStatus == .ready ? "Retry" : "Retry when model is ready", systemImage: "arrow.clockwise")
                        .font(.caption.weight(.semibold))
                }
                .buttonStyle(.borderless)
                .disabled(engineStatus != .ready)
                .padding(.top, 2)
            }
        case .done:
            EmptyView()
        }
    }

    private var waitReason: String {
        switch engineStatus {
        case .loading(let p): return p > 0 ? "model downloading \(Int(p * 100))%" : "model loading"
        case .failed: return "model failed to load"
        default: return "queued"
        }
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
        case .loading(let p): return p > 0 ? "Recording… downloading model \(Int(p * 100))%" : "Recording… loading model"
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

/// The transcript and, for speech that wasn't English, its romanisation
/// (pinyin for Mandarin) and English translation. Side by side when there's
/// room (landscape, iPad), stacked on a phone held upright.
private struct TextWithTranslation: View {
    let text: String
    let translation: NoteTranslation?
    let color: Color
    let highlight: (String) -> AttributedString
    let onRetry: () -> Void

    var body: some View {
        if let translation, translation.status != .notNeeded {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 12) {
                    original(translation).frame(minWidth: 220, maxWidth: .infinity, alignment: .leading)
                    Rectangle().fill(color.opacity(0.3)).frame(width: 1)
                    english(translation).frame(minWidth: 220, maxWidth: .infinity, alignment: .leading)
                }
                VStack(alignment: .leading, spacing: 8) {
                    original(translation)
                    Rectangle().fill(color.opacity(0.3)).frame(height: 1)
                    english(translation)
                }
            }
        } else {
            Text(highlight(text)).foregroundStyle(color)
        }
    }

    private func original(_ translation: NoteTranslation) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if let language = translation.language {
                LanguageLabel(name: LanguageTags.displayName(language), color: color)
            }
            Text(highlight(text)).foregroundStyle(color)
            if let romanization = translation.romanization {
                Text(highlight(romanization))
                    .font(.callout)
                    .italic()
                    .foregroundStyle(color.opacity(0.85))
                    .padding(.top, 2)
            }
        }
    }

    @ViewBuilder
    private func english(_ translation: NoteTranslation) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            LanguageLabel(name: "English", color: color)
            switch translation.status {
            case .done:
                Text(highlight(translation.english ?? "")).foregroundStyle(color)
            case .pending:
                HStack(spacing: 6) {
                    ProgressView().controlSize(.mini).tint(color)
                    Text("Translating…").italic().font(.callout)
                }
                .foregroundStyle(color)
            case .failed:
                Text(translation.error ?? "Translation failed.")
                    .font(.caption)
                    .foregroundStyle(color.opacity(0.85))
                    .lineLimit(3)
                Button(action: onRetry) {
                    Label("Retry translation", systemImage: "arrow.clockwise").font(.caption.weight(.semibold))
                }
                .buttonStyle(.borderless)
                .tint(color)
            case .notNeeded:
                EmptyView()
            }
        }
    }
}

private struct LanguageLabel: View {
    let name: String
    let color: Color

    var body: some View {
        Label(name.uppercased(), systemImage: "translate")
            .font(.caption2.weight(.semibold))
            .foregroundStyle(color.opacity(0.7))
            .padding(.bottom, 2)
    }
}
