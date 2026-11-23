import AVFoundation
import Observation
import SwiftUI
import UIKit

/// Plays one recording at a time.
@MainActor
@Observable
final class AudioPlayer: NSObject, AVAudioPlayerDelegate {
    private(set) var playingID: Note.ID?
    @ObservationIgnored private var player: AVAudioPlayer?

    /// Starts `id`, or stops it if it is already playing. Returns an error message on failure.
    @discardableResult
    func toggle(_ id: Note.ID, url: URL?) -> String? {
        let wasPlaying = playingID == id
        stop()
        if wasPlaying { return nil }
        guard let url, FileManager.default.fileExists(atPath: url.path) else { return "Audio file is missing." }
        do {
            // Recording keeps the session active; otherwise bring it up for playback.
            if !AVAudioSession.sharedInstance().isOtherAudioPlaying {
                try? AVAudioSession.sharedInstance().setActive(true)
            }
            let player = try AVAudioPlayer(contentsOf: url)
            player.delegate = self
            player.play()
            self.player = player
            playingID = id
            return nil
        } catch {
            stop()
            return "Couldn't play this recording."
        }
    }

    func stop() {
        player?.stop()
        player = nil
        playingID = nil
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.stop() }
    }
}

/// What gets handed to the share sheet: the WAV plus its transcript.
struct ShareItem: Identifiable {
    let id = UUID()
    let items: [Any]
}

/// Opens the system share sheet (save to Files, AirDrop, send…).
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
