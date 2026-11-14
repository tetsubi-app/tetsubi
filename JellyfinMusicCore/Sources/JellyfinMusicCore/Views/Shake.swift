// MARK: - Shake — device shake as a SwiftUI event (iOS)

import SwiftUI
#if canImport(UIKit)
import UIKit

extension Notification.Name {
    /// Posted when the user shakes the device.
    static let tetsubiDeviceDidShake = Notification.Name("TetsubiDeviceDidShake")
}

extension UIWindow {
    /// SwiftUI has no shake event; the window passes iOS's on.
    open override func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        if motion == .motionShake {
            NotificationCenter.default.post(name: .tetsubiDeviceDidShake, object: nil)
        }
        super.motionEnded(motion, with: event)
    }
}
#endif

extension View {
    /// Runs `action` when the device is shaken (iOS; nothing on the Mac).
    @ViewBuilder
    func onShake(perform action: @escaping () -> Void) -> some View {
        #if canImport(UIKit)
        self.onReceive(NotificationCenter.default.publisher(for: .tetsubiDeviceDidShake)) { _ in action() }
        #else
        self
        #endif
    }
}
