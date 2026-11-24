import ActivityKit
import Foundation
import Observation

/// Mirrors the recording in a Live Activity (Lock Screen and Dynamic Island):
/// timer, part number, background-voices mode and the latest words, with Stop
/// and Split buttons. It stays up as "Finishing transcript…" until stopped or
/// split-off notes are transcribed, then ends. Updates at most once a second.
@MainActor
final class LiveActivityPresenter {
    typealias State = RecordingActivityAttributes.ContentState

    private var activity: Activity<RecordingActivityAttributes>?
    private var lastSent: State?
    private var lastSentAt = Date.distantPast
    private var trailing: Task<Void, Never>?
    private let minInterval: TimeInterval = 1

    /// Watches `state()` and keeps the Live Activity in step with it.
    func observe(_ state: @escaping @MainActor () -> State?) {
        withObservationTracking {
            _ = state()
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.observe(state)
                self.show(state())
            }
        }
    }

    private func show(_ state: State?) {
        guard let state else {
            trailing?.cancel()
            end()
            return
        }
        guard state != lastSent else { return }
        if activity == nil {
            start(state)
            return
        }
        // Timers tick on their own; send text changes at most once a second.
        let wait = minInterval - Date().timeIntervalSince(lastSentAt)
        trailing?.cancel()
        if wait <= 0 || state.isRecording != lastSent?.isRecording || state.part != lastSent?.part {
            send(state)
        } else {
            trailing = Task { [weak self] in
                try? await Task.sleep(for: .seconds(wait))
                guard !Task.isCancelled else { return }
                self?.send(state)
            }
        }
    }

    private func start(_ state: State) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        activity = try? Activity.request(
            attributes: RecordingActivityAttributes(),
            content: ActivityContent(state: state, staleDate: nil)
        )
        lastSent = state
        lastSentAt = .now
    }

    private func send(_ state: State) {
        guard let activity else { return }
        lastSent = state
        lastSentAt = .now
        Task { await activity.update(ActivityContent(state: state, staleDate: nil)) }
    }

    private func end() {
        guard let activity else { return }
        self.activity = nil
        lastSent = nil
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
    }
}
