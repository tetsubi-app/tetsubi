// MARK: - ReadableWidth — centred, width-limited lists on the Mac

import SwiftUI

public extension View {
    /// Mac: keeps a list's rows to a readable width, centred in the window,
    /// like Settings' grouped form. No change on iPhone.
    func readableWidthOnMac(_ maxWidth: CGFloat = ReadableWidth.macMaxWidth) -> some View {
        modifier(ReadableWidth(maxWidth: maxWidth))
    }
}

public struct ReadableWidth: ViewModifier {
    /// About the width of Settings' grouped boxes on the Mac.
    public static let macMaxWidth: CGFloat = 720

    let maxWidth: CGFloat

    public func body(content: Content) -> some View {
        #if os(macOS)
        // Mac lists ignore content margins, so the list itself is limited
        // and centred; the page background still fills the window.
        // Grows with a big window (`UIScale`), as the text in it does.
        content
            .frame(maxWidth: maxWidth.scaled)
            .frame(maxWidth: .infinity)
        #else
        content
        #endif
    }
}
