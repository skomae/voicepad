import UIKit
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
            if phase == .active, DebugFlags.on("duoLog") {
                let screens = UIScreen.screens.map { "\($0.bounds.size.width)x\($0.bounds.size.height) @\($0.scale)" }
                let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
                let size = scene?.coordinateSpace.bounds.size ?? .zero
                let insets = scene?.windows.first?.safeAreaInsets ?? .zero
                Diagnostics.log("duo: screens=\(screens.count) [\(screens.joined(separator: ", "))] scene=\(Int(size.width))x\(Int(size.height)) insets=\(Int(insets.top)),\(Int(insets.bottom)),\(Int(insets.left)),\(Int(insets.right))")
            }
            #endif
            // Start recording the moment the app is opened.
            if phase == .active {
                Task { await controller.appBecameActive() }
            }
        }
    }
}
