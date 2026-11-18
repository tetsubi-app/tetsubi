import XCTest
@testable import JellyfinMusicCore

/// Wide layouts: cards per row (3…10, nil = automatic), each card filling
/// its column, text growing with the card, cover size from its pixels.
final class GridMetricsTests: XCTestCase {

    /// Phones keep today's grid whatever the setting says.
    func testPhoneUnchanged() {
        let m = LibraryView.gridMetrics(width: 393 - 4 - 22, wide: false, chosen: 5, displayScale: 3)
        XCTAssertEqual(m.columns, 3)
        XCTAssertEqual(m.spacing, 3.5, accuracy: 0.01)
        XCTAssertEqual(m.cardWidth, 120)
        XCTAssertEqual(m.textScale, 1)
        XCTAssertEqual(m.imageSize, 300)
    }

    /// Mac's window scale: the phone-style card still grows with it.
    func testNotWideFollowsScale() {
        let m = LibraryView.gridMetrics(width: 1000, scale: 1.5, wide: false)
        XCTAssertEqual(m.cardWidth, 180)
        XCTAssertEqual(m.columns, LibraryView.gridLayout(width: 1000, scale: 1.5).columns)
    }

    func testAutomaticIsTodaysCount() {
        let m = LibraryView.gridMetrics(width: 1000, wide: true)
        XCTAssertEqual(m.columns, 7)
        XCTAssertEqual(m.spacing, 12)
    }

    func testAutomaticClampedToThree() {
        XCTAssertEqual(LibraryView.gridLayout(width: 300).columns, 2)
        XCTAssertEqual(LibraryView.gridMetrics(width: 300, wide: true).columns, 3)
    }

    func testAutomaticClampedToTen() {
        XCTAssertGreaterThan(LibraryView.gridLayout(width: 2000).columns, 10)
        XCTAssertEqual(LibraryView.gridMetrics(width: 2000, wide: true).columns, 10)
    }

    func testChosenCountWins() {
        XCTAssertEqual(LibraryView.gridMetrics(width: 1000, wide: true, chosen: 4).columns, 4)
    }

    func testChosenCountKeptInRange() {
        XCTAssertEqual(LibraryView.gridMetrics(width: 1000, wide: true, chosen: 12).columns, 10)
        XCTAssertEqual(LibraryView.gridMetrics(width: 1000, wide: true, chosen: 1).columns, 3)
    }

    /// Cards fill the row exactly; at 10 columns a wider window means
    /// bigger cards, not an 11th column.
    func testCardsFillTheWidth() {
        for width: CGFloat in [700, 1000, 1600, 2400] {
            for chosen in 3...10 {
                let m = LibraryView.gridMetrics(width: width, wide: true, chosen: chosen)
                let used = CGFloat(m.columns) * m.cardWidth + CGFloat(m.columns - 1) * m.spacing
                XCTAssertEqual(used, width, accuracy: 0.001)
            }
        }
        let narrow = LibraryView.gridMetrics(width: 2000, wide: true, chosen: 10)
        let wider = LibraryView.gridMetrics(width: 2600, wide: true, chosen: 10)
        XCTAssertEqual(wider.columns, 10)
        XCTAssertGreaterThan(wider.cardWidth, narrow.cardWidth)
    }

    func testGapGrowsWithMacScale() {
        let m = LibraryView.gridMetrics(width: 1000, scale: 1.6, wide: true, chosen: 4)
        XCTAssertEqual(m.spacing, 19.2, accuracy: 0.001)
        XCTAssertEqual(m.cardWidth, (1000 - 3 * 19.2) / 4, accuracy: 0.001)
    }

    func testTextScalesWithCard() {
        let m = LibraryView.gridMetrics(width: 1000, wide: true)
        XCTAssertEqual(m.textScale, m.cardWidth / 120, accuracy: 0.001)
        // Relative to the Mac's scaled 120pt card.
        let mac = LibraryView.gridMetrics(width: 1000, scale: 1.6, wide: true, chosen: 4)
        XCTAssertEqual(mac.textScale, mac.cardWidth / 192, accuracy: 0.001)
    }

    func testTextNeverSmallerThanPhone() {
        let m = LibraryView.gridMetrics(width: 700, wide: true, chosen: 10)
        XCTAssertLessThan(m.cardWidth, 120)
        XCTAssertEqual(m.textScale, 1)
    }

    func testTextAtMostThreeTimes() {
        XCTAssertEqual(LibraryView.gridMetrics(width: 3000, wide: true, chosen: 3).textScale, 3)
    }

    func testImageSizeRoundsUpToFixedSteps() {
        // ~133pt at 2x = 265px.
        XCTAssertEqual(LibraryView.gridMetrics(width: 1000, wide: true, displayScale: 2).imageSize, 300)
        // ~189pt at 2x = 378px.
        XCTAssertEqual(LibraryView.gridMetrics(width: 2000, wide: true, displayScale: 2).imageSize, 600)
        // 488pt at 2x = 976px.
        XCTAssertEqual(LibraryView.gridMetrics(width: 1488, wide: true, chosen: 3, displayScale: 2).imageSize, 1000)
        // 992pt: 1000px at 1x, more than the largest step at 2x.
        XCTAssertEqual(LibraryView.gridMetrics(width: 3000, wide: true, chosen: 3, displayScale: 1).imageSize, 1000)
        XCTAssertEqual(LibraryView.gridMetrics(width: 3000, wide: true, chosen: 3, displayScale: 2).imageSize, 1500)
    }

    func testZoomSteps() {
        XCTAssertEqual(LibraryView.zoomed(from: 7, in: true), 6)
        XCTAssertEqual(LibraryView.zoomed(from: 7, in: false), 8)
        XCTAssertEqual(LibraryView.zoomed(from: 4, in: true), 3)
        XCTAssertEqual(LibraryView.zoomed(from: 9, in: false), 10)
    }

    /// The buttons go off at the limits.
    func testZoomLimits() {
        XCTAssertNil(LibraryView.zoomed(from: 3, in: true))
        XCTAssertNil(LibraryView.zoomed(from: 10, in: false))
        XCTAssertEqual(LibraryView.zoomed(from: 3, in: false), 4)
        XCTAssertEqual(LibraryView.zoomed(from: 10, in: true), 9)
    }
}

/// Home's sideways rows on wide layouts: the same cards-per-row setting,
/// with a third of the next card peeking in at the edge.
final class HomeRowMetricsTests: XCTestCase {

    /// Phones keep today's 140pt cards whatever the setting says.
    func testPhoneUnchanged() {
        let m = LibraryView.homeRowMetrics(width: 393, wide: false, chosen: 5, displayScale: 3)
        XCTAssertEqual(m.cardWidth, 140)
        XCTAssertEqual(m.spacing, 12)
        XCTAssertEqual(m.textScale, 1)
        XCTAssertEqual(m.imageSize, 300)
        // The Mac's window scale still grows the fixed card.
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 1000, scale: 1.5, wide: false).cardWidth, 210)
    }

    /// N whole cards and their gaps, then a third of a card, fill the row
    /// after the leading inset.
    func testPeekOfOneThirdCard() {
        for width: CGFloat in [700, 1024, 1600, 2400] {
            for chosen in 3...10 {
                let m = LibraryView.homeRowMetrics(width: width, wide: true, chosen: chosen)
                let used = 16 + CGFloat(m.columns) * (m.cardWidth + m.spacing) + m.cardWidth / 3
                XCTAssertEqual(used, width, accuracy: 0.001)
            }
        }
        let m = LibraryView.homeRowMetrics(width: 1016, wide: true, chosen: 4)
        XCTAssertEqual(m.cardWidth, (1000 - 4 * 12) / (4 + 1.0 / 3), accuracy: 0.001)
    }

    /// Automatic: as many of today's cards (140 + 12) as fit whole.
    func testAutomaticIsTodaysWholeCards() {
        // 1000 visible: (1000 + 12) / 152 = 6.66 → 6.
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 1016, wide: true).columns, 6)
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 1016, scale: 1.5, wide: true).columns, 4)
    }

    func testAutomaticClampedToThreeAndTen() {
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 400, wide: true).columns, 3)
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 3000, wide: true).columns, 10)
    }

    func testChosenCountHonouredAndKeptInRange() {
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 1016, wide: true, chosen: 8).columns, 8)
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 1016, wide: true, chosen: 14).columns, 10)
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 1016, wide: true, chosen: 1).columns, 3)
    }

    /// Text grows from 140pt (times the Mac's scale), 1…3×.
    func testTextScale() {
        let m = LibraryView.homeRowMetrics(width: 1600, wide: true, chosen: 4)
        XCTAssertEqual(m.textScale, m.cardWidth / 140, accuracy: 0.001)
        let mac = LibraryView.homeRowMetrics(width: 2000, scale: 1.6, wide: true, chosen: 4)
        XCTAssertEqual(mac.textScale, mac.cardWidth / 224, accuracy: 0.001)
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 800, wide: true, chosen: 10).textScale, 1)
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 4000, wide: true, chosen: 3).textScale, 3)
    }

    func testImageSizeBuckets() {
        // ~107pt at 2x: 214px.
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 1016, wide: true, chosen: 8).imageSize, 300)
        // ~214pt at 2x: 428px.
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 1016, wide: true, chosen: 4).imageSize, 600)
        // ~290pt at 3x: 870px.
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 1016, wide: true, chosen: 3, displayScale: 3).imageSize, 1000)
        // ~750pt at 2x: past the largest step.
        XCTAssertEqual(LibraryView.homeRowMetrics(width: 2600, wide: true, chosen: 3).imageSize, 1500)
    }
}

/// The shared setting: remembered, starts from what's on screen, and
/// only acts while a zoomable grid is showing.
@MainActor
final class GridZoomSettingTests: XCTestCase {

    private var suite: String!
    private var defaults: UserDefaults!

    override func setUp() {
        suite = "GridZoomSettingTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suite)
    }

    func testAutomaticWhenNothingSaved() {
        XCTAssertNil(GridZoom(defaults: defaults).columns)
    }

    func testOffWithNoGridShowing() {
        let zoom = GridZoom(defaults: defaults)
        XCTAssertFalse(zoom.canZoomIn)
        XCTAssertFalse(zoom.canZoomOut)
        zoom.zoomIn()
        XCTAssertNil(zoom.columns)
    }

    /// The first press starts from the automatic count on screen.
    func testFirstPressStartsFromShownCount() {
        let zoom = GridZoom(defaults: defaults)
        zoom.gridAppeared()
        zoom.gridShows(columns: 7)
        zoom.zoomIn()
        XCTAssertEqual(zoom.columns, 6)
        zoom.zoomOut()
        zoom.zoomOut()
        XCTAssertEqual(zoom.columns, 8)
    }

    func testRememberedAcrossLaunches() {
        let zoom = GridZoom(defaults: defaults)
        zoom.gridAppeared()
        zoom.gridShows(columns: 5)
        zoom.zoomOut()
        XCTAssertEqual(defaults.integer(forKey: "library.gridColumns"), 6)
        XCTAssertEqual(GridZoom(defaults: defaults).columns, 6)
    }

    func testDisabledAtLimits() {
        let zoom = GridZoom(defaults: defaults)
        zoom.gridAppeared()
        zoom.gridShows(columns: 4)
        zoom.zoomIn()
        XCTAssertEqual(zoom.columns, 3)
        XCTAssertFalse(zoom.canZoomIn)
        XCTAssertTrue(zoom.canZoomOut)
        zoom.zoomIn()
        XCTAssertEqual(zoom.columns, 3)

        let wide = GridZoom(defaults: defaults)
        wide.gridAppeared()
        for _ in 0..<10 { wide.zoomOut() }
        XCTAssertEqual(wide.columns, 10)
        XCTAssertFalse(wide.canZoomOut)
        XCTAssertTrue(wide.canZoomIn)
    }

    /// A pushed page appears before the one under it goes.
    func testCountsOverlappingGrids() {
        let zoom = GridZoom(defaults: defaults)
        zoom.gridAppeared()
        zoom.gridShows(columns: 6)
        zoom.gridAppeared()
        zoom.gridDisappeared()
        XCTAssertTrue(zoom.canZoomIn)
        zoom.gridDisappeared()
        XCTAssertFalse(zoom.canZoomIn)
    }

    func testOutOfRangeSavedValueIsAutomatic() {
        defaults.set(14, forKey: "library.gridColumns")
        XCTAssertNil(GridZoom(defaults: defaults).columns)
    }
}

/// Library ▸ Artists: grid by default, list remembered on its own key.
@MainActor
final class ArtistsViewModeTests: XCTestCase {

    private let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")

    func testGridByDefaultAndRememberedSeparately() {
        let suite = "ArtistsViewModeTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { UserDefaults().removePersistentDomain(forName: suite) }

        let model = LibraryViewModel(api: api, defaults: defaults)
        XCTAssertEqual(model.artistsDisplayMode, .grid)
        model.artistsDisplayMode = .list
        XCTAssertEqual(defaults.string(forKey: "library.artistsViewMode"), "List")
        XCTAssertNil(defaults.string(forKey: "library.viewMode"))

        let next = LibraryViewModel(api: api, defaults: defaults)
        XCTAssertEqual(next.artistsDisplayMode, .list)
        XCTAssertEqual(next.displayMode, .grid)
    }

    func testUnknownValueFallsBackToGrid() {
        let suite = "ArtistsViewModeTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        defaults.set("Cards", forKey: "library.artistsViewMode")
        XCTAssertEqual(LibraryViewModel(api: api, defaults: defaults).artistsDisplayMode, .grid)
    }
}
