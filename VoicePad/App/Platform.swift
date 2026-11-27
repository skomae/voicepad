import Foundation
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// The few things the shared recording code needs from the operating system,
/// so `RecordingController` stays the same on iPhone and Mac.
@MainActor
enum Platform {
    /// Puts `text` on the clipboard.
    static func copy(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #elseif os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }

    /// For the diagnostics log: is the app really in front (and, on iPhone, unlocked)?
    static var appState: String {
        #if os(iOS)
        let state = ["active", "inactive", "background"][UIApplication.shared.applicationState.rawValue]
        return "app=\(state) unlocked=\(UIApplication.shared.isProtectedDataAvailable)"
        #elseif os(macOS)
        return "app=\(NSApp?.isActive == true ? "active" : "inactive")"
        #endif
    }

    #if os(iOS)
    typealias BackgroundTask = UIBackgroundTaskIdentifier
    #else
    typealias BackgroundTask = Int
    #endif

    /// Asks for time to finish work after the microphone stops (iOS may
    /// otherwise suspend the app). Macs don't suspend apps, so it's a no-op there.
    static func beginBackgroundTask(_ name: String) -> BackgroundTask {
        #if os(iOS)
        return UIApplication.shared.beginBackgroundTask(withName: name)
        #else
        return 0
        #endif
    }

    static func endBackgroundTask(_ task: BackgroundTask) {
        #if os(iOS)
        if task != .invalid { UIApplication.shared.endBackgroundTask(task) }
        #endif
    }
}
