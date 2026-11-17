import XCTest
@testable import JellyfinMusicCore

/// Settings ▸ About lists the QA team on one line, separated by bullets.
final class SettingsQATeamTests: XCTestCase {
    func testNamesAreOnOneLineSeparatedByBullets() {
        XCTAssertEqual(SettingsView.qaTeamLine, "Matt Evans \u{2022} Eloy Smith Rodriguez \u{2022} Adam Buchanan")
    }
}
