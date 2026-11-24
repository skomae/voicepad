import SwiftUI

@main
struct VoicePadApp: App {
    @State private var store: NoteStore
    @State private var controller: RecordingController
    @State private var conversation: ConversationController
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let store = NoteStore()
        _store = State(initialValue: store)
        let controller = RecordingController(store: store)
        _controller = State(initialValue: controller)
        _conversation = State(initialValue: ConversationController(notes: controller))
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(store)
                .environment(controller)
                .environment(conversation)
                .preferredColorScheme(controller.themeMode.colorScheme)
                .environment(\.pureBlack, controller.themeMode == .black)
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            #if DEBUG
            // For screenshots and UI checks: `-VoicePadOpenConversation YES`.
            if phase == .active, UserDefaults.standard.bool(forKey: "VoicePadOpenConversation") { conversation.open() }
            #endif
            // Start recording the moment the app is opened.
            if phase == .active {
                Task { await controller.appBecameActive() }
            }
        }
    }
}
