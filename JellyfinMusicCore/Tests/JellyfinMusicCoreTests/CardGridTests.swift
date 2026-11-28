import XCTest
@testable import JellyfinMusicCore

/// The one grid's rules (docs/redesign.md section 3); the same numbers as Kotlin's CardGridTest.
final class CardGridTests: XCTestCase {
    // Content widths: 358 is a 390 pt phone less 2 x 16; 658 and 976 are the medium and expanded panes.
    private func columns(_ width: CGFloat, _ density: Density, _ gutter: CGFloat) -> Int {
        gridColumns(contentWidth: width, density: density, gutter: gutter)
    }

    func testMediumDensityColumns() {
        XCTAssertEqual(columns(358, .m, 12), 2)
        XCTAssertEqual(columns(658, .m, 16), 4)
        XCTAssertEqual(columns(706, .m, 16), 4)
        XCTAssertEqual(columns(976, .m, 20), 5)
    }

    func testSmallDensityColumns() {
        XCTAssertEqual(columns(358, .s, 12), 3)
        XCTAssertEqual(columns(658, .s, 16), 5)
        XCTAssertEqual(columns(706, .s, 16), 6)
        XCTAssertEqual(columns(976, .s, 20), 8)
    }

    func testLargeDensityColumns() {
        XCTAssertEqual(columns(358, .l, 12), 2)
        XCTAssertEqual(columns(658, .l, 16), 3)
        XCTAssertEqual(columns(706, .l, 16), 3)
        XCTAssertEqual(columns(976, .l, 20), 4)
    }

    func testNeverFewerThanTwoColumns() {
        XCTAssertEqual(columns(200, .l, 12), 2)
        XCTAssertEqual(columns(0, .m, 12), 2)
    }

    func testCardsFillTheContentWidthExactly() {
        for density in Density.allCases {
            for width: CGFloat in [200, 358, 658, 976, 1500] {
                let count = columns(width, density, 16)
                let card = CardGridLayout.cardWidth(contentWidth: width, columns: count, gutter: 16)
                XCTAssertEqual(card * CGFloat(count) + 16 * CGFloat(count - 1), width, accuracy: 0.01)
            }
        }
        XCTAssertEqual(CardGridLayout.cardWidth(contentWidth: 358, columns: 2, gutter: 12), 173, accuracy: 0.001)
    }

    func testCardsAreNeverNarrowerThanTheDensityAsksFor() {
        for density in Density.allCases {
            for width: CGFloat in [358, 658, 976, 1500] {
                let count = columns(width, density, 16)
                if count > 2 {
                    XCTAssertGreaterThanOrEqual(CardGridLayout.cardWidth(contentWidth: width, columns: count, gutter: 16), density.minCard)
                }
            }
        }
    }

    func testContentWidthLeavesRoomForTheStrip() {
        XCTAssertEqual(CardGridLayout.contentWidth(paneWidth: 390, margin: 16), 358)
        // The A to Z strip wider than the margin takes the margin's place at the end.
        XCTAssertEqual(CardGridLayout.contentWidth(paneWidth: 390, margin: 16, endInset: 24), 350)
        XCTAssertEqual(CardGridLayout.contentWidth(paneWidth: 390, margin: 16, endInset: 8), 358)
    }

    func testMetricsForWindowClasses() {
        let phone = CardMetrics(paneWidth: 390, windowClass: .compact, density: .m)
        XCTAssertEqual(phone.columns, 2)
        XCTAssertEqual(phone.cardWidth, 173, accuracy: 0.001)
        XCTAssertEqual(phone.rowSpacing, 20)
        XCTAssertEqual(phone.gridItems.count, 2)
        XCTAssertEqual(CardMetrics(paneWidth: 706, windowClass: .medium, density: .m).columns, 4)
        XCTAssertEqual(CardMetrics(paneWidth: 1040, windowClass: .expanded, density: .m).columns, 5)
        XCTAssertEqual(CardMetrics(paneWidth: 0, windowClass: .compact, density: .m).columns, 2)
    }

    func testShelfCardsPeekOnCompactOnly() {
        // Compact: two whole cards and 35% of the third fit from the margin to the right edge.
        let card = CardGridLayout.shelfCardWidth(contentWidth: 358, columns: 2, gutter: 12, pageMargin: 16, peek: true)
        XCTAssertEqual(16 + 2 * card + 2 * 12 + CardGridLayout.shelfPeek * card, 390, accuracy: 0.01)
        XCTAssertLessThan(card, CardGridLayout.cardWidth(contentWidth: 358, columns: 2, gutter: 12))
        // Wider: the grid's own card.
        XCTAssertEqual(CardGridLayout.shelfCardWidth(contentWidth: 658, columns: 4, gutter: 16, pageMargin: 24, peek: false),
                       CardGridLayout.cardWidth(contentWidth: 658, columns: 4, gutter: 16))
    }

    func testListColumns() {
        XCTAssertEqual(CardGridLayout.listColumns(paneWidth: 699), 1)
        XCTAssertEqual(CardGridLayout.listColumns(paneWidth: 700), 2)
    }
}

/// The remembered density: "S", "M" or "L" under `ui.density`; the old GridZoom value is ignored.
@MainActor
final class DensityStoreTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suite: String!

    override func setUp() {
        super.setUp()
        suite = "DensityStoreTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    func testMinimumCardWidths() {
        XCTAssertEqual(Density.s.minCard, 104)
        XCTAssertEqual(Density.m.minCard, 148)
        XCTAssertEqual(Density.l.minCard, 200)
    }

    func testDefaultsToMedium() {
        XCTAssertEqual(DensityStore(defaults: defaults).density, .m)
        XCTAssertEqual(Density.parse("X"), .m)
        XCTAssertEqual(Density.parse(nil), .m)
    }

    func testRemembersChoice() {
        let store = DensityStore(defaults: defaults)
        store.density = .l
        XCTAssertEqual(defaults.string(forKey: "ui.density"), "L")
        XCTAssertEqual(DensityStore(defaults: defaults).density, .l)
    }

    func testOldGridZoomColumnsAreIgnored() {
        defaults.set(6, forKey: "library.gridColumns")
        let store = DensityStore(defaults: defaults)
        XCTAssertEqual(store.density, .m)
        // And left alone: the old zoom still reads it until the screens move over.
        XCTAssertEqual(defaults.integer(forKey: "library.gridColumns"), 6)
    }
}
