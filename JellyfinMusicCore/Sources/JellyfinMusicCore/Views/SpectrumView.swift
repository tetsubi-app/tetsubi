// MARK: - SpectrumView — animated visualiser bars for Now Playing

import SwiftUI

/// Bars from `AudioEffects.spectrum()`, redrawn ~30 times a second. Turns
/// spectrum analysis on while visible and off when it disappears.
public struct SpectrumView: View {
    /// Smoothed levels: bars jump up quickly and fall back gently.
    @State private var levels = SmoothedLevels()
    let isPlaying: Bool
    /// Bar colour: the key colour from the album art, as the progress line.
    /// Nil falls back to the pink-to-blue gradient.
    let tint: Color?

    public init(isPlaying: Bool, tint: Color? = nil) {
        self.isPlaying = isPlaying
        self.tint = tint
    }

    public var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !isPlaying && levels.isSilent)) { context in
            Canvas { gc, size in
                let bars = levels.update(with: isPlaying ? AudioEffects.shared.spectrum() : [], at: context.date)
                guard !bars.isEmpty else { return }
                let gap: CGFloat = 3
                let width = (size.width - gap * CGFloat(bars.count - 1)) / CGFloat(bars.count)
                let shading: GraphicsContext.Shading = tint.map { .color($0) }
                    ?? .linearGradient(Gradient(colors: [.appPink, .appBlue]),
                                       startPoint: .zero, endPoint: CGPoint(x: size.width, y: 0))
                for (i, level) in bars.enumerated() {
                    let h = max(2, CGFloat(level) * size.height)
                    let rect = CGRect(x: CGFloat(i) * (width + gap), y: size.height - h, width: width, height: h)
                    gc.fill(Path(roundedRect: rect, cornerRadius: min(2, width / 2)), with: shading)
                }
            }
        }
        .onAppear { AudioEffects.shared.setVisualizerActive(true) }
        .onDisappear { AudioEffects.shared.setVisualizerActive(false) }
        .accessibilityHidden(true)
    }
}

/// Holds the bar levels between frames.
final class SmoothedLevels {
    private var values = [Float](repeating: 0, count: AudioEffects.barCount)
    private var last = Date.distantPast

    var isSilent: Bool { values.allSatisfy { $0 < 0.01 } }

    /// Rises to new peaks at once; falls at most ~1.5 of full height per second.
    func update(with target: [Float], at date: Date) -> [Float] {
        let dt = Float(min(0.1, max(0, date.timeIntervalSince(last))))
        last = date
        for i in values.indices {
            let t = i < target.count ? target[i] : 0
            values[i] = t >= values[i] ? t : max(t, values[i] - 1.5 * dt)
        }
        return values
    }
}
