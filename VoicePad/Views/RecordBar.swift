import SwiftUI

extension Color {
    static let recordingRed = Color(red: 0.83, green: 0.18, blue: 0.18)
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
    @Environment(\.pureBlack) private var pureBlack

    var body: some View {
        let recording = controller.isRecording
        VStack(spacing: 6) {
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
            HStack(spacing: 20) {
                VStack(spacing: 4) {
                    ElapsedTime(start: recording ? controller.startedAt : nil)
                        .font(.title3.monospacedDigit())
                        .foregroundStyle(recording ? Color.recordingRed : .secondary)
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
                                .fill(Color.recordingRed.opacity(0.22))
                                .frame(width: 80, height: 80)
                                .scaleEffect(1 + CGFloat(controller.level) * 0.3)
                                .animation(.easeOut(duration: 0.08), value: controller.level)
                            Circle().fill(Color.recordingRed).frame(width: 64, height: 64)
                            RoundedRectangle(cornerRadius: 5).fill(.white).frame(width: 24, height: 24)
                        } else {
                            // Idle looks clearly different: a ring with a red dot, like a camera.
                            Circle().strokeBorder(Color.secondary, lineWidth: 4).frame(width: 64, height: 64)
                            Circle().fill(Color.recordingRed).frame(width: 44, height: 44)
                        }
                    }
                    .frame(width: 80, height: 80)
                }
                .accessibilityLabel(recording ? "Stop recording" : "Start recording")

                SplitButton()
            }
            Text(recording
                 ? "Recording" + (controller.partNumber > 1 ? " · part \(controller.partNumber)" : "")
                    + (controller.backgroundVoices ? " · background voices" : "") + " — tap ■ to stop"
                 : "Not recording — tap ● to start")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(recording ? Color.recordingRed : .secondary)
        }
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .background { if pureBlack { Color.black } else { Rectangle().fill(.bar) } }
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
                Text(p > 0 ? "\(Int(p * 100))%" : "Loading")
            case .ready:
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            case .failed:
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
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
                    .overlay(alignment: .topTrailing) {
                        if let badge = controller.autoSplit.badge {
                            Text(badge)
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 4)
                                .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 5))
                                .offset(x: 18, y: -8)
                                .fixedSize()
                        }
                    }
                Text(controller.autoSplit == .off ? "Split" : "Auto").font(.caption.weight(.medium))
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
