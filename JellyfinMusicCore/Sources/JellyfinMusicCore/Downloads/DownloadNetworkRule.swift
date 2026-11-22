// MARK: - DownloadNetworkRule — "Download over mobile data"

import Foundation

/// When downloads may use the network. With "Download over mobile data" off
/// (the default) a download waits on mobile data or a hotspot and carries
/// on by itself on Wi‑Fi: each request is made with
/// `allowsExpensiveNetworkAccess` off, and the background session holds it
/// until an allowed network is there. Android's `DownloadManager.mayDownload`
/// is the same rule.
public enum DownloadNetworkRule {

    /// Whether downloads may run now: always with mobile data on, else only
    /// on a network that isn't expensive (Wi‑Fi, Ethernet).
    public static func mayDownload(allowCellular: Bool, isExpensive: Bool) -> Bool {
        allowCellular || !isExpensive
    }

    /// Whether a download under way shows "Waiting for Wi‑Fi".
    public static func isWaitingForWiFi(status: DownloadStatus, allowCellular: Bool, isExpensive: Bool) -> Bool {
        status == .downloading && !mayDownload(allowCellular: allowCellular, isExpensive: isExpensive)
    }

    /// Whether a running task must be made again after the setting changed:
    /// its request still carries the other choice. (A task can't be changed
    /// in place, and resume data keeps the old request.)
    public static func needsRestart(requestAllowsExpensive: Bool, allowCellular: Bool) -> Bool {
        requestAllowsExpensive != allowCellular
    }

    /// Whether a task ended only because the network it may use isn't there
    /// (on mobile data with mobile data off). It is made again to wait,
    /// without counting as one of the track's retries.
    public static func isWaitingForAllowedNetwork(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        switch urlError.networkUnavailableReason {
        case .expensive?, .constrained?: return true
        default: return false
        }
    }

    /// The album and playlist pages' note under the bar.
    public static func statusText(failed: Bool, paused: Bool, waitingForWiFi: Bool) -> String {
        failed ? "Download failed" : paused ? "Paused" : waitingForWiFi ? "Waiting for Wi\u{2011}Fi" : "Downloading"
    }
}
