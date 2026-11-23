import SwiftUI

struct RecordBar: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        VStack(spacing: 8) {
            if let error = controller.errorMessage {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }
            HStack(spacing: 16) {
                if controller.isRecording, let start = controller.startedAt {
                    TimelineView(.periodic(from: start, by: 1)) { context in
                        Text(Duration.seconds(context.date.timeIntervalSince(start)).formatted(.time(pattern: .minuteSecond)))
                            .font(.body.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    .frame(width: 56, alignment: .leading)
                } else {
                    Spacer().frame(width: 56)
                }

                Button {
                    Task { await controller.toggle() }
                } label: {
                    ZStack {
                        Circle()
                            .fill(Color.red.opacity(0.18))
                            .frame(width: 76, height: 76)
                            .scaleEffect(controller.isRecording ? 1 + CGFloat(controller.level) * 0.35 : 1)
                            .animation(.easeOut(duration: 0.08), value: controller.level)
                        Circle()
                            .fill(.red)
                            .frame(width: 62, height: 62)
                        if controller.isRecording {
                            RoundedRectangle(cornerRadius: 5).fill(.white).frame(width: 22, height: 22)
                        } else {
                            Image(systemName: "mic.fill").font(.title2).foregroundStyle(.white)
                        }
                    }
                }
                .accessibilityLabel(controller.isRecording ? "Stop recording" : "Start recording")
                .disabled(!controller.isRecording && controller.recordingNoteID != nil)

                LevelMeter(level: controller.isRecording ? controller.level : 0)
                    .frame(width: 56, height: 28)
            }
        }
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .background(.bar)
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

    var body: some View {
        HStack(spacing: 5) {
            switch controller.engineStatus {
            case .idle:
                Image(systemName: "circle.dashed")
            case .loading(let p):
                ProgressView().scaleEffect(0.6)
                Text(p > 0 ? "\(Int(p * 100))%" : "Loading")
            case .ready:
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            case .failed:
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
            Text(controller.engineChoice.shortName)
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}
