// MARK: - Artist links — an artist line that opens the artist's albums

import SwiftUI
#if os(macOS)
import AppKit
#endif

extension View {
    /// A button or link around an artist line (album page, Now Playing) that
    /// looks exactly like the plain text: the plain style (no chrome; it only
    /// dims a little while pressed), the Mac's pointing hand, and read out as
    /// "Show albums by …".
    func artistLinkStyle(_ name: String) -> some View {
        buttonStyle(.plain)
            .accessibilityLabel("Show albums by \(name)")
            .linkPointer()
    }

    /// Mac: the pointing-hand cursor while the pointer is over this.
    @ViewBuilder
    func linkPointer() -> some View {
        #if os(macOS)
        if #available(macOS 15.0, *) {
            pointerStyle(.link)
        } else {
            onHover { inside in
                if inside { NSCursor.pointingHand.push() } else { NSCursor.pop() }
            }
        }
        #else
        self
        #endif
    }
}
