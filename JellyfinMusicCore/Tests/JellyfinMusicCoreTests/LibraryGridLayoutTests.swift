import XCTest
@testable import JellyfinMusicCore

/// Widths are the grid's usable width: screen − 4pt leading − A–Z strip (22pt).
final class LibraryGridLayoutTests: XCTestCase {

    func testThreeAcrossOn393ptPhoneWithStrip() {
        let layout = LibraryView.gridLayout(width: 393 - 4 - 22)
        XCTAssertEqual(layout.columns, 3)
        XCTAssertEqual(layout.spacing, 3.5, accuracy: 0.01)
    }

    func testThreeAcrossOn402ptPhoneWithStrip() {
        let layout = LibraryView.gridLayout(width: 402 - 4 - 22)
        XCTAssertEqual(layout.columns, 3)
        XCTAssertEqual(layout.spacing, 8, accuracy: 0.01)
    }

    /// 375pt phones can't fit three 120pt covers next to the strip.
    func testTwoAcrossWhenThreeDontFit() {
        XCTAssertEqual(LibraryView.gridLayout(width: 375 - 4 - 22).columns, 2)
    }

    func testWideWindowsKeepComfortableSpacing() {
        let layout = LibraryView.gridLayout(width: 1000)
        XCTAssertEqual(layout.columns, 7)
        XCTAssertEqual(layout.spacing, 12)
    }
}
