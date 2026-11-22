import XCTest
@testable import JellyfinMusicCore

/// "Download over mobile data": off by default, and downloads wait for Wi‑Fi.
final class DownloadNetworkRuleTests: XCTestCase {

    func testSavedChoiceIsKept() {
        for saved in [true, false] {
            let suite = "DownloadNetworkRuleTests-\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defaults.set(saved, forKey: "downloads.allowCellular")
            PlaybackSettings.defaults = defaults
            XCTAssertEqual(PlaybackSettings.downloadOverCellular, saved)
            PlaybackSettings.defaults = .standard
            UserDefaults().removePersistentDomain(forName: suite)
        }
    }

    func testDefaultIsOff() {
        let suite = "DownloadNetworkRuleTests-\(UUID().uuidString)"
        PlaybackSettings.defaults = UserDefaults(suiteName: suite)!
        defer {
            PlaybackSettings.defaults = .standard
            UserDefaults().removePersistentDomain(forName: suite)
        }
        XCTAssertFalse(PlaybackSettings.downloadOverCellular)
    }

    func testDownloadsRunOnWiFiOrWithMobileDataOn() {
        XCTAssertTrue(DownloadNetworkRule.mayDownload(allowCellular: false, isExpensive: false))
        XCTAssertFalse(DownloadNetworkRule.mayDownload(allowCellular: false, isExpensive: true))
        XCTAssertTrue(DownloadNetworkRule.mayDownload(allowCellular: true, isExpensive: true))
        XCTAssertTrue(DownloadNetworkRule.mayDownload(allowCellular: true, isExpensive: false))
    }

    func testOnlyARunningDownloadOnMobileDataWaits() {
        XCTAssertTrue(DownloadNetworkRule.isWaitingForWiFi(status: .downloading, allowCellular: false, isExpensive: true))
        XCTAssertFalse(DownloadNetworkRule.isWaitingForWiFi(status: .downloading, allowCellular: false, isExpensive: false),
                       "back on Wi‑Fi it carries on")
        XCTAssertFalse(DownloadNetworkRule.isWaitingForWiFi(status: .downloading, allowCellular: true, isExpensive: true))
        XCTAssertFalse(DownloadNetworkRule.isWaitingForWiFi(status: .paused, allowCellular: false, isExpensive: true))
        XCTAssertFalse(DownloadNetworkRule.isWaitingForWiFi(status: .failed, allowCellular: false, isExpensive: true))
    }

    func testTasksWithTheOtherChoiceAreMadeAgain() {
        XCTAssertTrue(DownloadNetworkRule.needsRestart(requestAllowsExpensive: false, allowCellular: true))
        XCTAssertTrue(DownloadNetworkRule.needsRestart(requestAllowsExpensive: true, allowCellular: false))
        XCTAssertFalse(DownloadNetworkRule.needsRestart(requestAllowsExpensive: false, allowCellular: false))
        XCTAssertFalse(DownloadNetworkRule.needsRestart(requestAllowsExpensive: true, allowCellular: true))
    }

    func testNoAllowedNetworkWaitsInsteadOfFailing() {
        let expensive = URLError(.notConnectedToInternet, userInfo: [
            NSURLErrorNetworkUnavailableReasonKey: URLError.NetworkUnavailableReason.expensive.rawValue,
        ])
        XCTAssertTrue(DownloadNetworkRule.isWaitingForAllowedNetwork(expensive))
        XCTAssertFalse(DownloadNetworkRule.isWaitingForAllowedNetwork(URLError(.notConnectedToInternet)))
        XCTAssertFalse(DownloadNetworkRule.isWaitingForAllowedNetwork(URLError(.timedOut)))
        XCTAssertFalse(DownloadNetworkRule.isWaitingForAllowedNetwork(CocoaError(.fileNoSuchFile)))
    }

    func testPageStatusText() {
        XCTAssertEqual(DownloadNetworkRule.statusText(failed: false, paused: false, waitingForWiFi: true), "Waiting for Wi\u{2011}Fi")
        XCTAssertEqual(DownloadNetworkRule.statusText(failed: false, paused: false, waitingForWiFi: false), "Downloading")
        XCTAssertEqual(DownloadNetworkRule.statusText(failed: false, paused: true, waitingForWiFi: false), "Paused")
        XCTAssertEqual(DownloadNetworkRule.statusText(failed: true, paused: false, waitingForWiFi: false), "Download failed")
    }
}
