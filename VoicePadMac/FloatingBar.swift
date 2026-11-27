import AppKit
import NaturalLanguage
import SwiftUI

/// The tiny always-on-top bar. It never becomes the active window, so
/// clicking pause leaves the app you're typing in at the front — that's where
/// the finished transcript gets pasted.
final class FloatingPanel: NSPanel {
    init<Content: View>(rootView: Content) {
        super.init(contentRect: NSRect(origin: .zero, size: FloatingBar.size),
                   styleMask: [.borderless, .nonactivatingPanel, .fullSizeContentView],
                   backing: .buffered, defer: false)
        isFloatingPanel = true
        level = .floating
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        hidesOnDeactivate = false
        isMovableByWindowBackground = true
        isReleasedWhenClosed = false
        backgroundColor = .clear
        isOpaque = false
        hasShadow = true
        animationBehavior = .utilityWindow
        contentView = FirstClickHostingView(rootView: rootView)
        if setFrameUsingName(Self.frameName) {
            // A saved frame from an older, narrower bar: keep its place, take the new size.
            setContentSize(FloatingBar.size)
        } else {
            placeDefault()
        }
        setFrameAutosaveName(Self.frameName)
    }

    private static let frameName = "VoicePadFloatingBar"

    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }

    /// Bottom centre of the main screen, above the Dock.
    private func placeDefault() {
        guard let screen = NSScreen.main?.visibleFrame else { return }
        setFrameOrigin(NSPoint(x: screen.midX - frame.width / 2, y: screen.minY + 24))
    }

    /// Brings it back on screen if a display it was on went away.
    func ensureOnScreen() {
        let visible = NSScreen.screens.contains { $0.visibleFrame.intersects(frame) }
        if !visible { placeDefault() }
    }
}

/// Buttons respond to the first click even though the panel is never key.
private final class FirstClickHostingView<Content: View>: NSHostingView<Content> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

struct FloatingBar: View {
    static let size = NSSize(width: 340, height: 64)

    @Environment(RecordingController.self) private var controller
    @Environment(MacSettings.self) private var settings
    let openMain: () -> Void
    let openSettings: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            recordButton
            words
                .frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .trailing, spacing: 5) {
                countLabel
                HStack(spacing: 4) {
                    SplitMenu()
                    pasteToggle
                    barButton("macwindow", help: "Open VoicePad", action: openMain)
                }
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, 10)
        .frame(width: Self.size.width, height: Self.size.height)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(controller.isRecording ? Color.red.opacity(0.22) : Color.primary.opacity(0.12))
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .background { TranslationHost(translator: controller.translator) }
        .contextMenu {
            Button(controller.isRecording ? "Pause" : "Resume") { Task { await controller.toggle() } }
            Button("Open VoicePad", action: openMain)
            Toggle("Auto-paste", isOn: Binding(get: { settings.autoPaste && controller.copyWhenDone },
                                               set: { settings.autoPaste = $0; if $0 { controller.copyWhenDone = true } }))
            Button("Settings…", action: openSettings)
            Divider()
            Button("Hide Floating Bar") { settings.showFloatingBar = false }
        }
        .preferredColorScheme(controller.themeMode.colorScheme)
    }

    /// Calm on purpose: VoicePad is nearly always listening, so nothing
    /// pulses or counts — a soft red pause button while recording, grey
    /// microphone while paused.
    private var recordButton: some View {
        Button {
            Task { await controller.toggle() }
        } label: {
            ZStack {
                Circle()
                    .fill(controller.isRecording ? Color.red.opacity(0.14) : Color.primary.opacity(0.07))
                Image(systemName: controller.isRecording ? "pause.fill" : "mic.fill")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(controller.isRecording ? AnyShapeStyle(Color.red) : AnyShapeStyle(.secondary))
            }
            .frame(width: 34, height: 34)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(controller.isRecording ? "Pause" : "Resume")
        .help(controller.isRecording
              ? "Pause (\(MacSettings.shortcutLabel)) — finishes this note\(settings.autoPaste ? " and pastes it" : "")"
              : "Resume recording (\(MacSettings.shortcutLabel))")
    }

    /// Two rows: the latest words while recording, otherwise what's going on.
    @ViewBuilder private var words: some View {
        Group {
            if let notice = controller.notice {
                Text(notice).foregroundStyle(.secondary)
            } else if case .loading(let progress) = controller.engineStatus {
                Text(progress.determinate ? "Downloading the speech model — \(Int(progress.fraction * 100))%" : "Getting the speech model ready…")
                    .foregroundStyle(.secondary)
            } else if case .failed = controller.engineStatus {
                Text("The speech model failed to load — open VoicePad").foregroundStyle(.orange)
            } else if controller.noSoundWarning {
                Text("No sound is reaching VoicePad — check the microphone").foregroundStyle(.orange)
            } else if controller.isRecording {
                if controller.liveText.isEmpty {
                    Text("Listening…").foregroundStyle(.tertiary)
                } else {
                    LatestWords(text: controller.liveText)
                }
            } else if controller.finishingCount > 0 {
                Text("Transcribing…").foregroundStyle(.secondary)
            } else {
                Text("Paused — click or press \(MacSettings.shortcutLabel) to resume").foregroundStyle(.secondary)
            }
        }
        .font(.system(size: 12))
        .lineLimit(2)
        .multilineTextAlignment(.leading)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// The note's word count while recording.
    @ViewBuilder private var countLabel: some View {
        if controller.isRecording {
            let count = WordCount.of(controller.liveText)
            Text("\(count) \(count == 1 ? "word" : "words")")
                .font(.system(size: 10, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .contentTransition(.numericText())
                .animation(.default, value: count)
        } else {
            Text(controller.finishingCount > 0 ? "…" : "Paused")
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(.tertiary)
        }
    }

    /// Auto-paste on/off, right on the bar: lit when finished text will be
    /// pasted into the app in front, dim when it's only copied.
    private var pasteToggle: some View {
        let on = settings.autoPaste && controller.copyWhenDone
        let needsPermission = on && !settings.accessibilityTrusted
        return Button {
            let enable = !on
            settings.autoPaste = enable
            if enable {
                controller.copyWhenDone = true
                if !settings.accessibilityTrusted { settings.requestAccessibility() }
            }
            controller.notice = enable ? "Auto-paste on — text goes where you're typing" : "Auto-paste off — text is only copied"
        } label: {
            Image(systemName: on ? "text.insert" : "doc.on.clipboard")
                .font(.system(size: 12, weight: on ? .semibold : .medium))
                .foregroundStyle(needsPermission ? AnyShapeStyle(.orange) : on ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                .frame(width: 24, height: 22)
                .background(on ? Color.accentColor.opacity(0.15) : .clear, in: RoundedRectangle(cornerRadius: 6))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Auto-paste")
        .accessibilityValue(on ? "On" : "Off")
        .help(needsPermission ? "Auto-paste is on, but needs Accessibility permission — click to allow"
              : on ? "Auto-paste is on: finished text is pasted into the app you're using. Click to only copy."
              : "Auto-paste is off: finished text is only copied. Click to paste it where you're typing.")
    }

    private func barButton(_ symbol: String, help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.secondary)
                .frame(width: 22, height: 22)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(help)
        .help(help)
    }
}

/// Words in a transcript. Uses the system's word breaker, so Chinese and
/// Japanese (written without spaces) are counted by word too.
enum WordCount {
    static func of(_ text: String) -> Int {
        guard !text.isEmpty else { return 0 }
        let tokenizer = NLTokenizer(unit: .word)
        tokenizer.string = text
        return tokenizer.tokens(for: text.startIndex..<text.endIndex).count
    }
}

/// Scissors: click to split now; the arrow picks when to split by itself
/// (after a pause, or every few minutes).
struct SplitMenu: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        @Bindable var controller = controller
        Menu {
            Button("Split Now") { controller.split() }
                .disabled(!controller.isRecording)
            Divider()
            Picker("Split Automatically", selection: $controller.autoSplit) {
                ForEach(AutoSplit.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            .pickerStyle(.inline)
        } label: {
            Image(systemName: "scissors")
                .font(.system(size: 12, weight: .medium))
        } primaryAction: {
            controller.split()
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .foregroundStyle(controller.autoSplit == .off ? AnyShapeStyle(.secondary) : AnyShapeStyle(.tint))
        .accessibilityLabel("Split")
        .help(controller.autoSplit == .off
              ? "Split — click to start a new note now; the arrow sets automatic splitting"
              : "Split — click to start a new note now. Splitting by itself: \(controller.autoSplit.label.lowercased())")
    }
}

/// The newest words of a transcript filling exactly two lines: earlier words
/// are dropped from the front (with "…") until the rest fits the width.
private struct LatestWords: View {
    let text: String
    private static let font = NSFont.systemFont(ofSize: 12)

    var body: some View {
        GeometryReader { geo in
            Text(Self.fit(text, width: geo.size.width))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
        .frame(height: ceil(Self.lineHeight * 2) + 1)
    }

    private static var lineHeight: CGFloat {
        let layout = NSLayoutManager()
        return layout.defaultLineHeight(for: font)
    }

    private static func height(_ string: String, width: CGFloat) -> CGFloat {
        NSAttributedString(string: string, attributes: [.font: font])
            .boundingRect(with: NSSize(width: width, height: .greatestFiniteMagnitude),
                          options: [.usesLineFragmentOrigin, .usesFontLeading]).height
    }

    static func fit(_ text: String, width: CGFloat) -> String {
        guard width > 20 else { return text }
        let limit = lineHeight * 2 + 1
        if height(text, width: width) <= limit { return text }
        // Words for Latin text; single characters for text without spaces (Chinese, Japanese).
        let pieces: [Substring] = text.contains(" ")
            ? text.split(separator: " ", omittingEmptySubsequences: false)
            : text.map { Substring(String($0)) }
        let joiner = text.contains(" ") ? " " : ""
        // Binary search for the most pieces from the end that still fit.
        var low = 1, high = pieces.count
        while low < high {
            let mid = (low + high + 1) / 2
            let candidate = "…" + pieces.suffix(mid).joined(separator: joiner)
            if height(candidate, width: width) <= limit { low = mid } else { high = mid - 1 }
        }
        return "…" + pieces.suffix(low).joined(separator: joiner)
    }
}
