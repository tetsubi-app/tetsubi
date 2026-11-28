// MARK: - LoadingOutline — the chasing outline shown while a cover loads

import SwiftUI

/// A faint rounded outline with a pink and a blue pulse chasing each other
/// round it. All outlines share the clock, so every card moves in step.
/// Reduce Motion: just the faint outline.
///
/// Drawn at 30 fps, and still after `animationLimit`: a load that takes that
/// long is probably stuck, and a still outline costs no CPU (an endless
/// full-rate animation kept the Mac at ~40% CPU while idle).
struct LoadingOutline: View {
    let cornerRadius: CGFloat
    /// Seconds per lap.
    var period: Double = 2.0
    /// How long the pulses run before the outline goes still.
    var animationLimit: Duration = .seconds(20)

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expired = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius)
        ZStack {
            shape.strokeBorder(Color.secondary.opacity(0.3), lineWidth: 1)
            if !reduceMotion && !expired {
                TimelineView(.animation(minimumInterval: 1.0 / 30)) { context in
                    let t = context.date.timeIntervalSinceReferenceDate
                    let phase = t.truncatingRemainder(dividingBy: period) / period
                    shape.strokeBorder(
                        AngularGradient(gradient: Self.pulses, center: .center,
                                        angle: .degrees(phase * 360)),
                        lineWidth: 2
                    )
                }
            }
        }
        .task {
            try? await Task.sleep(for: animationLimit)
            if !Task.isCancelled { expired = true }
        }
    }

    /// Two comets half a lap apart: each fades in from its tail and ends
    /// at a bright head, which leads as the gradient turns clockwise.
    private static let pulses = Gradient(stops: [
        .init(color: .clear, location: 0.0),
        .init(color: Color.appPink.opacity(0), location: 0.25),
        .init(color: Color.appPink, location: 0.5),
        .init(color: .clear, location: 0.5001),
        .init(color: Color.appBlue.opacity(0), location: 0.75),
        .init(color: Color.appBlue, location: 1.0),
    ])
}
