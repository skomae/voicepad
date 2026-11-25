import AVFoundation
import Observation

/// Says text out loud with the phone's own voices (AVSpeechSynthesizer), in
/// any language it has a voice for. Requests play one at a time: `say`
/// replaces what's playing (a tap), `enqueue` waits its turn (reading
/// translations aloud automatically). While it plays, `playing` is true so
/// conversation mode can ignore its own voice coming back through the mic.
///
/// (Android adds downloadable local voices on top of the phone's; iOS voices
/// already run on the device, so those aren't needed here.)
@MainActor
@Observable
final class SpeechOutput: NSObject, AVSpeechSynthesizerDelegate {
    /// Id of the request being played, for the UI's speaker icons.
    private(set) var activeID: String?
    /// True while the voice is being prepared: nothing is audible yet, so the
    /// UI shows that it's working (the tap's speaker icon becomes bars).
    private(set) var generating = false
    /// True while sound is coming out (the microphone should ignore it).
    private(set) var playing = false {
        didSet { if playing != oldValue { onPlayingChanged?(playing) } }
    }
    /// Told when sound starts or stops coming out.
    @ObservationIgnored var onPlayingChanged: ((Bool) -> Void)?

    @ObservationIgnored private let synthesizer = AVSpeechSynthesizer()
    @ObservationIgnored private var queue: [(id: String, text: String, language: String)] = []

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Says `text`, replacing whatever is playing; saying the same `id` again stops it.
    func say(_ id: String, _ text: String, language: String) {
        let again = activeID == id
        stop()
        if !again { start(id, text, language) }
    }

    /// Says `text` after whatever is playing or queued.
    func enqueue(_ id: String, _ text: String, language: String) {
        if activeID == nil { start(id, text, language) } else { queue.append((id, text, language)) }
    }

    func stop() {
        queue.removeAll()
        activeID = nil
        generating = false
        synthesizer.stopSpeaking(at: .immediate)
        playing = false
    }

    private func start(_ id: String, _ text: String, _ language: String) {
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: Self.voiceLanguage(language))
        activeID = id
        generating = true
        playing = true
        synthesizer.speak(utterance)
    }

    private func next() {
        activeID = nil
        generating = false
        playing = false
        guard !queue.isEmpty else { return }
        let item = queue.removeFirst()
        start(item.id, item.text, item.language)
    }

    /// Voice language for a code: Mandarin from mainland China, Cantonese from Hong Kong.
    static func voiceLanguage(_ code: String) -> String {
        switch code {
        case "zh": return "zh-CN"
        case "yue": return "zh-HK"
        default: return AVSpeechSynthesisVoice.speechVoices().first { $0.language.hasPrefix(code) }?.language ?? code
        }
    }

    /// First sound out: the bars give way to the stop icon again.
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        Task { @MainActor in self.generating = false }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.next() }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in if self.activeID == nil { self.playing = false } }
    }
}
