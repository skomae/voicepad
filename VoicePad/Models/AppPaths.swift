import Foundation

/// Where VoicePad keeps its notes, recordings and logs. On iPhone that's the
/// app's Documents folder (visible in Files); on the Mac it's
/// ~/Library/Application Support/VoicePad, so nothing lands loose in the
/// user's Documents folder (and macOS never asks for access to it).
enum AppPaths {
    static var data: URL {
        #if os(macOS)
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("VoicePad", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
        #else
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        #endif
    }
}
