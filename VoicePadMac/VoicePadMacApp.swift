import AppKit
import Observation
import SwiftUI

@main
struct VoicePadMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var app
    @Environment(\.openWindow) private var openWindow

    var body: some Scene {
        Window("VoicePad", id: AppDelegate.mainWindowID) {
            MainView()
                .environment(app.controller)
                .environment(app.settings)
                .onAppear { app.capture(openWindow: { [openWindow] in openWindow(id: AppDelegate.mainWindowID) }) }
        }
        .defaultSize(width: 560, height: 700)
        .commands {
            CommandGroup(after: .newItem) {
                Button(app.controller.isRecording ? "Pause Recording" : "Start Recording") {
                    Task { await app.controller.toggle() }
                }
                .keyboardShortcut("r")
                Button("Split") { app.controller.split() }
                    .keyboardShortcut("s", modifiers: [.command, .shift])
                    .disabled(!app.controller.isRecording)
            }
            CommandGroup(after: .windowArrangement) {
                Toggle("Floating Bar", isOn: Bindable(app.settings).showFloatingBar)
                    .keyboardShortcut("b", modifiers: [.command, .shift])
            }
        }

        Settings {
            SettingsView()
                .environment(app.controller)
                .environment(app.settings)
                .preferredColorScheme(app.controller.themeMode.colorScheme)
        }

        MenuBarExtra {
            MenuBarContent(openMain: { app.showMainWindow() })
                .environment(app.controller)
                .environment(app.settings)
        } label: {
            Image(systemName: app.controller.isRecording ? "waveform.circle.fill" : "waveform")
        }
    }
}

private struct MenuBarContent: View {
    @Environment(RecordingController.self) private var controller
    @Environment(MacSettings.self) private var settings
    let openMain: () -> Void

    var body: some View {
        @Bindable var settings = settings
        Button(controller.isRecording ? "Pause Recording   \(MacSettings.shortcutLabel)" : "Start Recording   \(MacSettings.shortcutLabel)") {
            Task { await controller.toggle() }
        }
        .disabled(!controller.onboardingDone)
        Button("Split") { controller.split() }.disabled(!controller.isRecording)
        Divider()
        Button("Open VoicePad") { openMain() }
        Toggle("Floating Bar", isOn: $settings.showFloatingBar)
        SettingsLink { Text("Settings…") }
        Divider()
        Button("Quit VoicePad") { NSApp.terminate(nil) }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    static let mainWindowID = "main"

    let store: NoteStore
    let controller: RecordingController
    let settings = MacSettings()
    private lazy var paster = AutoPaster(settings: settings)
    private var panel: FloatingPanel?
    private var hotKey: HotKey?
    private var openMainWindow: (() -> Void)?
    private var noticeClear: Task<Void, Never>?

    override init() {
        let store = NoteStore()
        self.store = store
        controller = RecordingController(store: store)
        super.init()
    }

    func capture(openWindow: @escaping () -> Void) {
        openMainWindow = openWindow
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        controller.onTranscriptCopied = { [weak self] text in self?.paster.transcriptCopied(text) }
        paster.onNotice = { [weak self] text in self?.controller.notice = text }

        panel = FloatingPanel(rootView: FloatingBar(openMain: { [weak self] in self?.showMainWindow() },
                                                    openSettings: { [weak self] in self?.showSettings() })
            .environment(controller)
            .environment(settings))
        settings.onFloatingBarChanged = { [weak self] _ in self?.updatePanel() }
        settings.onShortcutChanged = { [weak self] _ in self?.updateHotKey() }
        updateHotKey()
        observeOnboarding()
        observeNotices()

        // Loads the speech model (downloading it the first time) and, once
        // onboarding is done, starts recording. Only at launch: on a Mac the
        // app becomes active every time a window is clicked, and that mustn't
        // undo a pause.
        Task { await controller.appBecameActive() }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { showMainWindow() }
        return true
    }

    func applicationWillTerminate(_ notification: Notification) {
        // Close the WAV properly; the note finishes transcribing next launch.
        controller.stop()
    }

    // MARK: Windows

    func showMainWindow() {
        NSApp.activate()
        if let window = NSApp.windows.first(where: { $0.identifier?.rawValue.hasPrefix(Self.mainWindowID) == true && $0.isVisible }) {
            window.makeKeyAndOrderFront(nil)
        } else {
            openMainWindow?()
        }
    }

    func showSettings() {
        NSApp.activate()
        NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
    }

    private func updatePanel() {
        guard let panel else { return }
        if settings.showFloatingBar && controller.onboardingDone {
            panel.ensureOnScreen()
            panel.orderFrontRegardless()
        } else {
            panel.orderOut(nil)
        }
    }

    private func updateHotKey() {
        hotKey = settings.globalShortcut
            ? HotKey { [weak self] in
                guard let self, self.controller.onboardingDone else { return }
                Task { await self.controller.toggle() }
            }
            : nil
    }

    // MARK: Observation

    /// Shows the floating bar once onboarding finishes.
    private func observeOnboarding() {
        withObservationTracking {
            _ = controller.onboardingDone
        } onChange: { [weak self] in
            Task { @MainActor in
                self?.updatePanel()
                self?.observeOnboarding()
            }
        }
        updatePanel()
    }

    /// Short notices ("Pasted into Notes") fade after a few seconds.
    private func observeNotices() {
        withObservationTracking {
            _ = controller.notice
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.noticeClear?.cancel()
                if let notice = self.controller.notice {
                    self.noticeClear = Task { @MainActor [weak self] in
                        try? await Task.sleep(for: .seconds(3))
                        guard !Task.isCancelled, let self, self.controller.notice == notice else { return }
                        self.controller.notice = nil
                    }
                }
                self.observeNotices()
            }
        }
    }
}
