import SwiftUI

/// Three bars breathing while a voice is being made: nothing is audible yet,
/// and it takes a moment. Shown in place of the speaker icon the tap started.
struct MakingVoiceBars: View {
    var tint: Color

    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(0..<3, id: \.self) { index in
                    let wave = 0.5 + 0.5 * sin(2 * .pi * (t / 0.76 - Double(index) * 0.11))
                    Capsule()
                        .fill(tint)
                        .frame(width: 3, height: 14 * (0.2 + 0.8 * wave))
                }
            }
            .frame(width: 14, height: 14, alignment: .bottomLeading)
        }
        .frame(width: 14, height: 14, alignment: .bottomLeading)
        .accessibilityLabel("Generating the voice")
    }
}
