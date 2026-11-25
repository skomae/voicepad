import SwiftUI

extension Color {
    static let recordingRed = Color(red: 0.83, green: 0.18, blue: 0.18)

    /// Dark grey in light mode, light grey in dark: the "Tap to Start" hint has
    /// to stay readable on both, including the pure-black theme.
    static let idleCaption = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(white: 0.72, alpha: 1) : UIColor(white: 0.28, alpha: 1)
    })
}

/// A dot that pulses, for "recording now".
struct PulsingDot: View {
    var color: Color = .recordingRed
    var size: CGFloat = 10
    @State private var dim = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .opacity(dim ? 0.25 : 1)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) { dim = true }
            }
    }
}

struct RecordBar: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        let recording = controller.isRecording
        VStack(spacing: 8) {
            if controller.isRecording, controller.noSoundWarning {
                Label("No sound is coming in from the microphone, so this part isn't being captured. If the iPhone is connected to a Mac, unplug it.", systemImage: "mic.slash.fill")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(Color.recordingRed, in: Capsule())
                    .padding(.horizontal, 4)
                    .accessibilityAddTraits(.updatesFrequently)
            }
            if let error = controller.errorMessage {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }
            if controller.microphoneDenied {
                Button("Allow the microphone in Settings", systemImage: "gear") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                .font(.footnote.weight(.semibold))
            }

            // One floating glass dock, like Voice Memos: the controls sit on a
            // single surface over the notes rather than a full-width strip.
            VStack(spacing: 2) {
                HStack(spacing: 18) {
                    VStack(spacing: 4) {
                        // No "--:--" when stopped: the dashes read as broken
                        // glyphs in a monospaced title face. The space is kept so
                        // the dock never resizes.
                        ElapsedTime(start: recording ? controller.startedAt : nil)
                            .font(.title3.monospacedDigit())
                            .foregroundStyle(Color.recordingRed)
                            .opacity(recording ? 1 : 0)
                        LevelMeter(level: recording ? controller.level : 0)
                            .frame(height: 28)
                    }
                    .frame(width: 72)

                    Button {
                        Task { await controller.toggle() }
                    } label: {
                        ZStack {
                            if recording {
                                Circle()
                                    .fill(Color.recordingRed.opacity(0.18))
                                    .frame(width: 80, height: 80)
                                    .scaleEffect(1 + CGFloat(controller.level) * 0.25)
                                    .animation(.easeOut(duration: 0.08), value: controller.level)
                                Circle().fill(Color.recordingRed).frame(width: 64, height: 64)
                                RoundedRectangle(cornerRadius: 6, style: .continuous)
                                    .fill(.white)
                                    .frame(width: 26, height: 26)
                            } else {
                                // Idle looks clearly different: a ring with a red dot, like a camera.
                                Circle().strokeBorder(Color.idleCaption.opacity(0.55), lineWidth: 3)
                                    .frame(width: 64, height: 64)
                                Circle().fill(Color.recordingRed).frame(width: 34, height: 34)
                            }
                        }
                        .frame(width: 80, height: 80)
                        .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(recording ? "Stop recording" : "Start recording")

                    // Both side stacks are centred on the row, but the timer sits above
                    // the bars while the split's label sits under the icon, so the
                    // scissors ride above the meter. Nudged down until the scissors sit
                    // in line with the meter (Android #f0bc488); the label stays clear
                    // of the caption below.
                    SplitButton()
                        .offset(y: 12)
                }
                Text(recording ? "Recording..." : "Tap to Start")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(recording ? Color.recordingRed : Color.idleCaption)
            }
            .padding(.top, 6)
            .padding(.bottom, 4)
            .padding(.horizontal, 6)
            .glassDock(tint: recording ? Color.recordingRed.opacity(0.07) : nil)
        }
        .padding(.horizontal, 14)
        .padding(.bottom, 6)
    }
}

private extension View {
    /// The dock's surface. Liquid Glass on iOS 26+, the old bar material before
    /// it, in the same shape so the layout doesn't move.
    @ViewBuilder func glassDock(tint: Color? = nil) -> some View {
        if #available(iOS 26.0, *) {
            let glass: Glass = tint.map { .regular.tint($0) } ?? .regular
            self.glassEffect(glass, in: RoundedRectangle(cornerRadius: 34, style: .continuous))
        } else {
            self.background(.bar, in: RoundedRectangle(cornerRadius: 34, style: .continuous))
        }
    }
}

/// mm:ss since `start`, ticking every second; "--:--" when not recording.
struct ElapsedTime: View {
    let start: Date?

    var body: some View {
        if let start {
            TimelineView(.periodic(from: start, by: 1)) { context in
                Text(Duration.seconds(max(0, context.date.timeIntervalSince(start))).formatted(.time(pattern: .minuteSecond)))
            }
        } else {
            Text("--:--")
        }
    }
}

private struct LevelMeter: View {
    let level: Float

    var body: some View {
        HStack(alignment: .center, spacing: 3) {
            ForEach(0..<7, id: \.self) { i in
                let threshold = Float(i) / 7
                Capsule()
                    .fill(level > threshold ? Color.red : Color.secondary.opacity(0.3))
                    .frame(width: 4, height: 8 + CGFloat(min(i, 6 - i)) * 6)
            }
        }
        .animation(.easeOut(duration: 0.08), value: level)
    }
}

struct EngineStatusBadge: View {
    @Environment(RecordingController.self) private var controller
    /// Text colour; white on the red bar while recording.
    var color: Color = .secondary

    var body: some View {
        HStack(spacing: 5) {
            switch controller.engineStatus {
            case .idle:
                Image(systemName: "circle.dashed")
            case .loading(let p):
                ProgressView().scaleEffect(0.6).tint(color)
                Text(p.determinate ? "\(Int(p.fraction * 100))%" : "Preparing")
            case .ready:
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            case .failed:
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
            if controller.backgroundVoices { Image(systemName: "ear.fill") }
            Text(controller.activeEngine.shortName)
        }
        .font(.caption)
        .foregroundStyle(color)
        .lineLimit(1)
        .fixedSize()
        .padding(.horizontal, 6)
    }
}

/// The scissors: tap to split now (like a stopwatch lap — ends this note and
/// starts the next without a gap); press and hold, then slide up, to pick an
/// auto-split rule. A badge shows the active rule.
struct SplitButton: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        @Bindable var controller = controller
        let recording = controller.isRecording
        Menu {
            Picker("Auto-split", selection: $controller.autoSplit) {
                // Furthest from the button first, like Android's drag-up list.
                ForEach(AutoSplit.allCases.reversed()) { Text($0.label).tag($0) }
            }
            .pickerStyle(.inline)
        } label: {
            VStack(spacing: 2) {
                Image(systemName: "scissors")
                    .font(.title3)
                HStack(spacing: 3) {
                    Text(controller.autoSplit == .off ? "Split" : "Auto")
                        .font(.caption.weight(.medium))
                    if let badge = controller.autoSplit.badge {
                        Text(badge)
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Color.accentColor, in: Capsule())
                    }
                }
            }
            .frame(width: 72, height: 56)
            .contentShape(Rectangle())
        } primaryAction: {
            if recording { controller.split() }
        }
        .menuOrder(.fixed)
        .foregroundStyle(recording ? Color.primary : Color.secondary.opacity(0.6))
        .sensoryFeedback(.impact, trigger: controller.partNumber) { old, new in new > old }
        .accessibilityLabel("Split")
        .accessibilityHint("Starts a new note now. Touch and hold to set auto-split, currently \(controller.autoSplit.label).")
    }
}

/// Progress of the speech model: what's happening and how far along, with a
/// determinate bar while downloading and a moving one while preparing.
struct ModelProgressView: View {
    let progress: LoadProgress
    var compact = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if progress.determinate {
                ProgressView(value: min(1, max(0, progress.fraction))) {
                    Text(progress.stage).font(compact ? .caption : .subheadline)
                } currentValueLabel: {
                    Text("\(Int(progress.fraction * 100))%").monospacedDigit()
                }
            } else {
                Text(progress.stage).font(compact ? .caption : .subheadline)
                IndeterminateBar()
            }
        }
    }
}

/// A thin bar with a segment sweeping across: "working, can't say how long".
struct IndeterminateBar: View {
    var body: some View {
        TimelineView(.animation) { context in
            GeometryReader { geo in
                let t = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.6) / 1.6
                let width = geo.size.width * 0.3
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.secondary.opacity(0.2))
                    Capsule().fill(Color.accentColor).frame(width: width).offset(x: (geo.size.width + width) * t - width)
                }
                .clipShape(Capsule())
            }
        }
        .frame(height: 4)
        .accessibilityLabel("Working")
    }
}

/// Shown at the top of the notes while the speech model is downloading or
/// getting ready, so a long first load never looks stuck.
struct ModelLoadingBanner: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        if case .loading(let progress) = controller.engineStatus {
            VStack(alignment: .leading, spacing: 8) {
                Label("Getting \(controller.activeEngine.shortName) ready", systemImage: "arrow.down.circle")
                    .font(.subheadline.weight(.semibold))
                ModelProgressView(progress: progress)
                Text("Recording works meanwhile; the text appears once the model is ready.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(14)
            .background(Color.accentColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
        }
    }
}
