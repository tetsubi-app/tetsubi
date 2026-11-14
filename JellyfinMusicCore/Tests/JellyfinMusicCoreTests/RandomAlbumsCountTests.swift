import XCTest
@testable import JellyfinMusicCore

/// Home ▸ Random Albums ›: 9 albums, or 12 when four rows fit.
final class RandomAlbumsCountTests: XCTestCase {

    func testNineOnAShorterScreenTwelveWhenFourRowsFit() {
        let row: CGFloat = 157 // 120 pt cover + one-line title + artist
        // 32 padding + 4 × 157 + 3 × 16 gaps + 26 footer = 734
        XCTAssertEqual(RandomAlbumsView.count(forHeight: 733, rowHeight: row), 9)
        XCTAssertEqual(RandomAlbumsView.count(forHeight: 757, rowHeight: row), 12, "iPhone Pro Max")
        XCTAssertEqual(RandomAlbumsView.count(forHeight: 600, rowHeight: row), 9)
    }

    func testBiggerTextNeedsMoreRoom() {
        XCTAssertEqual(RandomAlbumsView.count(forHeight: 757, rowHeight: 200), 9)
    }
}
