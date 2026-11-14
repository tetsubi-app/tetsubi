// MARK: - PulsingProgressBar — download progress that pulses between appBlue and appPink

import SwiftUI

struct PulsingProgressBar: View {
    let progress: Double
    /// When paused the bar stays a still, dimmed grey.
    var isPaused: Bool = false

    @State private var pulsing = false

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.15))
                Capsule()
                    .fill(isPaused ? Color.white.opacity(0.45)
                                   : (pulsing ? Color.appPink : Color.appBlue))
                    .opacity(isPaused ? 1 : (pulsing ? 1 : 0.7))
                    .frame(width: max(8, geo.size.width * min(max(progress, 0), 1)))
            }
        }
        .frame(height: 8)
        .animation(.easeInOut(duration: 0.4), value: progress)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.0).repeatForever(autoreverses: true)) {
                pulsing = true
            }
        }
    }
}
