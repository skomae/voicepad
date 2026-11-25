import SwiftUI

/// A neon bezel round the screen while the mic is on: a hot core line hugging
/// the edge, a soft bloom bleeding inward, and a slow breath so it reads as
/// "live" rather than as a static border. It brightens a little with the level,
/// so it also answers "is it hearing me?".
struct RecordingBezel: View {
    let active: Bool
    let level: Float

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The display's own corner radius, a touch generous so the stroke is
    /// clipped by the screen edge at the corners instead of cutting inside it.
    private let cornerRadius: CGFloat = 55

    var body: some View {
        if active {
            if reduceMotion {
                glow(1)
            } else {
                // 30 fps is plenty for a slow breath and keeps the blur cheap.
                TimelineView(.animation(minimumInterval: 1.0 / 30)) { context in
                    let t = context.date.timeIntervalSinceReferenceDate
                    let breath = 0.5 + 0.5 * sin(t * 1.9)
                    let loud = min(1, 0.25 + Double(level) * 2.5)
                    glow(max(0.55 + 0.45 * breath, loud))
                }
            }
        }
    }

    private func glow(_ intensity: Double) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        return ZStack {
            // The bloom: wide, soft, most of the light.
            shape
                .strokeBorder(Color.recordingRed.opacity(0.9), lineWidth: 12)
                .blur(radius: 18)
                .opacity(0.6 * intensity)
            // The tube.
            shape
                .strokeBorder(Color.recordingRed, lineWidth: 3)
                .blur(radius: 1.2)
                .opacity(intensity)
            // The white-hot line inside the tube, which is what makes it read as
            // neon rather than as a red rounded rectangle.
            shape
                .strokeBorder(Color(red: 1, green: 0.66, blue: 0.62), lineWidth: 1.3)
                .blur(radius: 0.4)
                .opacity(0.45 + 0.5 * intensity)
        }
        // One point in from the glass: the tube has to be inside the screen to be
        // seen at all, and the bloom's outside half is clipped for free.
        .padding(1)
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}
