import XCTest
@testable import JellyfinMusicCore

/// Settings' categories and its split view.
final class SettingsCategoryTests: XCTestCase {

    func testTheOrderIsManiacServerDownloadsPlaybackStorageAbout() {
        XCTAssertEqual(SettingsCategory.available(hasDownloads: true).map(\.title),
                       ["Maniac Mode", "Server", "Downloads", "Playback", "Storage", "About"])
    }

    func testDownloadsIsOfferedOnlyWithADownloadManager() {
        XCTAssertFalse(SettingsCategory.available(hasDownloads: false).contains(.downloads))
        XCTAssertEqual(SettingsCategory.available(hasDownloads: false).count, 5)
    }

    func testTheRememberedCategoryComesBack() {
        let all = SettingsCategory.available(hasDownloads: true)
        XCTAssertEqual(SettingsCategory.resolve("Playback", available: all), .playback)
        XCTAssertEqual(SettingsCategory.resolve("About", available: all), .about)
    }

    func testNothingStoredOrUnknownOrUnavailableFallsBackToTheFirst() {
        let all = SettingsCategory.available(hasDownloads: true)
        XCTAssertEqual(SettingsCategory.resolve(nil, available: all), .maniac)
        XCTAssertEqual(SettingsCategory.resolve("", available: all), .maniac)
        XCTAssertEqual(SettingsCategory.resolve("Updates", available: all), .maniac)
        // Downloads was remembered, then signed out.
        XCTAssertEqual(SettingsCategory.resolve("Downloads", available: SettingsCategory.available(hasDownloads: false)), .maniac)
    }

    func testTheSplitViewFollowsTheDetailPageRule() {
        XCTAssertFalse(SettingsCategory.usesSplit(windowClass: .compact, paneWidth: 390))
        XCTAssertFalse(SettingsCategory.usesSplit(windowClass: .medium, paneWidth: 834))
        XCTAssertTrue(SettingsCategory.usesSplit(windowClass: .medium, paneWidth: 900))
        XCTAssertTrue(SettingsCategory.usesSplit(windowClass: .expanded, paneWidth: 784))
    }
}
