import SwiftUI

/// Opens the word breakdown from anywhere inside a screen (a note's menu, a
/// conversation turn's dictionary button).
struct OpenBreakdownKey: EnvironmentKey {
    static let defaultValue: ((BreakdownRequest) -> Void)? = nil
}

extension EnvironmentValues {
    var openBreakdown: ((BreakdownRequest) -> Void)? {
        get { self[OpenBreakdownKey.self] }
        set { self[OpenBreakdownKey.self] = newValue }
    }
}

/// Every Chinese word of a text as a card: its pinyin, the meanings its
/// translation used, and the other meanings, readings and characters ranked
/// with dictionary-estimated likelihoods. Tapping an English word also lists
/// other Chinese words for it. Ported from Android's `WordBreakdownSheet`
/// (#22/#25).
struct WordBreakdownSheet: View {
    let request: BreakdownRequest
    @Environment(ConversationController.self) private var conversation
    @Environment(\.dismiss) private var dismiss

    @State private var result: WordBreakdown.Result?
    @State private var unavailable = false
    @State private var selectedEnglish: String?
    @State private var candidates: [WordBreakdown.Candidate] = []

    private var lexicon: ChineseLexicon { .shared }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    if unavailable {
                        ContentUnavailableView("The Chinese dictionary isn't in this build",
                                               systemImage: "character.book.closed",
                                               description: Text("Rebuild the app with VoicePad/Resources/zh_dict.dat (scripts/fetch-zh-dictionary.sh)."))
                    } else if let result {
                        cards(result)
                        translationWords(result)
                        if let selectedEnglish { reverseLookup(selectedEnglish) }
                    } else {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Reading the dictionary…").foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 30)
                    }
                }
                .padding(16)
            }
            .navigationTitle("Word breakdown")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .task { await load() }
        .presentationDragIndicator(.visible)
    }

    // MARK: Pieces

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(request.chinese)
                .font(.title3.weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
            if let translation = request.translation, request.translationIsEnglish {
                Text(translation).font(.callout).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func cards(_ result: WordBreakdown.Result) -> some View {
        let focused = request.focus.flatMap { focus -> Int? in
            switch focus {
            case .chinese(let offset): return result.cardAt(offset)
            case .translation: return nil
            }
        }
        VStack(alignment: .leading, spacing: 12) {
            ForEach(result.cards.indices, id: \.self) { index in
                CardView(card: result.cards[index], focused: index == focused)
            }
            if result.cards.isEmpty {
                Text("No Chinese words here.").foregroundStyle(.secondary)
            }
        }
    }

    private func translationWords(_ result: WordBreakdown.Result) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Words of the translation").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            FlowLayout(spacing: 6) {
                ForEach(result.translationWords.indices, id: \.self) { index in
                    let word = result.translationWords[index]
                    Button {
                        selectedEnglish = word.text
                        candidates = lexicon.candidates(for: word.text)
                    } label: {
                        HStack(spacing: 4) {
                            Text(word.text)
                            if let card = word.card {
                                Image(systemName: "arrow.right").font(.caption2)
                                Text(result.cards[card].text).foregroundStyle(.secondary)
                            }
                        }
                        .font(.callout)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(selectedEnglish == word.text ? Color.accentColor.opacity(0.18) : Color(.secondarySystemBackground),
                                    in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func reverseLookup(_ english: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Other Chinese words for “\(english)”").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            if candidates.isEmpty {
                Text("None in the dictionary.").foregroundStyle(.secondary).font(.callout)
            }
            ForEach(candidates.indices, id: \.self) { index in
                let candidate = candidates[index]
                Button {
                    speak(candidate.word)
                } label: {
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 8) {
                                Text(candidate.word).font(.body.weight(.medium))
                                Text(candidate.pinyin).font(.callout.italic()).foregroundStyle(.secondary)
                            }
                            Text(candidate.gloss).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        Spacer()
                        Text("\(Int((candidate.score * 100).rounded()))%").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Divider()
            }
        }
    }

    // MARK: Loading

    private func load() async {
        lexicon.prepare()
        result = await lexicon.analyse(request)
        unavailable = lexicon.unavailable
    }

    private func speak(_ word: String) {
        conversation.speech.say("breakdown-\(word)", word, language: "zh")
    }
}

/// One word: its reading, best meanings, and (for longer words) its characters.
private struct CardView: View {
    let card: WordBreakdown.Card
    let focused: Bool
    @Environment(ConversationController.self) private var conversation

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    // Pinyin above the characters, the way it's read.
                    if let numbered = card.numbered {
                        TonePinyin(numbered: numbered).font(.callout)
                    }
                    Text(card.text).font(.title2.weight(.semibold))
                }
                Spacer()
                Button {
                    conversation.speech.say("breakdown-\(card.text)", card.text, language: "zh")
                } label: {
                    Image(systemName: "speaker.wave.2")
                }
                .accessibilityLabel("Say \(card.text)")
            }

            if card.senses.isEmpty {
                Text("Not in the dictionary.").font(.callout).foregroundStyle(.secondary)
            }
            ForEach(card.senses.indices.prefix(4), id: \.self) { index in
                SenseRow(sense: card.senses[index])
            }
            if card.readings.count > 1 {
                Text("Other readings: " + card.readings.dropFirst().map { $0.pinyin }.joined(separator: ", "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if !card.characters.isEmpty {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(card.characters.indices, id: \.self) { index in
                        let character = card.characters[index]
                        VStack(spacing: 2) {
                            Text(character.pinyin ?? " ").font(.caption2.italic()).foregroundStyle(.secondary)
                            Text(character.char).font(.title3)
                            if let gloss = character.gloss {
                                Text(gloss).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                            }
                        }
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
        .overlay {
            if focused {
                RoundedRectangle(cornerRadius: 14).stroke(Color.accentColor, lineWidth: 2)
            }
        }
    }
}

private struct SenseRow: View {
    let sense: WordBreakdown.Sense

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(Pinyin.marksInBrackets(sense.gloss))
                    .font(.callout.weight(sense.matched ? .semibold : .regular))
                    .foregroundStyle(sense.matched ? Color.accentColor : .primary)
                Spacer(minLength: 8)
                Text("\(Int((sense.score * 100).rounded()))%")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.secondary.opacity(0.18))
                    Capsule()
                        .fill(sense.matched ? Color.accentColor : Color.secondary.opacity(0.55))
                        .frame(width: max(3, proxy.size.width * sense.score))
                }
            }
            .frame(height: 4)
        }
    }
}

/// Pinyin with a colour per tone: the reading, at a glance.
private struct TonePinyin: View {
    let numbered: String

    var body: some View {
        var text = Text("")
        for (index, syllable) in numbered.split(separator: " ").enumerated() {
            if index > 0 { text = text + Text(" ") }
            text = text + Text(Pinyin.marks(String(syllable)))
                .foregroundColor(TonePinyin.color(Pinyin.tone(String(syllable))))
        }
        return text
    }

    static func color(_ tone: Int) -> Color {
        switch tone {
        case 1: return .red
        case 2: return .orange
        case 3: return .green
        case 4: return .blue
        default: return .secondary
        }
    }
}

/// Wraps chips onto as many lines as they need.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += lineHeight + spacing
                lineHeight = 0
            }
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
        return CGSize(width: proposal.width ?? x, height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += lineHeight + spacing
                lineHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}
