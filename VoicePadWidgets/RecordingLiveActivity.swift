import ActivityKit
import SwiftUI
import WidgetKit

@main
struct VoicePadWidgets: WidgetBundle {
    var body: some Widget {
        RecordingLiveActivity()
    }
}

private let recordingRed = Color(red: 0.83, green: 0.18, blue: 0.18)

struct RecordingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RecordingActivityAttributes.self) { context in
            LockScreenView(state: context.state)
                .padding(16)
                .activityBackgroundTint(context.state.isRecording ? recordingRed.opacity(0.9) : nil)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(state.isRecording ? "REC" : "VoicePad", systemImage: state.isRecording ? "record.circle" : "waveform")
                        .font(.headline)
                        .foregroundStyle(state.isRecording ? recordingRed : .primary)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if let start = state.startedAt {
                        Text(timerInterval: start...Date.distantFuture, countsDown: false)
                            .monospacedDigit()
                            .frame(maxWidth: 64)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(subtitle(state)).font(.caption).foregroundStyle(.secondary)
                        Text(state.latest.isEmpty ? "Listening…" : state.latest)
                            .font(.callout)
                            .lineLimit(2)
                        if let other = state.other, !other.isEmpty {
                            Text(other).font(.caption).lineLimit(2).opacity(0.8)
                        }
                        if state.isRecording { Buttons() }
                    }
                }
            } compactLeading: {
                Image(systemName: state.isRecording ? "record.circle" : "waveform")
                    .foregroundStyle(state.isRecording ? recordingRed : .secondary)
            } compactTrailing: {
                if let start = state.startedAt {
                    Text(timerInterval: start...Date.distantFuture, countsDown: false)
                        .monospacedDigit()
                        .frame(maxWidth: 44)
                } else {
                    ProgressView().controlSize(.mini)
                }
            } minimal: {
                Image(systemName: state.isRecording ? "record.circle" : "waveform")
                    .foregroundStyle(state.isRecording ? recordingRed : .secondary)
            }
        }
    }
}

private func subtitle(_ state: RecordingActivityAttributes.ContentState) -> String {
    if !state.isRecording {
        return state.finishing == 1 ? "Finishing transcript…" : "Finishing \(state.finishing) transcripts…"
    }
    var parts = ["Recording"]
    if state.part > 1 { parts.append("part \(state.part)") }
    if state.backgroundVoices { parts.append("background voices") }
    return parts.joined(separator: " · ")
}

private struct LockScreenView: View {
    let state: RecordingActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                if state.isRecording {
                    Image(systemName: "record.circle").font(.headline)
                    Text("REC").font(.headline.weight(.bold))
                    if let start = state.startedAt {
                        Text(timerInterval: start...Date.distantFuture, countsDown: false)
                            .font(.headline.monospacedDigit())
                    }
                } else {
                    ProgressView().tint(.primary)
                    Text("VoicePad").font(.headline)
                }
                Spacer()
                Text(subtitle(state)).font(.caption)
            }
            Text(state.latest.isEmpty ? (state.isRecording ? "Listening…" : "Transcribing your recording…") : state.latest)
                .font(.callout)
                .lineLimit(3)
            // The person opposite reads their own language: the phone on the table is
            // their script, the job a foldable's cover screen did.
            if let other = state.other, !other.isEmpty {
                HStack(alignment: .top, spacing: 6) {
                    if let language = state.otherLanguage {
                        Text(language)
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(.white.opacity(0.18), in: RoundedRectangle(cornerRadius: 4))
                    }
                    Text(other)
                        .font(.callout)
                        .lineLimit(3)
                        .opacity(0.9)
                }
            }
            if state.isRecording { Buttons() }
        }
        .foregroundStyle(state.isRecording ? .white : .primary)
    }
}

private struct Buttons: View {
    var body: some View {
        HStack(spacing: 10) {
            Button(intent: StopRecordingIntent()) {
                Label("Stop", systemImage: "stop.fill").frame(maxWidth: .infinity)
            }
            Button(intent: SplitRecordingIntent()) {
                Label("Split", systemImage: "scissors").frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.bordered)
        .tint(.white)
    }
}
