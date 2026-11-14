import XCTest
@testable import JellyfinMusicCore

/// Device names in the server header must be plain text (HTTP 400 otherwise).
final class HeaderSafeNameTests: XCTestCase {

    func testCurlyApostropheBecomesStraight() {
        XCTAssertEqual(JellyfinAPI.headerSafe("Jake\u{2019}s Mac mini"), "Jake's Mac mini")
    }

    func testAccentsAreDropped() {
        XCTAssertEqual(JellyfinAPI.headerSafe("Zoë’s iPhone"), "Zoe's iPhone")
    }

    func testQuotesBackslashesAndEmojiAreRemoved() {
        XCTAssertEqual(JellyfinAPI.headerSafe("My \"Studio\" Mac\\ 🎧"), "My Studio Mac")
    }

    func testPlainNamesAreUnchanged() {
        XCTAssertEqual(JellyfinAPI.headerSafe("iPhone"), "iPhone")
        XCTAssertEqual(JellyfinAPI.headerSafe("Tetsubi"), "Tetsubi")
    }

    func testNothingLeftGetsAFallback() {
        XCTAssertEqual(JellyfinAPI.headerSafe("🎧🎧"), "Device")
    }
}
