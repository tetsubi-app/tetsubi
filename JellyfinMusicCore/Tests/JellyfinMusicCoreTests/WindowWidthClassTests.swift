import XCTest
@testable import JellyfinMusicCore

/// Compact under 600 pt, medium 600-1023, expanded 1024 and up.
final class WindowWidthClassTests: XCTestCase {
    func testBoundaries() {
        XCTAssertEqual(WindowWidthClass.of(width: 0), .compact)
        XCTAssertEqual(WindowWidthClass.of(width: 360), .compact)
        XCTAssertEqual(WindowWidthClass.of(width: 599.9), .compact)
        XCTAssertEqual(WindowWidthClass.of(width: 600), .medium)
        XCTAssertEqual(WindowWidthClass.of(width: 1023.9), .medium)
        XCTAssertEqual(WindowWidthClass.of(width: 1024), .expanded)
        XCTAssertEqual(WindowWidthClass.of(width: 2560), .expanded)
    }

    func testLayoutFlags() {
        XCTAssertFalse(WindowWidthClass.compact.usesWideGrids)
        XCTAssertTrue(WindowWidthClass.medium.usesWideGrids)
        XCTAssertTrue(WindowWidthClass.expanded.usesWideGrids)
        XCTAssertTrue(WindowWidthClass.compact.usesTabBar)
        XCTAssertFalse(WindowWidthClass.medium.usesTabBar)
        XCTAssertFalse(WindowWidthClass.expanded.usesTabBar)
        XCTAssertTrue(WindowWidthClass.compact < .medium && WindowWidthClass.medium < .expanded)
    }
}
