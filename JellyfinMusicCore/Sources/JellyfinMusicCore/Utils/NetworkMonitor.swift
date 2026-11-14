// MARK: - NetworkMonitor — online / cellular state for the whole app

import Foundation
import Network
import Observation

/// Watches the device's network path. Drives the offline banner, offline
/// search, and the lower streaming bitrate on cellular / hotspots.
@MainActor
@Observable
public final class NetworkMonitor {

    public static let shared = NetworkMonitor()

    /// Whether any network route is available.
    public private(set) var isConnected = true
    /// Cellular or a personal hotspot — "costs the user data".
    public private(set) var isExpensive = false

    private let monitor = NWPathMonitor()

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let connected = path.status == .satisfied
            let expensive = path.isExpensive
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self?.isConnected = connected
                    self?.isExpensive = expensive
                }
            }
        }
        monitor.start(queue: DispatchQueue(label: "NetworkMonitor"))
    }
}
