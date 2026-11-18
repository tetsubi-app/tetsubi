import XCTest
@testable import JellyfinMusicCore

/// Now Playing shows cover and info on the left and the track list on the right
/// once the view is wide enough (an unfolded Duo, an iPad, a wide Mac window).
final class NowPlayingLayoutTests: XCTestCase {

    func testNarrowViewsKeepOneColumn() {
        XCTAssertFalse(NowPlayingView.isWide(width: 393, height: 852))   // an iPhone
        XCTAssertFalse(NowPlayingView.isWide(width: 466, height: 678))   // a Duo's cover screen
        XCTAssertFalse(NowPlayingView.isWide(width: 639, height: 900))
    }

    func testAnIphoneOnItsSideKeepsOneColumn() {
        XCTAssertFalse(NowPlayingView.isWide(width: 932, height: 430))
    }

    func testWideViewsGetTwoColumns() {
        XCTAssertTrue(NowPlayingView.isWide(width: 669, height: 951))    // an unfolded Duo
        XCTAssertTrue(NowPlayingView.isWide(width: 640, height: 520))
        XCTAssertTrue(NowPlayingView.isWide(width: 1024, height: 768))   // an iPad on its side
    }

    func testTheMacsUIScaleDoesNotChangeTheThresholds() {
        // Now Playing has its own scale, so the Mac's window factor plays no part.
        let saved = UIScale.shared.factor
        defer { UIScale.shared.factor = saved }
        UIScale.shared.factor = 1.5
        XCTAssertTrue(NowPlayingView.isWide(width: 640, height: 520))
        XCTAssertFalse(NowPlayingView.isWide(width: 639, height: 520))
        XCTAssertFalse(NowPlayingView.isWide(width: 640, height: 519))
    }

    func testTheCoverShrinksSoTheControlsStayOnScreen() {
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 460, height: 800, visualiser: false, s: 1), 360)  // room: capped
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 460, height: 700, visualiser: false, s: 1), 270)  // 700 - 430
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 460, height: 700, visualiser: true, s: 1), 190)   // visualiser takes 80
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 460, height: 300, visualiser: false, s: 1), 140)  // never tiny
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 320, height: 900, visualiser: false, s: 1), 264)  // narrow column: 320 - 56
    }

    func testTheCoverAndColumnGrowWithTheScale() {
        // 1600 × 1000 at s 1.6: the left column is capped at 520 × 1.6 = 832,
        // the cover at 360 × 1.6 = 576, and 1000 - 430 × 1.6 = 312 leaves room for 312.
        XCTAssertEqual(NowPlayingView.wideLeftColumn(width: 1600, s: 1.6), 736, accuracy: 0.01)   // 1600 × 0.46
        XCTAssertEqual(NowPlayingView.wideLeftColumn(width: 2560, s: 1.6), 832, accuracy: 0.01)
        XCTAssertEqual(NowPlayingView.wideLeftColumn(width: 640, s: 1), 320, accuracy: 0.01)
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 832, height: 1400, visualiser: false, s: 1.6), 576, accuracy: 0.01)
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 736, height: 1000, visualiser: false, s: 1.6), 312, accuracy: 0.01)
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 736, height: 600, visualiser: false, s: 1.6), 224, accuracy: 0.01) // never below 140 × 1.6
    }

    #if os(macOS)
    func testMacSheetOpensWideOnlyWhenTheWindowHasRoom() {
        XCTAssertEqual(NowPlayingView.macSheetIdealWidth(windowWidth: 790, s: 1), 460)    // too small for two columns
        XCTAssertEqual(NowPlayingView.macSheetIdealWidth(windowWidth: 820, s: 1), 660)    // the smallest window
        XCTAssertEqual(NowPlayingView.macSheetIdealWidth(windowWidth: 1100, s: 1.25), 940)  // the default window
        XCTAssertEqual(NowPlayingView.macSheetIdealWidth(windowWidth: 2000, s: 1), 980)   // capped
        XCTAssertEqual(NowPlayingView.macSheetIdealWidth(windowWidth: 2000, s: 1.6), 1568, accuracy: 0.01) // capped at 980 × s
        XCTAssertEqual(NowPlayingView.macSheetIdealHeight(windowHeight: 760, s: 1.25), 700)  // never below 700
        XCTAssertEqual(NowPlayingView.macSheetIdealHeight(windowHeight: 877, s: 1), 797)  // window minus 80
        XCTAssertEqual(NowPlayingView.macSheetIdealHeight(windowHeight: 1500, s: 1), 880) // capped
        XCTAssertEqual(NowPlayingView.macSheetIdealHeight(windowHeight: 1500, s: 1.6), 1408, accuracy: 0.01) // capped at 880 × s
    }
    #endif
}
