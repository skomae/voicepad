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
                }

                Section {
                    Picker("Model", selection: $controller.engineChoice) {
                        ForEach(EngineChoice.allCases) { choice in
                            VStack(alignment: .leading) {
                                Text(choice.displayName)
                                Text(choice.approximateSize).font(.caption).foregroundStyle(.secondary)
                            }
                            .tag(choice)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("Transcription model")
                } footer: {
                    Text("Models run entirely on device. Each is downloaded once on first use. Recording starts immediately; text catches up once the model is ready.")
                }

                Section("Status") {
                    switch controller.engineStatus {
                    case .idle: Text("Not loaded")
                    case .loading(let p): ProgressView(value: p) { Text("Downloading / loading…") }
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
    }
}
