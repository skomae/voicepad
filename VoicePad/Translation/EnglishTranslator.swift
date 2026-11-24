import Foundation
import NaturalLanguage
import Observation
import SwiftUI
#if canImport(Translation)
import Translation
#endif

enum TranslationError: LocalizedError {
    case unsupported(String)
    case needsNewerIOS
    case notReady
    case unavailableOnDevice

    var errorDescription: String? {
        switch self {
        case .unsupported(let name): return "Translation from \(name) isn't supported on-device."
        case .needsNewerIOS: return "On-device translation needs iOS 18 or later."
        case .notReady: return "Translation isn't available right now. Open VoicePad to retry."
        case .unavailableOnDevice: return "On-device translation isn't available on this device."
        }
    }
}

/// Translation was needed but failed. Carries what was worked out offline
/// (language and romanisation) so it can still be shown.
struct TranslationFailure: LocalizedError {
    let language: String?
    let romanization: String?
    let underlying: Error

    var errorDescription: String? { underlying.localizedDescription }
}

/// On-device translation to English with Apple's Translation framework, plus
/// romanisation. Each language pack downloads the first time it's needed
/// (iOS asks first); after that, translation works offline.
///
/// Apple only hands out a `TranslationSession` inside a view's
/// `.translationTask`, so a hidden `TranslationHost` view in the window runs
/// each request this class queues.
@MainActor
@Observable
final class EnglishTranslator {
    struct Result {
        var language: String?
        var english: String?
        var romanization: String?
    }

    /// Drives the hidden `.translationTask`; changing it starts a session.
    /// (A `TranslationSession.Configuration`, typed loosely for iOS 17.)
    var configuration: Any?
    @ObservationIgnored var hostAttached = false
    @ObservationIgnored private var job: (text: String, continuation: CheckedContinuation<String, Error>)?
    /// One request at a time: there is a single hidden session host, and live
    /// translation and saved notes both use it.
    @ObservationIgnored private var busy = false
    @ObservationIgnored private var waiting: [CheckedContinuation<Void, Never>] = []

    /// Translates `text` to English. `modelLanguage` is what the speech model
    /// reported, if anything. Returns no English when no translation is
    /// needed. Throws if translation was needed but failed.
    func translate(_ text: String, modelLanguage: String?) async throws -> Result {
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        let guess = recognizer.dominantLanguage?.rawValue
        let decided = LanguageTags.decide(modelLanguage: modelLanguage, textGuess: guess, text: text)
        guard let language = decided, !LanguageTags.isEnglish(language) else { return Result(language: decided) }
        let romanization = Romanizer.romanize(language: language, text: text)
        // Written Chinese: simplified unless the text looks traditional;
        // Cantonese is written with traditional characters.
        let source: String = switch language {
        case "zh": guess == "zh-Hant" ? "zh-Hant" : "zh-Hans"
        case "yue": "zh-Hant"
        default: language
        }
        do {
            let english = try await translateText(text, from: source, languageName: LanguageTags.displayName(language))
            return Result(language: language, english: english, romanization: romanization)
        } catch {
            throw TranslationFailure(language: language, romanization: romanization, underlying: error)
        }
    }

    private func translateText(_ text: String, from source: String, languageName: String) async throws -> String {
        #if canImport(Translation)
        guard #available(iOS 18.0, *) else { throw TranslationError.needsNewerIOS }
        let from = Locale.Language(identifier: source)
        let to = Locale.Language(identifier: "en")
        let availability = LanguageAvailability()
        let status = await availability.status(from: from, to: to)
        if status == .unsupported {
            // No languages at all (e.g. the Simulator): don't blame the language.
            if await availability.supportedLanguages.isEmpty { throw TranslationError.unavailableOnDevice }
            throw TranslationError.unsupported(languageName)
        }
        await acquire()
        defer { release() }
        try Task.checkCancellation()
        guard hostAttached, job == nil else { throw TranslationError.notReady }
        return try await withCheckedThrowingContinuation { continuation in
            job = (text, continuation)
            if var current = configuration as? TranslationSession.Configuration,
               current.source == from, current.target == to {
                current.invalidate() // same pair: run the task again
                configuration = current
            } else {
                configuration = TranslationSession.Configuration(source: from, target: to)
            }
        }
        #else
        throw TranslationError.needsNewerIOS
        #endif
    }

    #if canImport(Translation)
    /// Called from the host view's `.translationTask` with a live session.
    @available(iOS 18.0, *)
    func run(_ session: TranslationSession) async {
        guard let job else { return }
        self.job = nil
        do {
            // Asks the user to download the language pack the first time.
            let response = try await session.translate(job.text)
            job.continuation.resume(returning: response.targetText)
        } catch {
            job.continuation.resume(throwing: error)
        }
    }
    #endif

    private func acquire() async {
        if !busy {
            busy = true
            return
        }
        await withCheckedContinuation { waiting.append($0) }
    }

    private func release() {
        if waiting.isEmpty {
            busy = false
        } else {
            waiting.removeFirst().resume() // hand over; stays busy
        }
    }

    /// The host went away (e.g. scene closed) with a request outstanding.
    func detach() {
        hostAttached = false
        job?.continuation.resume(throwing: TranslationError.notReady)
        job = nil
    }
}

/// Invisible view that gives `EnglishTranslator` its translation sessions.
struct TranslationHost: View {
    let translator: EnglishTranslator

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .modifier(TranslationTaskModifier(translator: translator))
            .onAppear { translator.hostAttached = true }
            .onDisappear { translator.detach() }
            .accessibilityHidden(true)
    }
}

private struct TranslationTaskModifier: ViewModifier {
    let translator: EnglishTranslator

    func body(content: Content) -> some View {
        #if canImport(Translation)
        if #available(iOS 18.0, *) {
            content.translationTask(translator.configuration as? TranslationSession.Configuration) { session in
                await translator.run(session)
            }
        } else {
            content
        }
        #else
        content
        #endif
    }
}
