import SwiftUI

private let speakerColors: [Color] = [
    Color(red: 0.12, green: 0.53, blue: 0.90), Color(red: 0.96, green: 0.32, blue: 0.12), Color(red: 0.26, green: 0.63, blue: 0.28),
    Color(red: 0.56, green: 0.14, blue: 0.67), Color(red: 0.0, green: 0.54, blue: 0.48), Color(red: 0.85, green: 0.11, blue: 0.38),
]

private func speakerColor(_ ref: SpeakerRef?) -> Color { ref.map { speakerColors[$0.index % speakerColors.count] } ?? .gray }

/// A turn's colour; speech still in progress has no speaker yet, so it goes by its language.
private func stageColor(_ turn: Turn?, myLanguage: String) -> Color {
    guard let turn else { return .gray }
    if let speaker = turn.speaker { return speakerColor(speaker) }
    return speakerColors[turn.language == myLanguage ? 0 : 1]
}

private enum Side: Identifiable { case mine, theirs; var id: Self { self } }

/// Conversation mode: live two-way translation in big text.
struct ConversationView: View {
    @Environment(ConversationController.self) private var conversation
    @Environment(RecordingController.self) private var notes
    @State private var showSettings = false
    @State private var picking: Side?

    var body: some View {
        let settings = conversation.settings
        TimelineView(.periodic(from: .now, by: 0.08)) { _ in
            // Re-read the input level ~12 times a second for the glow and meters.
            let level = conversation.listening ? CGFloat(conversation.level) : 0
            VStack(spacing: 0) {
                switch settings.layout {
                case .faceToFace:
                    StagePane(language: settings.theirLanguage, level: level).rotationEffect(.degrees(180))
                    controls(level)
                    StagePane(language: settings.myLanguage, level: level)
                case .faceToFaceSideways:
                    HStack(spacing: 0) {
                        StagePane(language: settings.myLanguage, level: level)
                        Divider()
                        StagePane(language: settings.theirLanguage, level: level).rotationEffect(.degrees(180))
                    }
                    controls(level)
                case .sideBySide:
                    HStack(spacing: 0) {
                        StagePane(language: settings.myLanguage, level: level)
                        Divider()
                        StagePane(language: settings.theirLanguage, level: level)
                    }
                    controls(level)
                case .transcript:
                    TranscriptPane()
                    controls(level)
                }
            }
        }
        .background(Color(.systemBackground))
        .background { TranslationHost(translator: notes.translator) }
        // Nobody should have to tap the screen to keep it awake mid-conversation.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .sheet(item: $picking) { side in
            LanguagePicker(
                title: side == .mine ? "You speak" : "They speak",
                selected: side == .mine ? settings.myLanguage : settings.theirLanguage
            ) { code in
                if side == .mine {
                    conversation.setLanguages(mine: code, theirs: settings.theirLanguage)
                } else {
                    conversation.setLanguages(mine: settings.myLanguage, theirs: code)
                }
                picking = nil
            }
        }
        .sheet(isPresented: $showSettings) { ConversationSettings(onPick: { picking = $0 }) }
    }

    private func controls(_ level: CGFloat) -> some View {
        ControlStrip(level: level, onPick: { picking = $0 }, onSettings: { showSettings = true })
    }
}

/// Close, the two languages, the microphone, and what's still loading.
private struct ControlStrip: View {
    let level: CGFloat
    let onPick: (Side) -> Void
    let onSettings: () -> Void
    @Environment(ConversationController.self) private var conversation
    @Environment(RecordingController.self) private var notes

    var body: some View {
        let settings = conversation.settings
        VStack(spacing: 6) {
            StatusLines()
            HStack(spacing: 4) {
                Button(LanguageNames.native(settings.myLanguage)) { onPick(.mine) }
                    .font(.headline).lineLimit(1)
                Button { conversation.swapLanguages() } label: { Image(systemName: "arrow.left.arrow.right") }
                    .accessibilityLabel("Swap languages")
                Button(LanguageNames.native(settings.theirLanguage)) { onPick(.theirs) }
                    .font(.headline).lineLimit(1)
            }
            HStack {
                Button { conversation.close() } label: { Image(systemName: "xmark").frame(width: 40, height: 40) }
                    .accessibilityLabel("Leave conversation mode")
                Button { conversation.cycleLayout() } label: { Image(systemName: settings.layout.systemImage).frame(width: 40, height: 40) }
                    .accessibilityLabel("Layout: \(settings.layout.label). Tap for the next one.")
                Spacer()
                MicButton(level: level)
                Spacer()
                Button { conversation.setAutoSpeak(!settings.autoSpeak) } label: {
                    Image(systemName: settings.autoSpeak ? "speaker.wave.2.fill" : "speaker.slash").frame(width: 40, height: 40)
                }
                .tint(settings.autoSpeak ? .accentColor : .secondary)
                .accessibilityLabel(settings.autoSpeak ? "Reading translations aloud. Tap to stop." : "Not reading aloud. Tap to read translations aloud.")
                Button(action: onSettings) { Image(systemName: "gearshape").frame(width: 40, height: 40) }
                    .accessibilityLabel("Conversation settings")
            }
            .font(.title3)
            .padding(.horizontal, 8)
        }
        .padding(.vertical, 6)
        .background(.bar)
    }
}

private struct MicButton: View {
    let level: CGFloat
    @Environment(ConversationController.self) private var conversation

    var body: some View {
        let listening = conversation.listening
        Button { conversation.toggleListening() } label: {
            ZStack {
                if listening {
                    Circle().fill(Color.recordingRed.opacity(0.22)).frame(width: 60, height: 60).scaleEffect(0.8 + level * 0.4)
                    Circle().fill(Color.recordingRed).frame(width: 48, height: 48)
                    Image(systemName: "mic.fill").foregroundStyle(.white)
                    HoldRing(hold: conversation.hold, refilled: conversation.hearing || conversation.live != nil,
                             color: stageColor(conversation.stage.turn, myLanguage: conversation.settings.myLanguage))
                        .frame(width: 60, height: 60)
                } else {
                    Circle().fill(Color(.secondarySystemBackground)).frame(width: 48, height: 48)
                    Image(systemName: "mic.slash").foregroundStyle(.secondary)
                }
            }
            .frame(width: 60, height: 60)
        }
        .accessibilityLabel(listening ? "Pause listening" : "Start listening")
    }
}

/// Drains while a turn is held open for a thinking pause; full again when the speaker goes on.
private struct HoldRing: View {
    let hold: ConversationController.Hold?
    let refilled: Bool
    let color: Color

    var body: some View {
        if let hold {
            TimelineView(.animation) { context in
                let fraction = refilled ? 1 : max(0, min(1, hold.endsAt.timeIntervalSince(context.date) / max(hold.total, 0.1)))
                ZStack {
                    Circle().stroke(color.opacity(0.22), lineWidth: 3)
                    Circle().trim(from: 0, to: fraction).stroke(color, style: StrokeStyle(lineWidth: 3, lineCap: .round)).rotationEffect(.degrees(-90))
                }
            }
        }
    }
}

/// What's loading or wrong, most important first; each line says what to do about it.
private struct StatusLines: View {
    @Environment(ConversationController.self) private var conversation
    @Environment(RecordingController.self) private var notes

    var body: some View {
        let settings = conversation.settings
        VStack(spacing: 2) {
            ForEach(Array(lines(settings).prefix(3).enumerated()), id: \.offset) { _, line in line }
        }
        .padding(.horizontal, 16)
    }

    private func lines(_ settings: ConversationController.Settings) -> [AnyView] {
        var out: [AnyView] = []
        if let message = conversation.message {
            out.append(line(message, error: true, action: "OK") { conversation.message = nil })
        }
        switch notes.engineStatus {
        case .loading(let p):
            out.append(AnyView(ModelProgressView(progress: p, compact: true)))
        case .failed(let message):
            out.append(line("Speech model failed: \(message)", error: true, action: "Retry") { notes.loadEngine() })
        default: break
        }
        if !conversation.activeEngineUnderstandsBoth {
            let engine = notes.activeEngine
            let recommended = conversation.recommendedEngine
            let missing = [settings.myLanguage, settings.theirLanguage].filter { !engine.understands($0) }.map(LanguageNames.english)
            let download = notes.installedEngines.contains(recommended) ? "" : " \(recommended.shortName) is a \(recommended.approximateSize) download."
            out.append(line("\(engine.shortName) doesn't understand \(missing.joined(separator: " or ")).\(download)", error: true,
                            action: "Use \(recommended.shortName)") { conversation.useRecommendedEngine() })
        }
        switch conversation.translation {
        case .preparing: out.append(line("Getting translation packs (once per language)…"))
        case .failed(let message): out.append(line("Translation unavailable: \(message)", error: true, action: "Retry") { conversation.retryModels() })
        default: break
        }
        if settings.identifyVoices {
            switch conversation.voiceModel {
            case .preparing(let p): out.append(line(p.map { "Downloading voice model to tell speakers apart… \(Int($0 * 100))%" } ?? "Loading voice model…"))
            case .failed(let message): out.append(line("Can't tell voices apart: \(message)", action: "Retry") { conversation.retryModels() })
            default: break
            }
        }
        if !conversation.listening { out.append(line("Paused — tap the microphone to listen.")) }
        return out
    }

    private func line(_ text: String, error: Bool = false, action: String? = nil, perform: @escaping () -> Void = {}) -> AnyView {
        AnyView(HStack {
            Text(text).font(.footnote).foregroundStyle(error ? .red : .secondary).lineLimit(2)
            Spacer(minLength: 4)
            if let action { Button(action, action: perform).font(.footnote.weight(.semibold)) }
        })
    }
}

/// The conversation in one language as a stage: the newest thought big at
/// the bottom, lit in its speaker's colour, everything older small and dim
/// above it (scroll up to read back).
private struct StagePane: View {
    let language: String
    let level: CGFloat
    @Environment(ConversationController.self) private var conversation
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let stage = conversation.stage
        let settings = conversation.settings
        let motion = !reduceMotion || settings.forceAnimations
        let color = stageColor(stage.turn, myLanguage: settings.myLanguage)
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Text(LanguageNames.native(language)).font(.subheadline.weight(.semibold)).foregroundStyle(Color.accentColor)
                if conversation.hearing { HearingBars(level: level) }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(stage.history) { HistoryTurn(turn: $0, language: language) }
                        if let turn = stage.turn {
                            Hero(turn: turn, phase: stage.phase, language: language, color: color, motion: motion).id("hero")
                        } else if conversation.listening {
                            Text("…").font(.system(size: CGFloat(settings.textSize))).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .defaultScrollAnchor(.bottom)
                .onChange(of: stage.turn?.text) { _, _ in proxy.scrollTo("hero", anchor: .bottom) }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(alignment: .top) { Aura(color: color, phase: stage.phase, level: level, motion: motion) }
    }
}

/// The newest thought as `language`'s reader sees it.
private struct Hero: View {
    let turn: Turn
    let phase: Stage.Phase
    let language: String
    let color: Color
    let motion: Bool
    @Environment(ConversationController.self) private var conversation

    var body: some View {
        let size = CGFloat(conversation.settings.textSize)
        let original = turn.language == language
        HStack(alignment: .top, spacing: 10) {
            RoundedRectangle(cornerRadius: 3).fill(color).frame(width: 5)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(ConversationController.speakerLabel(turn) + phaseLabel).font(.subheadline.weight(.semibold)).foregroundStyle(color)
                    if motion, phase == .working { ProgressView().controlSize(.small) }
                    if motion, phase == .holding { ThinkingDots(color: color) }
                    if phase != .speaking, phase != .holding { SpeakButton(turn: turn, language: language, tint: color) }
                }
                if original {
                    SpokenText(turn: turn, phase: phase, size: size)
                } else {
                    TranslatedText(turn: turn, phase: phase, language: language, size: size, color: color, motion: motion)
                    Text(turn.text + (turn.translationFailed ? "  (not translated)" : ""))
                        .font(.system(size: max(13, size * 0.42)))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private var phaseLabel: String {
        switch phase {
        case .speaking: return " · speaking…"
        case .holding: return " · thinking…"
        case .working: return " · translating…"
        default: return ""
        }
    }
}

/// The speaker's own words: while they talk, the newest few are the brightest.
private struct SpokenText: View {
    let turn: Turn
    let phase: Stage.Phase
    let size: CGFloat

    var body: some View {
        let text = turn.text
        let settled: Int = {
            guard phase == .speaking else { return 0 }
            if Glyphs.isDense(turn.language) { return max(0, text.count - 5) }
            // Everything before the last three words.
            let words = text.split(separator: " ", omittingEmptySubsequences: false)
            return words.count <= 3 ? 0 : words.dropLast(3).joined(separator: " ").count + 1
        }()
        (Text(String(text.prefix(settled))).foregroundStyle(.primary.opacity(0.62)) + Text(String(text.dropFirst(settled))))
            .font(.system(size: size, weight: .semibold))
    }
}

/// The translation the listener reads. While it's on its way, stand-in
/// characters in the reader's own script flicker where the words will be;
/// when it arrives, they resolve into it from the start.
private struct TranslatedText: View {
    let turn: Turn
    let phase: Stage.Phase
    let language: String
    let size: CGFloat
    let color: Color
    let motion: Bool
    @State private var revealStart: Date?
    @State private var wasPending: Bool?

    var body: some View {
        let pending = turn.pending && !turn.translationFailed
        TimelineView(.periodic(from: .now, by: motion ? 0.07 : 3600)) { context in
            let (shown, tail, reveal) = parts(pending: pending, now: context.date)
            var line = Text(shown).foregroundStyle(pending ? Color.primary.opacity(0.75) : (reveal < 1 ? color : .primary))
            if !tail.isEmpty {
                let space = !shown.isEmpty && !Glyphs.isDense(language) ? " " : ""
                line = line + Text(space + tail).foregroundStyle(color.opacity(phase == .speaking ? 0.4 : 0.85))
            }
            return line.font(.system(size: size, weight: .semibold))
        }
        .onChange(of: pending, initial: true) { old, new in
            if wasPending == true, !new, motion { revealStart = .now }
            wasPending = new
        }
    }

    private func parts(pending: Bool, now: Date) -> (String, String, Double) {
        if pending {
            let shown = turn.translation ?? ""
            let expected = Glyphs.estimateLength(turn.text, from: turn.language, to: language)
            // A held turn isn't being worked on, so nothing flickers.
            let tail = phase == .holding ? 0 : min(28, max(3, expected - shown.count))
            return (shown, scramble(tail), 1)
        }
        let full = turn.translation ?? turn.text
        let reveal = revealStart.map { min(1, now.timeIntervalSince($0) / 0.55) } ?? 1
        let k = Int(reveal * Double(full.count))
        return (String(full.prefix(k)), scramble(min(28, full.count - k)), reveal)
    }

    private func scramble(_ count: Int) -> String {
        guard count > 0 else { return "" }
        guard motion else { return "…" }
        let pool = Glyphs.pool(language)
        return String((0..<count).map { Glyphs.isGap(language, $0) ? " " : pool.randomElement()! })
    }
}

/// An older turn: small and dim, so the newest one owns the stage.
private struct HistoryTurn: View {
    let turn: Turn
    let language: String
    @Environment(ConversationController.self) private var conversation

    var body: some View {
        let size = max(15, CGFloat(conversation.settings.textSize) * 0.5)
        HStack(alignment: .top, spacing: 10) {
            RoundedRectangle(cornerRadius: 3).fill(speakerColor(turn.speaker).opacity(0.6)).frame(width: 5)
            Text(turn.text(in: language) ?? turn.text)
                .font(.system(size: size))
                .foregroundStyle(.primary.opacity(0.6))
                .lineLimit(6)
            Spacer(minLength: 0)
            SpeakButton(turn: turn, language: language, tint: speakerColor(turn.speaker).opacity(0.8))
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// Speaker icon: says the turn in `language`; while it's being made, three
/// breathing bars (nothing is audible yet); while it plays, a stop icon.
private struct SpeakButton: View {
    let turn: Turn
    let language: String
    let tint: Color
    @Environment(ConversationController.self) private var conversation

    var body: some View {
        let speaking = conversation.speech.activeID == ConversationController.speechID(turn.id, language)
        let preparing = speaking && conversation.speech.generating
        Button { conversation.speak(turn, in: language) } label: {
            if preparing {
                MakingVoiceBars(tint: tint)
            } else {
                Image(systemName: speaking ? "stop.circle.fill" : "speaker.wave.2")
            }
        }
        .tint(tint)
        .font(.callout)
        .accessibilityLabel(speaking ? (preparing ? "Generating the voice, tap to stop" : "Stop reading") : "Read aloud")
    }
}

/// A glow in the speaker's colour from the pane's top edge: it follows their
/// voice while they talk, breathes slowly during a held pause, and pulses
/// faintly while the models work.
private struct Aura: View {
    let color: Color
    let phase: Stage.Phase
    let level: CGFloat
    let motion: Bool

    var body: some View {
        TimelineView(.animation(paused: !motion)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            let breath: CGFloat = {
                switch phase {
                case .holding: return 0.5 + 0.5 * sin(t * 2 * .pi / 2.4)
                case .working: return 0.5 + 0.5 * sin(t * 2 * .pi / 0.7)
                default: return 0
                }
            }()
            let strength: CGFloat = switch phase {
            case .speaking: 0.15 + level * 0.5
            case .holding: 0.12 + breath * 0.15
            case .working: 0.1 + breath * 0.08
            case .landed: 0.08
            case .idle: 0
            }
            LinearGradient(colors: [color.opacity(strength), .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 160)
                .allowsHitTesting(false)
        }
    }
}

private struct HearingBars: View {
    let level: CGFloat

    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<4, id: \.self) { i in
                Capsule().fill(Color.accentColor).frame(width: 3, height: 4 + level * CGFloat([8, 14, 10, 6][i]))
            }
        }
        .frame(height: 18)
    }
}

private struct ThinkingDots: View {
    let color: Color

    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            HStack(spacing: 4) {
                ForEach(0..<3, id: \.self) { i in
                    Circle().fill(color).frame(width: 6, height: 6).opacity(0.3 + 0.7 * max(0, sin(t * 4 - Double(i) * 0.7)))
                }
            }
        }
    }
}

/// One list with each turn's original, its romanisation and its translation.
private struct TranscriptPane: View {
    @Environment(ConversationController.self) private var conversation

    var body: some View {
        let size = max(15, CGFloat(conversation.settings.textSize) * 0.55)
        let all = conversation.turns + [conversation.live].compactMap { $0 }
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ForEach(all) { turn in
                    HStack(alignment: .top, spacing: 10) {
                        RoundedRectangle(cornerRadius: 3).fill(speakerColor(turn.speaker)).frame(width: 5)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(ConversationController.speakerLabel(turn)).font(.caption.weight(.semibold)).foregroundStyle(speakerColor(turn.speaker))
                            Text(turn.text).font(.system(size: size, weight: .semibold))
                            if let r = turn.romanization { Text(r).font(.system(size: size * 0.75)).italic().foregroundStyle(.secondary) }
                            if let t = turn.translation {
                                Text("→ " + t).font(.system(size: size))
                                if let r = turn.translationRomanization { Text(r).font(.system(size: size * 0.75)).italic().foregroundStyle(.secondary) }
                            } else if turn.pending {
                                Text(turn.held ? "thinking…" : "translating…").font(.caption).italic().foregroundStyle(.secondary)
                            }
                        }
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .defaultScrollAnchor(.bottom)
    }
}

/// Every language offered, common ones first.
private struct LanguagePicker: View {
    let title: String
    let selected: String
    let onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    private static let others = ["bg", "hr", "cs", "da", "et", "fi", "el", "he", "hu", "lv", "lt", "ms", "nb", "ro", "sk", "sl", "sv", "fa", "bn", "ta", "yue"]
        .filter { !LanguageNames.common.contains($0) }

    var body: some View {
        NavigationStack {
            List {
                Section("Common") { rows(LanguageNames.common) }
                Section("More") { rows(Self.others) }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }

    private func rows(_ codes: [String]) -> some View {
        ForEach(codes, id: \.self) { code in
            Button { onPick(code) } label: {
                HStack {
                    VStack(alignment: .leading) {
                        Text(LanguageNames.native(code)).foregroundStyle(.primary)
                        Text(LanguageNames.english(code)).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if code == selected { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                }
            }
        }
    }
}

private struct ConversationSettings: View {
    let onPick: (Side) -> Void
    @Environment(ConversationController.self) private var conversation
    @Environment(RecordingController.self) private var notes
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let s = conversation.settings
        NavigationStack {
            Form {
                Section("Languages") {
                    Button { dismiss(); onPick(.mine) } label: { LabeledContent("You speak", value: LanguageNames.native(s.myLanguage)) }
                    Button { dismiss(); onPick(.theirs) } label: { LabeledContent("They speak", value: LanguageNames.native(s.theirLanguage)) }
                }
                Section {
                    LabeledContent("Speech model", value: notes.activeEngine.shortName)
                    if conversation.recommendedEngine != notes.activeEngine {
                        Button("Use \(conversation.recommendedEngine.shortName) (best for these two languages)") { conversation.useRecommendedEngine() }
                    }
                } footer: {
                    Text("Conversation mode uses the same model as notes, so switching it here switches it there too.")
                }
                Section("Screen") {
                    Picker("Layout", selection: Binding(get: { s.layout }, set: { conversation.setLayout($0) })) {
                        ForEach(ConversationLayout.allCases) { Label($0.label, systemImage: $0.systemImage).tag($0) }
                    }
                    VStack(alignment: .leading) {
                        Text("Text size: \(s.textSize)")
                        Slider(value: Binding(get: { Double(s.textSize) }, set: { conversation.setTextSize(Int($0)) }), in: 18...96, step: 2)
                    }
                    Toggle(isOn: Binding(get: { s.forceAnimations }, set: { conversation.setForceAnimations($0) })) {
                        Text("Animate even with Reduce Motion on")
                        Text(reduceMotion ? "Reduce Motion is on right now." : "Reduce Motion is off right now.")
                    }
                }
                Section {
                    Picker("Thinking pause", selection: Binding(get: { s.thinkingPause }, set: { conversation.setThinkingPause($0) })) {
                        Text("Off").tag(0)
                        Text("3 s").tag(3)
                        Text("5 s").tag(5)
                        Text("8 s").tag(8)
                    }
                } footer: {
                    Text("A sentence that stops mid-way (\"…and\", 然后) is held open this long before it's translated, in case the speaker goes on.")
                }
                Section {
                    Toggle(isOn: Binding(get: { s.identifyVoices }, set: { conversation.setIdentifyVoices($0) })) {
                        Text("Tell speakers apart by voice")
                        Text("Downloads a small voice model once. Otherwise turns are labelled by language.")
                    }
                    Toggle("Read translations aloud", isOn: Binding(get: { s.autoSpeak }, set: { conversation.setAutoSpeak($0) }))
                } footer: {
                    Text("Read-aloud uses the phone's own voices. The microphone ignores the phone while it speaks.")
                }
                Section {
                    Button("Clear the screen") { conversation.clearTurns() }
                } footer: {
                    Text("Every listening session is also saved as a note, with its audio.")
                }
            }
            .navigationTitle("Conversation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
