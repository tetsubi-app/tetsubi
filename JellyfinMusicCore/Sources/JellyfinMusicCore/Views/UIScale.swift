// MARK: - UIScale — the Mac grows covers, text and spacing with its window

import SwiftUI
import Observation

/// One app-wide size factor. On the Mac it follows the window: at the default
/// 1100 × 760 window it's 1.0, and it grows (up to 1.6) as the window gets
/// bigger, so a large screen gets bigger covers and text, not just more empty
/// space. iOS and iPadOS always use 1.0.
///
/// Views read `factor` in `body` (through `Font.plex` and `.scaled`), so
/// Observation re-renders them when it changes: no view identity is reset,
/// so navigation, scroll position and playback carry on through a resize.
@Observable
public final class UIScale: @unchecked Sendable {
    public static let shared = UIScale()

    /// Multiplier for point sizes. Only changed on the main thread.
    public internal(set) var factor: CGFloat = 1.0

    /// The Mac window's width, for views that size themselves from it (the Now
    /// Playing sheet). 0 until measured; iOS never sets it.
    public internal(set) var windowWidth: CGFloat = 0
    public internal(set) var windowHeight: CGFloat = 0

    init() {}

    // MARK: Formula

    /// The window size where the factor is 1.0 (the Mac's default window).
    public static let baseSize = CGSize(width: 1100, height: 760)
    public static let maxFactor: CGFloat = 1.6
    /// Steps, so small resizes don't re-lay out the whole window each time.
    public static let step: CGFloat = 0.05

    /// The factor for a window of `size`: whichever of width and height has
    /// grown less sets it, clamped to 1.0…1.6 and rounded down to 0.05.
    /// Always 1.0 when `scales` is false (iOS).
    public static func factor(for size: CGSize, scales: Bool = platformScales) -> CGFloat {
        guard scales, size.width > 0, size.height > 0 else { return 1.0 }
        let raw = min(size.width / baseSize.width, size.height / baseSize.height)
        let clamped = min(max(raw, 1.0), maxFactor)
        let steps = (clamped / step + 1e-6).rounded(.down)
        return steps * step
    }

    /// Whether this platform scales with the window (the Mac only).
    public static var platformScales: Bool {
        #if os(macOS)
        true
        #else
        false
        #endif
    }

    /// Sets the factor for a window size; writes only when it changes.
    @MainActor
    public func update(for size: CGSize) {
        let new = Self.factor(for: size)
        if abs(new - factor) > 0.001 { factor = new }
        if abs(size.width - windowWidth) >= 1 { windowWidth = size.width }
        if abs(size.height - windowHeight) >= 1 { windowHeight = size.height }
    }
}

// MARK: - Call-site helpers

public extension CGFloat {
    /// This many points, scaled by `UIScale` (unchanged on iOS).
    var scaled: CGFloat { self * UIScale.shared.factor }
}

public extension Int {
    /// This many points, scaled by `UIScale` (unchanged on iOS).
    var scaled: CGFloat { CGFloat(self) * UIScale.shared.factor }
}

public extension Double {
    /// This many points, scaled by `UIScale` (unchanged on iOS).
    var scaled: CGFloat { CGFloat(self) * UIScale.shared.factor }
}

// MARK: - Measuring the window

public extension View {
    /// Mac: measures this view (the window's content) and sets `UIScale`
    /// from it. Does nothing on iOS.
    @ViewBuilder
    func measuresUIScale() -> some View {
        #if os(macOS)
        self.onGeometryChange(for: CGSize.self) { $0.size } action: { size in
            UIScale.shared.update(for: size)
        }
        #else
        self
        #endif
    }
}
