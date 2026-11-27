import AppKit
import ApplicationServices
import Carbon.HIToolbox

/// Pastes each finished transcript into the app you're using, as if you had
/// pressed ⌘V there. Only when VoicePad itself isn't in front: pausing from
/// the floating bar or with the shortcut keeps your app in front, so the text
/// lands where you were typing.
@MainActor
final class AutoPaster {
    private let settings: MacSettings
    /// Where the last paste went, so the next part of the same dictation is
    /// separated from it by a space instead of running into it.
    private var lastPaste: (pid: pid_t, at: Date)?

    /// Says what happened (shown as a notice).
    var onNotice: ((String) -> Void)?

    init(settings: MacSettings) {
        self.settings = settings
    }

    /// Called with each transcript just copied to the clipboard.
    func transcriptCopied(_ text: String) {
        guard settings.autoPaste else { return }
        guard let target = NSWorkspace.shared.frontmostApplication,
              target.processIdentifier != ProcessInfo.processInfo.processIdentifier,
              !NSApp.isActive else { return } // VoicePad is in front: copying is enough
        guard AXIsProcessTrusted() else {
            onNotice?("Copied — allow Accessibility in Settings to paste automatically")
            return
        }
        // A password field has the keyboard: never type into it.
        guard !IsSecureEventInputEnabled() else {
            onNotice?("Copied — not pasted into a password field")
            return
        }
        let continuing = lastPaste.map { $0.pid == target.processIdentifier && Date.now.timeIntervalSince($0.at) < 120 } ?? false
        let text = continuing && !text.hasPrefix(" ") ? " " + text : text
        lastPaste = (target.processIdentifier, .now)
        Task { await paste(text, into: target) }
    }

    private func paste(_ text: String, into target: NSRunningApplication) async {
        let pasteboard = NSPasteboard.general
        let plain = pasteboard.string(forType: .string)
        if plain != text {
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)
        }
        // Wait (up to a second) for keys held from the shortcut to be let go,
        // so ⌘V doesn't arrive as ⌃⌥⌘V.
        for _ in 0..<20 where !NSEvent.modifierFlags.intersection([.command, .option, .control, .shift]).isEmpty {
            try? await Task.sleep(for: .milliseconds(50))
        }
        guard NSWorkspace.shared.frontmostApplication?.processIdentifier == target.processIdentifier else { return }
        Self.pressCommandV()
        onNotice?("Pasted into \(target.localizedName ?? "the app in front")")
        // The clipboard keeps the transcript itself, without the joining space.
        if let plain, plain != text {
            let change = pasteboard.changeCount
            try? await Task.sleep(for: .milliseconds(400))
            if pasteboard.changeCount == change {
                pasteboard.clearContents()
                pasteboard.setString(plain, forType: .string)
            }
        }
    }

    private static func pressCommandV() {
        let source = CGEventSource(stateID: .combinedSessionState)
        let key = CGKeyCode(kVK_ANSI_V)
        let down = CGEvent(keyboardEventSource: source, virtualKey: key, keyDown: true)
        let up = CGEvent(keyboardEventSource: source, virtualKey: key, keyDown: false)
        down?.flags = .maskCommand
        up?.flags = .maskCommand
        down?.post(tap: .cghidEventTap)
        up?.post(tap: .cghidEventTap)
    }
}
