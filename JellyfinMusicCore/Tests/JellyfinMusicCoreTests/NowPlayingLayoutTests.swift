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

    func testTheMacsScaledWindowNeedsProportionallyMore() {
        let saved = UIScale.shared.factor
        defer { UIScale.shared.factor = saved }
        UIScale.shared.factor = 1.5
        XCTAssertFalse(NowPlayingView.isWide(width: 950, height: 1000))   // 640 × 1.5 = 960
        XCTAssertFalse(NowPlayingView.isWide(width: 1000, height: 770))   // 520 × 1.5 = 780
        XCTAssertTrue(NowPlayingView.isWide(width: 960, height: 780))
    }

    func testTheCoverShrinksSoTheControlsStayOnScreen() {
        let saved = UIScale.shared.factor
        defer { UIScale.shared.factor = saved }
        UIScale.shared.factor = 1.0
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 460, height: 800, visualiser: false), 360)  // room: capped
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 460, height: 700, visualiser: false), 270)  // 700 - 430
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 460, height: 700, visualiser: true), 190)   // visualiser takes 80
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 460, height: 300, visualiser: false), 140)  // never tiny
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: 320, height: 900, visualiser: false), 264)  // narrow column: 320 - 56
    }

    #if os(macOS)
    func testMacSheetOpensWideOnlyWhenTheWindowHasRoom() {
        let saved = UIScale.shared.factor
        defer { UIScale.shared.factor = saved }
        UIScale.shared.factor = 1.0
        XCTAssertEqual(NowPlayingView.macSheetIdealWidth(windowWidth: 790), 460)    // too small for two columns
        XCTAssertEqual(NowPlayingView.macSheetIdealWidth(windowWidth: 820), 660)    // the smallest window
        XCTAssertEqual(NowPlayingView.macSheetIdealWidth(windowWidth: 1100), 940)   // the default window
        XCTAssertEqual(NowPlayingView.macSheetIdealWidth(windowWidth: 2000), 980)   // capped
        XCTAssertEqual(NowPlayingView.macSheetIdealHeight(windowHeight: 760), 700)  // never below 700
        XCTAssertEqual(NowPlayingView.macSheetIdealHeight(windowHeight: 877), 797)  // window minus 80
        XCTAssertEqual(NowPlayingView.macSheetIdealHeight(windowHeight: 1500), 880) // capped
    }
    #endif
}
