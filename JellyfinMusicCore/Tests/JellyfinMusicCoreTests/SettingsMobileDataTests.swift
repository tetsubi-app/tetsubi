import XCTest
@testable import JellyfinMusicCore

/// Mobile-data rows and footer sentences show where there is mobile data (not on the Mac).
final class SettingsMobileDataTests: XCTestCase {
    func testMobileDataIsOnPhonesAndTabletsOnly() {
        XCTAssertTrue(SettingsText.showsMobileData(isMac: false))
        XCTAssertFalse(SettingsText.showsMobileData(isMac: true))
    }

    func testPlaybackFooterKeepsTheMobileSentenceOnlyWithMobileData() {
        let phone = SettingsText.playbackFooter(showsMobileData: true)
        let mac = SettingsText.playbackFooter(showsMobileData: false)
        XCTAssertTrue(phone.hasPrefix("On mobile data, tracks stream at the chosen quality"))
        XCTAssertTrue(phone.hasSuffix(mac))
        XCTAssertTrue(mac.hasPrefix("Normalise volume evens out"))
        XCTAssertFalse(mac.contains("mobile data"))
        XCTAssertTrue(mac.contains("Gapless playback"))
    }
}
