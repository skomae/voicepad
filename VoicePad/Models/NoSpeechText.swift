import Foundation

/// Decides whether a transcript is worth keeping, for audio the speech detector
/// found nothing in. Ported from Android's `NoSpeechText` (#26).
///
/// Asked to transcribe silence, the model does not return nothing — it returns
/// ".", "。 。 。", "Yeah." or "Thank you." Those are what it does when there is
/// nothing to hear, so a note only survives the second check if its audio
/// produced actual words. Pure Swift so the rule can be tested without a model.
enum NoSpeechText {
    /// What the model says when asked to transcribe silence.
    private static let fillers: Set<String> = [
        "yeah", "yes", "thank you", "thanks", "okay", "ok", "bye", "you", "your",
        "hmm", "uh", "um", "oh", "ah",
        "啊", "嗯", "哎", "哦", "呃",
    ]

    /// At least two letters or Hanzi, and not one of the fillers. Two Hanzi
    /// carry a word ("你好"); two Latin letters usually don't ("ok"), so Latin
    /// needs three.
    static func isSubstantive(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let letters = trimmed.reduce(0) { $1.isLetter ? $0 + 1 : $0 }
        let hanzi = trimmed.unicodeScalars.filter { (0x4E00...0x9FFF).contains($0.value) }.count
        if letters < 2 || (letters < 3 && hanzi == 0) { return false }
        let cleaned = trimmed.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".，。!? "))
        return !fillers.contains(cleaned)
    }
}
