// MARK: - AirPlayButton — the system AirPlay / audio output picker

import AVFoundation
import AVKit
import SwiftUI

/// The system AirPlay button. Tapping it opens Apple's list of speakers,
/// AirPlay devices and headphones. The icon turns `activeTint` while
/// audio is going somewhere other than this device.
struct AirPlayButton: View {
    /// On the Mac, the player whose output the picker changes.
    var player: AVPlayer?
    var tint: Color = .white.opacity(0.6)
    var activeTint: Color = .accentColor

    var body: some View {
        RoutePicker(player: player, tint: tint, activeTint: activeTint)
    }
}

#if os(iOS)
private struct RoutePicker: UIViewRepresentable {
    var player: AVPlayer?
    var tint: Color
    var activeTint: Color

    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.backgroundColor = .clear
        view.prioritizesVideoDevices = false
        return view
    }

    func updateUIView(_ view: AVRoutePickerView, context: Context) {
        view.tintColor = UIColor(tint)
        view.activeTintColor = UIColor(activeTint)
    }
}
#elseif os(macOS)
private struct RoutePicker: NSViewRepresentable {
    var player: AVPlayer?
    var tint: Color
    var activeTint: Color

    func makeNSView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.isRoutePickerButtonBordered = false
        return view
    }

    func updateNSView(_ view: AVRoutePickerView, context: Context) {
        view.player = player
        view.setRoutePickerButtonColor(NSColor(tint), for: .normal)
        view.setRoutePickerButtonColor(NSColor(activeTint), for: .active)
    }
}
#endif
