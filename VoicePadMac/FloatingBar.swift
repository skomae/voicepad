import AppKit
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
    static let size = NSSize(width: 326, height: 52)

    @Environment(RecordingController.self) private var controller
    @Environment(MacSettings.self) private var settings
    let openMain: () -> Void
    let openSettings: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            recordButton
            VStack(alignment: .leading, spacing: 1) {
                statusLine
                detailLine
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if controller.isRecording {
                barButton("scissors", help: "Split — finish this note and keep recording into a new one") { controller.split() }
            }
            pasteToggle
            barButton("macwindow", help: "Open VoicePad", action: openMain)
        }
        .padding(.leading, 9)
        .padding(.trailing, 12)
        .frame(width: Self.size.width, height: Self.size.height)
        .background(.regularMaterial, in: Capsule())
        .overlay {
            Capsule().strokeBorder(controller.isRecording ? Color.red.opacity(0.55) : Color.primary.opacity(0.12),
                                   lineWidth: controller.isRecording ? 1.5 : 1)
        }
        .clipShape(Capsule())
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

    private var recordButton: some View {
        Button {
            Task { await controller.toggle() }
        } label: {
            ZStack {
                Circle()
                    .fill(controller.isRecording ? Color.red : Color.red.opacity(0.12))
                // A ring that breathes with the voice.
                Circle()
                    .strokeBorder(Color.red.opacity(0.35), lineWidth: 3)
                    .scaleEffect(1 + CGFloat(controller.level) * 0.25)
                    .opacity(controller.isRecording ? 1 : 0)
                Image(systemName: controller.isRecording ? "pause.fill" : "mic.fill")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(controller.isRecording ? .white : .red)
            }
            .frame(width: 34, height: 34)
            .contentShape(Circle())
            .animation(.easeOut(duration: 0.08), value: controller.level)
        }
        .buttonStyle(.plain)
        .help(controller.isRecording
              ? "Pause (\(MacSettings.shortcutLabel)) — finishes this note\(settings.autoPaste ? " and pastes it" : "")"
              : "Resume recording (\(MacSettings.shortcutLabel))")
    }

    @ViewBuilder private var statusLine: some View {
        HStack(spacing: 6) {
            if controller.isRecording, let started = controller.startedAt {
                Circle().fill(.red).frame(width: 6, height: 6)
                TimelineView(.periodic(from: started, by: 1)) { context in
                    Text(Self.elapsed(from: started, to: context.date))
                        .monospacedDigit()
                }
                LevelBars(level: controller.level)
            } else if controller.finishingCount > 0 {
                ProgressView().controlSize(.mini)
                Text("Transcribing…")
            } else {
                Text("Paused")
            }
        }
        .font(.system(size: 12, weight: .semibold))
        .lineLimit(1)
    }

    @ViewBuilder private var detailLine: some View {
        Group {
            if let notice = controller.notice {
                Text(notice).foregroundStyle(.secondary)
            } else if case .loading(let progress) = controller.engineStatus {
                Text(progress.determinate ? "Downloading model \(Int(progress.fraction * 100))%" : "Getting the model ready…")
                    .foregroundStyle(.secondary)
            } else if case .failed = controller.engineStatus {
                Text("Model failed to load — open VoicePad").foregroundStyle(.orange)
            } else if controller.noSoundWarning {
                Text("No sound from the microphone").foregroundStyle(.orange)
            } else if controller.isRecording {
                Text(controller.liveText.isEmpty ? "Listening…" : controller.liveText)
                    .truncationMode(.head)
                    .foregroundStyle(.secondary)
            } else {
                Text("\(MacSettings.shortcutLabel) or click to resume").foregroundStyle(.tertiary)
            }
        }
        .font(.system(size: 11))
        .lineLimit(1)
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

    static func elapsed(from start: Date, to now: Date) -> String {
        let seconds = max(0, Int(now.timeIntervalSince(start)))
        return seconds >= 3600
            ? String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            : String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

/// Five little bars that follow the input level.
struct LevelBars: View {
    let level: Float

    var body: some View {
        HStack(alignment: .center, spacing: 2) {
            ForEach(0..<5, id: \.self) { i in
                let weights: [Float] = [0.55, 0.85, 1, 0.75, 0.5]
                Capsule()
                    .fill(Color.red.opacity(0.8))
                    .frame(width: 2.5, height: 3 + CGFloat(min(1, level * weights[i] * 1.3)) * 9)
            }
        }
        .frame(height: 12)
        .animation(.easeOut(duration: 0.08), value: level)
    }
}
