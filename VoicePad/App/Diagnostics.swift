import Foundation

/// A small rolling log in Documents/diagnostics.log (audio route and format
/// when recording starts), to tell why a recording came out silent.
enum Diagnostics {
    static func log(_ line: String) {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("diagnostics.log")
        let stamp = ISO8601DateFormatter().string(from: .now)
        var text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        text += "\(stamp) \(line)\n"
        // Keep the last ~200 lines.
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        if lines.count > 200 { text = lines.suffix(200).joined(separator: "\n") }
        try? text.write(to: url, atomically: true, encoding: .utf8)
    }
}
