import SwiftUI

@main
struct VoicePadApp: App {
    @State private var store: NoteStore
    @State private var controller: RecordingController
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let store = NoteStore()
        _store = State(initialValue: store)
        _controller = State(initialValue: RecordingController(store: store))
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(store)
                .environment(controller)
                .preferredColorScheme(controller.themeMode.colorScheme)
                .environment(\.pureBlack, controller.themeMode == .black)
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            // Start recording the moment the app is opened.
            if phase == .active {
                Task { await controller.appBecameActive() }
            }
        }
    }
}
