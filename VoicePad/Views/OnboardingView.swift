import ActivityKit
import AVFoundation
import SwiftUI

/// First run: explains what VoicePad does and walks through each thing iOS
/// will ask about — microphone, the speech model download, translation
/// packs, and the Lock Screen recording display — before anything records,
/// so the first recording actually captures sound.
struct OnboardingView: View {
    @Environment(RecordingController.self) private var controller
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var page = Page.welcome
    @State private var mic = AVAudioApplication.shared.recordPermission
    @State private var preparing: String?
    @State private var prepared: Set<String> = []
    @State private var prepareError: String?
    @State private var liveActivitiesOn = ActivityAuthorizationInfo().areActivitiesEnabled

    enum Page: Int, CaseIterable { case welcome, microphone, model, translation, lockScreen, ready }

    var body: some View {
        VStack(spacing: 0) {
            ProgressView(value: Double(page.rawValue + 1), total: Double(Page.allCases.count))
                .padding(.horizontal, 24)
                .padding(.top, 12)
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    content
                }
                .padding(24)
                .frame(maxWidth: 560, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            footer
        }
        .background(Color(.systemBackground))
        .background { TranslationHost(translator: controller.translator) }
        .interactiveDismissDisabled()
        .onChange(of: scenePhase) { _, phase in
            // Back from Settings: pick up what changed there.
            if phase == .active {
                mic = AVAudioApplication.shared.recordPermission
                liveActivitiesOn = ActivityAuthorizationInfo().areActivitiesEnabled
            }
        }
    }

    // MARK: Pages

    @ViewBuilder
    private var content: some View {
        switch page {
        case .welcome:
            header("waveform.circle.fill", "Welcome to VoicePad")
            Text("VoicePad starts recording the moment you open it and turns your speech into text, right on your phone. Nothing is uploaded.")
            point("mic.fill", "Opens straight into recording", "So a thought is captured before it's gone.")
            point("text.bubble", "Text appears as you speak", "Every note keeps its audio and a full edit history.")
            point("translate", "English translation", "With pinyin for Mandarin.")
            Text("iOS will ask for a few permissions. The next screens explain each one before it appears, so nothing comes as a surprise.")
                .foregroundStyle(.secondary)

        case .microphone:
            header("mic.circle.fill", "Microphone")
            Text("VoicePad needs the microphone to record. Audio and text stay on this iPhone.")
            Text("When you tap **Allow microphone**, iOS asks *“Allow “VoicePad” to access your microphone?”* Choose **Allow**.")
            switch mic {
            case .granted:
                status("checkmark.circle.fill", .green, "Microphone allowed.")
            case .denied:
                status("xmark.octagon.fill", .red, "Microphone access is off, so VoicePad can't record.")
                Text("Open Settings, turn on **Microphone** for VoicePad, then come back.").foregroundStyle(.secondary)
            default:
                EmptyView()
            }

        case .model:
            header("cpu", "Speech model")
            Text("Speech is recognised on your phone by a model that downloads once. Which languages do you speak?")
            modelOption(.parakeetV3, "English and European languages", "French, Spanish, German and 21 more, switching per sentence.")
            modelOption(.autoAsian, "Mandarin, Cantonese, Japanese or Korean, and English", "Picks the right model for each sentence.")
            Text("The download (\(controller.engineChoice.approximateSize)) starts now and continues in the background. Wi-Fi is best. You can record meanwhile; the text appears once it's ready. Other models are in Settings.")
                .font(.footnote).foregroundStyle(.secondary)
            engineStatus

        case .translation:
            header("translate", "Translation")
            Toggle("Translate other languages into English", isOn: Binding(get: { controller.autoTranslate }, set: { controller.autoTranslate = $0 }))
            Text("Translation also runs on your phone. The first time a language needs translating, iOS shows a sheet asking to **download that language**. Tap **Download** (about 30 MB each, once).")
            Text("You can get packs now so it's ready offline:").foregroundStyle(.secondary)
            ForEach(packLanguages, id: \.self) { code in
                HStack {
                    Text(LanguageNames.english(code))
                    Spacer()
                    if prepared.contains(code) {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                    } else if preparing == code {
                        ProgressView()
                    } else {
                        Button("Get") { Task { await prepare(code) } }.disabled(preparing != nil)
                    }
                }
            }
            if let prepareError { Text(prepareError).font(.footnote).foregroundStyle(.red) }

        case .lockScreen:
            header("lock.iphone", "Lock Screen and Dynamic Island")
            Text("While recording, VoicePad shows a timer and the latest words on the Lock Screen and in the Dynamic Island, with **Stop** and **Split** buttons.")
            Text("The first time, iOS may ask whether to **allow Live Activities from VoicePad**. Choose **Allow** to keep these controls.")
            if liveActivitiesOn {
                status("checkmark.circle.fill", .green, "Live Activities are on.")
            } else {
                status("exclamationmark.triangle.fill", .orange, "Live Activities are off for VoicePad.")
                Button("Open Settings") { openSettings() }
            }
            Text("Recording also continues in the background; iOS shows a red indicator while the microphone is on.")
                .font(.footnote).foregroundStyle(.secondary)

        case .ready:
            header("checkmark.seal.fill", "You're set")
            Toggle("Start recording whenever I open VoicePad", isOn: Binding(get: { controller.autoRecordOnOpen }, set: { controller.autoRecordOnOpen = $0 }))
            Text("Tap ■ to stop, the scissors to start a new note, and a bubble to edit it. Settings has more models, the microphone picker and themes.")
                .foregroundStyle(.secondary)
            if mic != .granted {
                status("exclamationmark.triangle.fill", .orange, "The microphone isn't allowed yet, so nothing can be recorded.")
            }
        }
    }

    private var footer: some View {
        HStack {
            if page != .welcome {
                Button("Back") { withAnimation { page = Page(rawValue: page.rawValue - 1)! } }
            }
            Spacer()
            primaryButton
        }
        .padding(24)
        .background(.bar)
    }

    @ViewBuilder
    private var primaryButton: some View {
        switch page {
        case .microphone where mic == .undetermined:
            Button("Allow microphone") {
                Task {
                    _ = await controller.requestMicrophone()
                    mic = AVAudioApplication.shared.recordPermission
                    if mic == .granted { next() }
                }
            }
            .buttonStyle(.borderedProminent)
        case .microphone where mic == .denied:
            HStack {
                Button("Skip") { next() }
                Button("Open Settings") { openSettings() }.buttonStyle(.borderedProminent)
            }
        case .model:
            Button("Continue") {
                if case .idle = controller.engineStatus { controller.loadEngine() }
                next()
            }
            .buttonStyle(.borderedProminent)
        case .ready:
            Button(controller.autoRecordOnOpen && mic == .granted ? "Start recording" : "Done") {
                Task { await controller.finishOnboarding() }
            }
            .buttonStyle(.borderedProminent)
        default:
            Button("Continue") { next() }.buttonStyle(.borderedProminent)
        }
    }

    // MARK: Pieces

    private func next() { withAnimation { page = Page(rawValue: min(page.rawValue + 1, Page.ready.rawValue))! } }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
    }

    private var packLanguages: [String] {
        controller.engineChoice == .autoAsian ? ["zh", "ja", "ko"] : ["es", "fr", "de"]
    }

    private func prepare(_ code: String) async {
        preparing = code
        prepareError = nil
        defer { preparing = nil }
        do {
            try await controller.translator.prepare(code, "en")
            prepared.insert(code)
        } catch {
            prepareError = error.localizedDescription
        }
    }

    private func header(_ symbol: String, _ title: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: symbol).font(.system(size: 48)).foregroundStyle(Color.accentColor)
            Text(title).font(.largeTitle.bold())
        }
        .padding(.top, 12)
    }

    private func point(_ symbol: String, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol).font(.title2).foregroundStyle(Color.accentColor).frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(detail).foregroundStyle(.secondary)
            }
        }
    }

    private func status(_ symbol: String, _ color: Color, _ text: String) -> some View {
        Label { Text(text) } icon: { Image(systemName: symbol).foregroundStyle(color) }
            .font(.headline)
    }

    private func modelOption(_ choice: EngineChoice, _ title: String, _ detail: String) -> some View {
        Button { controller.engineChoice = choice } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: controller.engineChoice == choice ? "largecircle.fill.circle" : "circle")
                    .font(.title3).foregroundStyle(Color.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).foregroundStyle(.primary)
                    Text("\(detail) \(choice.approximateSize).").font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var engineStatus: some View {
        switch controller.engineStatus {
        case .loading(let p): ProgressView(value: p) { Text(p > 0 ? "Downloading… \(Int(p * 100))%" : "Preparing…") }
        case .ready: status("checkmark.circle.fill", .green, "\(controller.activeEngine.shortName) is ready.")
        case .failed(let message): status("exclamationmark.triangle.fill", .orange, message)
        case .idle: EmptyView()
        }
    }
}
