import Foundation
import Observation
import SwiftUI

/// Keeps a pair of Even Realities G1s showing the conversation.
///
/// The glasses are the wearer's private subtitle track: only the other person's
/// speech, translated. Pages are conflated — a page still in flight is abandoned
/// rather than queued behind a newer one — and writes are spaced at least 350 ms
/// apart, because the glasses acknowledge slowly and a burst just backs up.
@MainActor
@Observable
final class GlassesController {
    private(set) var linkState: GlassesLinkState = .idle
    /// The page on the glasses (also the settings preview).
    private(set) var page: [String] = []

    var scope: GlassesScope {
        didSet {
            defaults.set(scope.rawValue, forKey: Self.scopeKey)
            page = []
        }
    }

    var enabled: Bool {
        didSet {
            defaults.set(enabled, forKey: Self.enabledKey)
            if enabled { startLinkIfNeeded() } else if link != nil { stop() }
            page = []
        }
    }

    private static let scopeKey = "glassesScope"
    private static let enabledKey = "glassesEnabled"

    private let defaults: UserDefaults
    private var link: GlassesLink?
    /// A page waiting for the current write to finish; only the newest survives.
    private var pending: [String]?
    private var pushing: Task<Void, Never>?
    private var lastPushAt = Date.distantPast
    private var lastSent: [String]?

    /// How far apart writes may be: the glasses' acknowledgements are not quick.
    private static let spacing: TimeInterval = 0.35

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        scope = GlassesScope(rawValue: defaults.string(forKey: Self.scopeKey) ?? "") ?? .them
        enabled = defaults.bool(forKey: Self.enabledKey)
    }

    /// Mirrors `stage` onto the glasses. Called for every stage change, so it must be
    /// cheap when nothing has changed.
    func mirror(stage: Stage, myLanguage: String, theirLanguage: String, listening: Bool) {
        guard enabled else { return }
        let page = GlassesScreen.page(stage: stage, myLanguage: myLanguage, theirLanguage: theirLanguage,
                                      scope: scope, listening: listening)
        self.page = page
        guard page != lastSent else { return }
        pending = page
        schedule()
    }

    /// Conversation mode closed (or the app went away): hand the glasses back.
    func leave() {
        pending = nil
        pushing?.cancel()
        pushing = nil
        lastSent = nil
        page = []
        Task { await link?.exit() }
    }

    /// Turns the link off entirely (the settings toggle).
    func stop() {
        leave()
        link?.stop()
        link = nil
        linkState = .idle
    }

    private func startLinkIfNeeded() {
        guard link == nil else { return }
        let link = GlassesLink()
        link.onChange = { [weak self] state in
            guard let self else { return }
            self.linkState = state
            if case .connected = state, let last = self.lastSent {
                // Reconnected: put the current page back up.
                self.pending = last
                self.schedule()
            }
        }
        self.link = link
        link.start()
    }

    private func schedule() {
        startLinkIfNeeded()
        guard pushing == nil else { return }
        pushing = Task { [weak self] in
            await self?.drain()
        }
    }

    private func drain() async {
        defer { pushing = nil }
        while let next = pending {
            pending = nil
            // Space the writes out; a newer page arriving meanwhile replaces it.
            let wait = Self.spacing - Date().timeIntervalSince(lastPushAt)
            if wait > 0 { try? await Task.sleep(for: .seconds(wait)) }
            if Task.isCancelled { return }
            lastPushAt = .now
            lastSent = next
            await link?.show(next)
        }
    }
}

/// The settings row: turn the glasses on, choose what they show, and see exactly
/// what is on them (5 lines x 40 columns, green on black — the waveguide's own look).
struct GlassesSection: View {
    @Environment(GlassesController.self) private var glasses

    var body: some View {
        Section {
            Toggle(isOn: Binding(get: { glasses.enabled }, set: { on in
                if on { glasses.enabled = true } else { glasses.enabled = false; glasses.stop() }
            })) {
                Label("Even Realities G1", systemImage: "eyeglasses")
            }
            if glasses.enabled {
                Picker("Show", selection: Binding(get: { glasses.scope }, set: { glasses.scope = $0 })) {
                    ForEach(GlassesScope.allCases) { scope in
                        Text(scope.label).tag(scope)
                    }
                }
                Text(glasses.scope.detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                HStack {
                    Text(glasses.linkState.label)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Spacer()
                    if case .failed = glasses.linkState {
                        Button("Look again") { glasses.stop(); glasses.enabled = true }
                            .font(.footnote.weight(.semibold))
                    }
                }
                preview
            }
        } header: {
            Text("Glasses")
        } footer: {
            Text("One pair at a time, and the Even Realities app holds their connection: the glasses pair with VoicePad or with that app, not both.")
        }
    }

    private var preview: some View {
        let lines = glasses.page.isEmpty
            ? ["", "", GlassesScreen.centre("G1 → EN"), GlassesScreen.centre("waiting…"), ""]
            : glasses.page
        return VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line.isEmpty ? " " : line)
                    .font(.system(size: 11, weight: .regular, design: .monospaced))
                    .foregroundStyle(Color(red: 0.55, green: 1, blue: 0.55))
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color.black, in: RoundedRectangle(cornerRadius: 8))
        .accessibilityLabel("Glasses preview")
    }
}
