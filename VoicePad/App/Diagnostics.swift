import Foundation

/// A small rolling log in Documents/diagnostics.log (audio route and format
/// when recording starts), to tell why a recording came out silent.
enum Diagnostics {
    static func log(_ line: String) {
        let url = AppPaths.data.appendingPathComponent("diagnostics.log")
        let stamp = ISO8601DateFormatter().string(from: .now)
        var text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        text += "\(stamp) \(line)\n"
        // Keep the last ~200 lines.
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        if lines.count > 200 { text = lines.suffix(200).joined(separator: "\n") }
        try? text.write(to: url, atomically: true, encoding: .utf8)
    }
}

/// Test switches read from Documents/debug.json (copied onto a test phone), to
/// narrow down device-only problems without a new build each time.
enum DebugFlags {
    private static let flags: [String: Bool] = {
        let url = AppPaths.data.appendingPathComponent("debug.json")
        guard let data = try? Data(contentsOf: url) else { return [:] }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Bool] ?? [:]
    }()

    static func on(_ name: String) -> Bool { flags[name] ?? false }
}
