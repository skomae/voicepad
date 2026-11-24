import SwiftUI

struct SettingsView: View {
    @Environment(RecordingController.self) private var controller
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var controller = controller
        NavigationStack {
            Form {
                Section {
                    Toggle("Record when app opens", isOn: $controller.autoRecordOnOpen)
                    Toggle("Copy transcript when done", isOn: $controller.copyWhenDone)
                    Picker("Theme", selection: $controller.themeMode) {
                        ForEach(ThemeMode.allCases) { Text($0.label).tag($0) }
                    }
                } footer: {
                    Text("Copy when done puts each finished recording (and each split-off part) on the clipboard.")
                }

                Section {
                    Toggle("Translate to English automatically", isOn: $controller.autoTranslate)
                } footer: {
                    Text("Detects the spoken language (e.g. Mandarin, French, Spanish) and shows the English translation, plus pinyin for Mandarin. Runs on the device; iOS asks before downloading each language's translation pack once. Needs iOS 18.")
                }

                MicrophoneSection()

                Section {
                    Toggle("Capture background voices", isOn: Binding(
                        get: { controller.backgroundVoices },
                        set: { controller.setBackgroundVoices($0) }
                    ))
                } footer: {
                    Text("For announcements, a TV or someone across the room. Records the whole room instead of just your voice, evens out the loudness, and uses Whisper turbo (~630 MB download, slower), which in testing made a quarter of the errors on distant, noisy speech. Also picks up nearby chatter, so leave it off for normal notes.")
                }

                Section {
                    Picker("Model", selection: $controller.engineChoice) {
                        ForEach(EngineChoice.allCases) { choice in
                            VStack(alignment: .leading) {
                                Text(choice.displayName)
                                Text("\(choice.languages) · \(choice.approximateSize)").font(.caption).foregroundStyle(.secondary)
                            }
                            .tag(choice)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("Transcription model")
                } footer: {
                    Text("Use Auto: Western for English and European languages, Auto: Asian + English when you speak Mandarin, Cantonese, Japanese or Korean (and English). Both pick the model per sentence. Silence is skipped and audio is split at pauses. Models run entirely on device. Each is downloaded once on first use. Recording starts immediately; text catches up once the model is ready.")
                }

                Section("Status") {
                    switch controller.engineStatus {
                    case .idle: Text("Not loaded")
                    case .loading(let p):
                        if p > 0 {
                            ProgressView(value: p) { Text("Downloading model…") }
                        } else {
                            ProgressView { Text("Loading model…") }
                        }
                    case .ready: Label("Ready", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                    case .failed(let message):
                        VStack(alignment: .leading, spacing: 8) {
                            Label("Failed to load", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                            Text(message).font(.caption).foregroundStyle(.secondary)
                            Button("Retry") { controller.loadEngine() }
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { controller.inputs.refresh() }
    }
}

/// Lists connected microphones, shows which one is live, and lets you pick one.
private struct MicrophoneSection: View {
    @Environment(RecordingController.self) private var controller

    var body: some View {
        let inputs = controller.inputs
        let selected = controller.selectedInputID
        let selectedDevice = inputs.devices.first { $0.id == selected }

        Section {
            Label {
                Text(statusText(selected: selected, selectedDevice: selectedDevice, active: inputs.activeInputName))
            } icon: {
                Image(systemName: controller.isRecording ? "mic.fill" : "mic")
                    .foregroundStyle(controller.isRecording ? .red : .secondary)
            }

            MicOption(
                title: "Automatic",
                subtitle: "Let iOS choose (usually the newest headset, else the built-in mic)",
                selected: selected == nil
            ) { controller.selectedInputID = nil }

            ForEach(inputs.devices) { device in
                MicOption(
                    title: device.name,
                    subtitle: device.kind + (device.isBluetooth ? " · uses the headset's call audio" : ""),
                    selected: device.id == selected
                ) { controller.selectedInputID = device.id }
            }

            if selected != nil, selectedDevice == nil {
                MicOption(
                    title: "Previously chosen mic",
                    subtitle: "Not connected — reconnect it and VoicePad switches back automatically",
                    selected: true,
                    enabled: false
                ) {}
            }
            if inputs.devices.isEmpty {
                Text("No microphones detected.").font(.caption).foregroundStyle(.red)
            }
        } header: {
            Text("Microphone")
        }
    }

    private func statusText(selected: String?, selectedDevice: InputDevice?, active: String?) -> String {
        if controller.isRecording { return "Recording from: \(active ?? "detecting…")" }
        if let selectedDevice { return "Will record from: \(selectedDevice.name)" }
        if selected != nil { return "Chosen mic isn't connected — the default will be used" }
        return "iOS picks the microphone"
    }
}

private struct MicOption: View {
    let title: String
    let subtitle: String
    let selected: Bool
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(.primary)
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                }
            }
        }
        .disabled(!enabled)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
