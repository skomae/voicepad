import AVFoundation
import SwiftUI

/// First run. The speech model starts downloading the moment VoicePad opens,
/// and each step moves on by itself as soon as macOS reports the permission,
/// so most people only ever click "Allow".
struct OnboardingView: View {
    @Environment(RecordingController.self) private var controller
    @Environment(MacSettings.self) private var settings
    @State private var step: Step = .welcome
    @State private var openAtLogin = true

    enum Step: Int, CaseIterable {
        case welcome, microphone, paste, ready
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                switch step {
                case .welcome: welcome
                case .microphone: microphone
                case .paste: paste
                case .ready: ready
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, 44)
            .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                    removal: .move(edge: .leading).combined(with: .opacity)))
            .id(step)

            footer
        }
        .frame(minWidth: 520, minHeight: 560)
        .animation(.spring(duration: 0.4), value: step)
        // Move on by itself when the user flips the switch in System Settings.
        .onChange(of: settings.microphone) { _, status in
            if step == .microphone, status == .authorized { advance(after: 0.6) }
        }
        .onChange(of: settings.accessibilityTrusted) { _, trusted in
            if step == .paste, trusted { advance(after: 0.6) }
        }
    }

    // MARK: Steps

    private var welcome: some View {
        StepLayout(symbol: "waveform", tint: .red,
                   title: "Talk. VoicePad types.",
                   message: "VoicePad turns what you say into text, right here on your Mac. Your voice and your notes never leave this computer.") {
            VStack(alignment: .leading, spacing: 12) {
                Feature(symbol: "bolt.fill", text: "Starts listening as soon as it opens")
                Feature(symbol: "pause.circle.fill", text: "A tiny floating bar to pause from any app")
                Feature(symbol: "doc.on.clipboard.fill", text: "Finished text is copied — and can paste itself where you're typing")
                Feature(symbol: "lock.fill", text: "Speech recognition runs on this Mac, offline once set up")
            }
            .padding(.top, 8)
        } actions: {
            Button("Get Started") { go(.microphone) }
                .buttonStyle(.borderedProminent).controlSize(.large).keyboardShortcut(.defaultAction)
        }
    }

    private var microphone: some View {
        let status = settings.microphone
        return StepLayout(symbol: status == .authorized ? "checkmark.circle.fill" : "mic.fill",
                          tint: status == .authorized ? .green : .red,
                          title: "Let VoicePad hear you",
                          message: "macOS will ask once whether VoicePad may use the microphone. You'll see the orange dot in the menu bar whenever it's listening.") {
            EmptyView()
        } actions: {
            switch status {
            case .authorized:
                Label("Microphone allowed", systemImage: "checkmark").foregroundStyle(.green)
            case .denied, .restricted:
                VStack(spacing: 10) {
                    Text("The microphone is turned off for VoicePad. Turn it on in System Settings — this page will notice by itself.")
                        .multilineTextAlignment(.center).foregroundStyle(.secondary)
                    Button("Open System Settings") { settings.openMicrophoneSettings() }
                        .buttonStyle(.borderedProminent).controlSize(.large)
                }
            default:
                Button("Allow Microphone") {
                    Task {
                        if await settings.requestMicrophone() { advance(after: 0.6) }
                    }
                }
                .buttonStyle(.borderedProminent).controlSize(.large).keyboardShortcut(.defaultAction)
            }
        }
        .onAppear { if status == .authorized { advance(after: 0.8) } }
    }

    private var paste: some View {
        @Bindable var settings = settings
        return StepLayout(symbol: settings.accessibilityTrusted && settings.autoPaste ? "checkmark.circle.fill" : "text.insert",
                          tint: settings.accessibilityTrusted && settings.autoPaste ? .green : .blue,
                          title: "Paste where you're typing",
                          message: "When you pause, the text is always copied. VoicePad can also paste it straight into the app you're using — Mail, Notes, Slack, a code editor — but only when VoicePad itself isn't in front.") {
            VStack(alignment: .leading, spacing: 10) {
                Picker("", selection: $settings.autoPaste) {
                    Text("Copy and paste into the app I'm using").tag(true)
                    Text("Just copy to the clipboard").tag(false)
                }
                .pickerStyle(.radioGroup)
                .labelsHidden()
                if settings.autoPaste && !settings.accessibilityTrusted {
                    Text("To press ⌘V for you, macOS needs you to turn VoicePad on under Privacy & Security → Accessibility. It never reads your screen or keystrokes.")
                        .font(.callout).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.top, 4)
        } actions: {
            if !settings.autoPaste {
                Button("Continue") { go(.ready) }
                    .buttonStyle(.borderedProminent).controlSize(.large).keyboardShortcut(.defaultAction)
            } else if settings.accessibilityTrusted {
                Label("Pasting is on", systemImage: "checkmark").foregroundStyle(.green)
                    .onAppear { advance(after: 0.8) }
            } else {
                VStack(spacing: 8) {
                    Button("Allow Pasting…") { settings.requestAccessibility() }
                        .buttonStyle(.borderedProminent).controlSize(.large).keyboardShortcut(.defaultAction)
                    Button("Not now — just copy") {
                        settings.autoPaste = false
                        go(.ready)
                    }
                    .buttonStyle(.link)
                }
            }
        }
    }

    private var ready: some View {
        StepLayout(symbol: "checkmark.seal.fill", tint: .green,
                   title: "You're set",
                   message: "This little bar floats above your windows. Click it — or press \(MacSettings.shortcutLabel) in any app — to pause and resume.") {
            VStack(spacing: 16) {
                FloatingBarPreview()
                Toggle("Open VoicePad when I log in", isOn: $openAtLogin)
                    .toggleStyle(.checkbox)
            }
            .padding(.top, 6)
        } actions: {
            Button("Start Listening") { finish() }
                .buttonStyle(.borderedProminent).controlSize(.large).keyboardShortcut(.defaultAction)
        }
    }

    // MARK: Footer: dots and the model download

    private var footer: some View {
        VStack(spacing: 12) {
            ModelDownloadRow()
            HStack(spacing: 7) {
                ForEach(Step.allCases, id: \.self) { s in
                    Circle()
                        .fill(s == step ? Color.primary : Color.primary.opacity(0.18))
                        .frame(width: 7, height: 7)
                        .onTapGesture { if s.rawValue < step.rawValue { go(s) } }
                }
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity)
        .background(.bar)
    }

    // MARK: Navigation

    private func go(_ next: Step) { step = next }

    private func advance(after seconds: Double) {
        let from = step
        Task {
            try? await Task.sleep(for: .seconds(seconds))
            guard step == from, let next = Step(rawValue: from.rawValue + 1) else { return }
            step = next
        }
    }

    private func finish() {
        if openAtLogin != settings.launchAtLogin { settings.setLaunchAtLogin(openAtLogin) }
        settings.showFloatingBar = true
        Task { await controller.finishOnboarding() }
    }
}

// MARK: - Pieces

private struct StepLayout<Content: View, Actions: View>: View {
    let symbol: String
    let tint: Color
    let title: String
    let message: String
    @ViewBuilder let content: Content
    @ViewBuilder let actions: Actions

    var body: some View {
        VStack(spacing: 18) {
            Spacer(minLength: 20)
            Image(systemName: symbol)
                .font(.system(size: 46, weight: .medium))
                .foregroundStyle(tint)
                .symbolRenderingMode(.hierarchical)
                .frame(height: 60)
                .contentTransition(.symbolEffect(.replace))
            Text(title).font(.system(size: 26, weight: .bold))
            Text(message)
                .font(.title3)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: 420)
            content.frame(maxWidth: 420, alignment: .leading)
            Spacer(minLength: 12)
            actions.frame(minHeight: 44)
            Spacer(minLength: 20)
        }
    }
}

private struct Feature: View {
    let symbol: String
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).foregroundStyle(.red).frame(width: 22)
            Text(text)
        }
    }
}

/// The speech model downloading in the background during onboarding.
private struct ModelDownloadRow: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        HStack(spacing: 10) {
            switch controller.engineStatus {
            case .idle:
                ProgressView().controlSize(.small)
                Text("Preparing the speech model…")
            case .loading(let progress):
                if progress.determinate {
                    ProgressView(value: progress.fraction).frame(width: 140)
                } else {
                    ProgressView().controlSize(.small)
                }
                Text(progress.stage).lineLimit(1).truncationMode(.middle)
            case .ready:
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                Text("Speech model ready — \(controller.activeEngine.shortName)")
            case .failed(let message):
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                Text("Model download failed: \(message)").lineLimit(1)
                Button("Retry") { controller.loadEngine() }.controlSize(.small)
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .frame(height: 18)
    }
}

/// A non-interactive copy of the floating bar, to show what to look for.
private struct FloatingBarPreview: View {
    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                Circle().fill(Color.red.opacity(0.14))
                Image(systemName: "pause.fill").font(.system(size: 13, weight: .semibold)).foregroundStyle(.red)
            }
            .frame(width: 34, height: 34)
            Text("…so the words show up here as you speak, two lines at a time")
                .font(.system(size: 12)).lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .trailing, spacing: 5) {
                Text("14 words").font(.system(size: 10, weight: .medium)).foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    Image(systemName: "scissors")
                    Image(systemName: "text.insert").foregroundStyle(.tint)
                    Image(systemName: "macwindow")
                }
                .font(.system(size: 12)).foregroundStyle(.secondary)
            }
        }
        .padding(.leading, 12).padding(.trailing, 12)
        .frame(width: FloatingBar.size.width, height: FloatingBar.size.height)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Color.red.opacity(0.22)) }
        .shadow(color: .black.opacity(0.15), radius: 10, y: 4)
        .allowsHitTesting(false)
    }
}
