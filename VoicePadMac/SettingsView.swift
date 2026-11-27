import SwiftUI

struct SettingsView: View {
    var body: some View {
        TabView {
            GeneralSettings().tabItem { Label("General", systemImage: "gearshape") }
            TranscriptionSettings().tabItem { Label("Transcription", systemImage: "waveform") }
            StorageSettings().tabItem { Label("Storage", systemImage: "internaldrive") }
        }
        .frame(width: 540)
        .frame(minHeight: 420)
    }
}

/// What happens when a recording (or a split-off part) finishes.
enum AfterRecording: Hashable {
    case nothing, copy, copyAndPaste
}

private struct GeneralSettings: View {
    @Environment(RecordingController.self) private var controller
    @Environment(MacSettings.self) private var settings

    private var afterRecording: Binding<AfterRecording> {
        Binding {
            !controller.copyWhenDone ? .nothing : settings.autoPaste ? .copyAndPaste : .copy
        } set: { value in
            controller.copyWhenDone = value != .nothing
            settings.autoPaste = value == .copyAndPaste
            if value == .copyAndPaste && !settings.accessibilityTrusted { settings.requestAccessibility() }
        }
    }

    var body: some View {
        @Bindable var controller = controller
        @Bindable var settings = settings
        Form {
            Section {
                Picker("When a recording finishes", selection: afterRecording) {
                    Text("Do nothing").tag(AfterRecording.nothing)
                    Text("Copy to the clipboard").tag(AfterRecording.copy)
                    Text("Copy and paste into the app I'm using").tag(AfterRecording.copyAndPaste)
                }
                if afterRecording.wrappedValue == .copyAndPaste {
                    if settings.accessibilityTrusted {
                        Label("Pasting is allowed", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                    } else {
                        HStack {
                            Label("Pasting needs Accessibility permission", systemImage: "exclamationmark.triangle.fill")
                                .foregroundStyle(.orange)
                            Spacer()
                            Button("Allow…") { settings.requestAccessibility() }
                            Button("Open Settings") { settings.openAccessibilitySettings() }
                        }
                    }
                }
            } footer: {
                Text("Pastes only when another app is in front — so pausing from the floating bar or with \(MacSettings.shortcutLabel) puts the text where you were typing. Each part of an auto-split recording is pasted as it finishes. Password fields are never typed into.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section {
                Toggle("Start recording when VoicePad opens", isOn: $controller.autoRecordOnOpen)
                Toggle("Show the floating bar", isOn: $settings.showFloatingBar)
                Toggle("Pause and resume with \(MacSettings.shortcutLabel) from any app", isOn: $settings.globalShortcut)
                Toggle("Open VoicePad when I log in", isOn: Binding(get: { settings.launchAtLogin },
                                                                    set: { settings.setLaunchAtLogin($0) }))
            }

            Section {
                Picker("Appearance", selection: $controller.themeMode) {
                    ForEach(ThemeMode.allCases) { Text($0 == .black ? "Black" : $0.label).tag($0) }
                }
                LabeledContent("Microphone") {
                    if settings.microphone == .authorized {
                        Text("Allowed — uses the input chosen in System Settings → Sound").foregroundStyle(.secondary)
                    } else {
                        Button("Open Privacy Settings") { settings.openMicrophoneSettings() }
                    }
                }
            }
        }
        .formStyle(.grouped)
    }
}

private struct TranscriptionSettings: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        @Bindable var controller = controller
        Form {
            Section {
                Picker("Speech model", selection: $controller.engineChoice) {
                    ForEach(EngineChoice.allCases) { choice in
                        Text("\(choice.displayName) · \(choice.approximateSize)").tag(choice)
                    }
                }
                Text(controller.engineChoice.languages).font(.caption).foregroundStyle(.secondary)
                LabeledContent("Status") { status }
                Toggle("Background voices (room microphone, Whisper turbo)",
                       isOn: Binding(get: { controller.backgroundVoices }, set: { controller.setBackgroundVoices($0) }))
            } footer: {
                Text("Models download once and then run offline on this Mac.").font(.caption).foregroundStyle(.secondary)
            }
            Section {
                Picker("Start a new note", selection: $controller.autoSplit) {
                    ForEach(AutoSplit.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                Toggle("Translate other languages to English", isOn: $controller.autoTranslate)
            }
        }
        .formStyle(.grouped)
    }

    @ViewBuilder private var status: some View {
        switch controller.engineStatus {
        case .idle: Text("Not loaded").foregroundStyle(.secondary)
        case .loading(let progress):
            HStack {
                if progress.determinate { ProgressView(value: progress.fraction).frame(width: 100) } else { ProgressView().controlSize(.small) }
                Text(progress.stage).lineLimit(1).foregroundStyle(.secondary)
            }
        case .ready: Label("Ready", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
        case .failed(let message):
            HStack {
                Text(message).lineLimit(2).foregroundStyle(.orange)
                Button("Retry") { controller.loadEngine() }
            }
        }
    }
}

private struct StorageSettings: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        @Bindable var controller = controller
        Form {
            Section {
                Picker("Delete notes with no speech", selection: $controller.autoDeleteEmpty) {
                    ForEach(NoteCleanup.AutoAge.allCases) { Text($0.label).tag($0) }
                }
                Picker("Delete recordings (keep the text)", selection: $controller.autoDeleteAudio) {
                    ForEach(NoteCleanup.AutoAge.allCases) { Text($0.label).tag($0) }
                }
                Picker("Compress recordings", selection: $controller.autoCompress) {
                    ForEach(NoteCleanup.AutoAge.allCases) { Text($0.label).tag($0) }
                }
            } footer: {
                Text("Pinned notes and notes still being transcribed are never touched.").font(.caption).foregroundStyle(.secondary)
            }
            Section {
                LabeledContent("Notes and recordings") {
                    Button("Show in Finder") { NSWorkspace.shared.open(AppPaths.data) }
                }
            }
        }
        .formStyle(.grouped)
    }
}
