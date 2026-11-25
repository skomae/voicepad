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
        // Reconcile once at startup: a process that died mid-recording leaves an
        // activity on the Lock Screen, and observation alone never fires for a
        // state that doesn't change.
        show(state())
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
        // Activities outlive the process. If one is already up — the app was
        // killed, relaunched or updated mid-recording — adopt it instead of
        // asking for a second: two requests put two on the Lock Screen.
        let existing = Activity<RecordingActivityAttributes>.activities
        if let adopted = newest(existing) {
            Diagnostics.log("live activity: adopting the existing activity (\(existing.count) up)")
            activity = adopted
            for extra in existing where extra.id != adopted.id {
                Task { await extra.end(nil, dismissalPolicy: .immediate) }
            }
            send(state)
            return
        }
        Diagnostics.log("live activity: starting a new one (none up) latest=\(state.latest.count) other=\(state.other?.count ?? 0) lang=\(state.otherLanguage ?? "-")")
        activity = try? Activity.request(
            attributes: RecordingActivityAttributes(),
            content: ActivityContent(state: state, staleDate: nil)
        )
        lastSent = state
        lastSentAt = .now
    }

    /// The most recently started activity, if one survived the last process.
    private func newest(_ activities: [Activity<RecordingActivityAttributes>])
        -> Activity<RecordingActivityAttributes>? {
        activities.max { a, b in
            (a.content.state.startedAt ?? .distantPast) < (b.content.state.startedAt ?? .distantPast)
        }
    }

    private func send(_ state: State) {
        guard let activity else { return }
        lastSent = state
        lastSentAt = .now
        Task { await activity.update(ActivityContent(state: state, staleDate: nil)) }
    }

    private func end() {
        activity = nil
        lastSent = nil
        // End every instance, not only the one this process made: a leftover
        // from a killed process would otherwise sit on the Lock Screen forever.
        let all = Activity<RecordingActivityAttributes>.activities
        guard !all.isEmpty else { return }
        Diagnostics.log("live activity: ending \(all.count)")
        Task {
            for a in all { await a.end(nil, dismissalPolicy: .immediate) }
        }
    }
}
