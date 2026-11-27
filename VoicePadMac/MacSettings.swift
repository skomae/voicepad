import AppKit
import AVFoundation
import ApplicationServices
import Observation
import ServiceManagement

/// Mac-only preferences, and the state of the permissions they depend on.
/// (Everything shared with the iPhone app lives on `RecordingController`.)
@MainActor
@Observable
final class MacSettings {
    /// After a recording finishes (and is copied), paste it into the app in
    /// front — only when that app isn't VoicePad.
    var autoPaste: Bool {
        didSet { UserDefaults.standard.set(autoPaste, forKey: Keys.autoPaste) }
    }

    /// The small always-on-top bar with pause/resume.
    var showFloatingBar: Bool {
        didSet {
            UserDefaults.standard.set(showFloatingBar, forKey: Keys.floatingBar)
            onFloatingBarChanged?(showFloatingBar)
        }
    }

    /// ⌃⌥V anywhere starts and pauses recording.
    var globalShortcut: Bool {
        didSet {
            UserDefaults.standard.set(globalShortcut, forKey: Keys.shortcut)
            onShortcutChanged?(globalShortcut)
        }
    }

    /// Whether macOS opens VoicePad when you log in.
    private(set) var launchAtLogin: Bool

    /// VoicePad may post keystrokes (System Settings → Privacy & Security → Accessibility).
    private(set) var accessibilityTrusted = AXIsProcessTrusted()
    private(set) var microphone = AVCaptureDevice.authorizationStatus(for: .audio)

    @ObservationIgnored var onFloatingBarChanged: ((Bool) -> Void)?
    @ObservationIgnored var onShortcutChanged: ((Bool) -> Void)?
    @ObservationIgnored private var pollTask: Task<Void, Never>?

    static let shortcutLabel = "⌃⌥V"

    private enum Keys {
        static let autoPaste = "macAutoPaste"
        static let floatingBar = "macFloatingBar"
        static let shortcut = "macGlobalShortcut"
    }

    init() {
        let defaults = UserDefaults.standard
        autoPaste = defaults.object(forKey: Keys.autoPaste) as? Bool ?? true
        showFloatingBar = defaults.object(forKey: Keys.floatingBar) as? Bool ?? true
        globalShortcut = defaults.object(forKey: Keys.shortcut) as? Bool ?? true
        launchAtLogin = SMAppService.mainApp.status == .enabled
        // macOS has no callback for permission changes made in System
        // Settings, so look every second: onboarding and Settings update by
        // themselves the moment the user flips a switch.
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                self?.refreshPermissions()
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    func refreshPermissions() {
        let trusted = AXIsProcessTrusted()
        if trusted != accessibilityTrusted { accessibilityTrusted = trusted }
        let mic = AVCaptureDevice.authorizationStatus(for: .audio)
        if mic != microphone { microphone = mic }
        let login = SMAppService.mainApp.status == .enabled
        if login != launchAtLogin { launchAtLogin = login }
    }

    // MARK: Microphone

    /// Shows macOS's microphone prompt the first time; afterwards answers at once.
    func requestMicrophone() async -> Bool {
        let granted = await AVCaptureDevice.requestAccess(for: .audio)
        refreshPermissions()
        return granted
    }

    func openMicrophoneSettings() {
        open("x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone")
    }

    // MARK: Accessibility (for pasting)

    /// Shows macOS's "VoicePad would like to control this computer" prompt,
    /// which leads to the right switch in System Settings. VoicePad notices
    /// the switch by itself (see `init`).
    func requestAccessibility() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        if AXIsProcessTrustedWithOptions(options) {
            accessibilityTrusted = true
        }
    }

    func openAccessibilitySettings() {
        open("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
    }

    // MARK: Open at login

    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            Diagnostics.log("open at login \(enabled ? "on" : "off") failed: \(error.localizedDescription)")
        }
        refreshPermissions()
    }

    private func open(_ string: String) {
        if let url = URL(string: string) { NSWorkspace.shared.open(url) }
    }
}
