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

/// Margins and gutters per class (docs/redesign.md section 1), and the phone rule.
final class WindowWidthClassMetricsTests: XCTestCase {
    func testMarginAndGutter() {
        XCTAssertEqual(WindowWidthClass.compact.pageMargin, 16)
        XCTAssertEqual(WindowWidthClass.compact.gutter, 12)
        XCTAssertEqual(WindowWidthClass.medium.pageMargin, 24)
        XCTAssertEqual(WindowWidthClass.medium.gutter, 16)
        XCTAssertEqual(WindowWidthClass.expanded.pageMargin, 32)
        XCTAssertEqual(WindowWidthClass.expanded.gutter, 20)
    }

    func testMiniPlayerHeightAndMaxWidth() {
        XCTAssertEqual(WindowWidthClass.compact.miniPlayerHeight, 64)
        XCTAssertEqual(WindowWidthClass.medium.miniPlayerHeight, 72)
        XCTAssertEqual(WindowWidthClass.expanded.miniPlayerHeight, 72)
        XCTAssertEqual(WindowWidthClass.maxContentWidth, 1600)
        XCTAssertTrue(WindowWidthClass.medium.isWide)
        XCTAssertFalse(WindowWidthClass.compact.isWide)
    }

    func testPhoneIsAlwaysCompact() {
        // A Max on its side is 932 pt wide and still a phone.
        XCTAssertEqual(WindowWidthClass.resolve(width: 932, isPhone: true), .compact)
        XCTAssertEqual(WindowWidthClass.resolve(width: 390, isPhone: true), .compact)
        // iPad split view and Stage Manager follow the width rule, as the Mac.
        XCTAssertEqual(WindowWidthClass.resolve(width: 507, isPhone: false), .compact)
        XCTAssertEqual(WindowWidthClass.resolve(width: 694, isPhone: false), .medium)
        XCTAssertEqual(WindowWidthClass.resolve(width: 1194, isPhone: false), .expanded)
    }
}
