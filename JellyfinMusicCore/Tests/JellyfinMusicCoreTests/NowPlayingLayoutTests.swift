import XCTest
@testable import JellyfinMusicCore

/// Now Playing's layout rules (`NowPlayingLayout`): one column on compact
/// widths and on any iPhone, two columns from a medium width (600), sizes in
/// plain points. Kotlin's `NowPlayingLayout` uses the same numbers.
final class NowPlayingLayoutTests: XCTestCase {

    func testNarrowViewsKeepOneColumn() {
        XCTAssertFalse(NowPlayingLayout.usesTwoColumns(width: 393, isPhone: true))     // an iPhone
        XCTAssertFalse(NowPlayingLayout.usesTwoColumns(width: 466, isPhone: false))    // a narrow window
        XCTAssertFalse(NowPlayingLayout.usesTwoColumns(width: 599, isPhone: false))
    }

    func testAnIphoneOnItsSideKeepsOneColumn() {
        XCTAssertFalse(NowPlayingLayout.usesTwoColumns(width: 932, height: 430, isPhone: true))
    }

    func testWideViewsGetTwoColumns() {
        XCTAssertTrue(NowPlayingLayout.usesTwoColumns(width: 600, isPhone: false))
        XCTAssertTrue(NowPlayingLayout.usesTwoColumns(width: 834, isPhone: false))     // an iPad Pro 11 portrait
        XCTAssertTrue(NowPlayingLayout.usesTwoColumns(width: 1194, isPhone: false))    // landscape
        XCTAssertTrue(NowPlayingLayout.usesTwoColumns(width: 980, isPhone: false))     // a Mac sheet
    }

    func testTheMacsUIScaleDoesNotChangeTheThreshold() {
        let saved = UIScale.shared.factor
        defer { UIScale.shared.factor = saved }
        UIScale.shared.factor = 1.5
        XCTAssertTrue(NowPlayingLayout.usesTwoColumns(width: 600, isPhone: false))
        XCTAssertFalse(NowPlayingLayout.usesTwoColumns(width: 599, isPhone: false))
    }

    func testOneColumnCoverFitsTheWidthAnd40PercentOfTheHeight() {
        XCTAssertEqual(NowPlayingLayout.compactArtSize(width: 393, height: 852), 300)   // up to 300
        XCTAssertEqual(NowPlayingLayout.compactArtSize(width: 320, height: 900), 288)   // width less 2 x 16
        XCTAssertEqual(NowPlayingLayout.compactArtSize(width: 393, height: 600), 240)   // 40% of the height
        XCTAssertEqual(NowPlayingLayout.compactArtSize(width: 932, height: 300), 140)   // never tiny
    }

    func testTheLeftColumnIs46PercentBetween360And560() {
        XCTAssertEqual(NowPlayingLayout.leftColumnWidth(width: 600), 360)
        XCTAssertEqual(NowPlayingLayout.leftColumnWidth(width: 1000), 460, accuracy: 0.01)
        XCTAssertEqual(NowPlayingLayout.leftColumnWidth(width: 2000), 560)
    }

    func testTheCoverShrinksSoTheControlsStayOnScreen() {
        XCTAssertEqual(NowPlayingLayout.wideArtSize(columnWidth: 560, height: 1200, visualiser: false), 420)  // capped
        XCTAssertEqual(NowPlayingLayout.wideArtSize(columnWidth: 460, height: 1200, visualiser: false), 396)  // 460 - 64
        XCTAssertEqual(NowPlayingLayout.wideArtSize(columnWidth: 460, height: 700, visualiser: false), 230)   // 700 - 470
        XCTAssertEqual(NowPlayingLayout.wideArtSize(columnWidth: 460, height: 700, visualiser: true), 150)    // the visualiser takes 80
        XCTAssertEqual(NowPlayingLayout.wideArtSize(columnWidth: 460, height: 300, visualiser: false), 140)   // never tiny
    }

    func testMacSheetOpensWideOnlyWhenTheWindowHasRoom() {
        XCTAssertEqual(NowPlayingLayout.macSheetIdealWidth(windowWidth: 790), 460)     // too small for two columns
        XCTAssertEqual(NowPlayingLayout.macSheetIdealWidth(windowWidth: 860), 700)     // the smallest wide sheet
        XCTAssertEqual(NowPlayingLayout.macSheetIdealWidth(windowWidth: 1100), 940)    // the default window
        XCTAssertEqual(NowPlayingLayout.macSheetIdealWidth(windowWidth: 2000), 1100)   // capped
        XCTAssertEqual(NowPlayingLayout.macSheetIdealHeight(windowHeight: 760), 700)   // never below 700
        XCTAssertEqual(NowPlayingLayout.macSheetIdealHeight(windowHeight: 877), 797)   // window minus 80
        XCTAssertEqual(NowPlayingLayout.macSheetIdealHeight(windowHeight: 1500), 880)  // capped
    }

    func testRepeatIconAndLabelFollowTheMode() {
        XCTAssertEqual(NowPlayingView.repeatIcon(.off), "repeat")
        XCTAssertEqual(NowPlayingView.repeatIcon(.all), "repeat")
        XCTAssertEqual(NowPlayingView.repeatIcon(.one), "repeat.1")
        XCTAssertEqual(NowPlayingView.repeatLabel(.off), "Off")
        XCTAssertEqual(NowPlayingView.repeatLabel(.one), "1")
        XCTAssertEqual(NowPlayingView.repeatLabel(.all), "All")
    }
}

private extension NowPlayingLayout {
    /// The rule does not look at the height (a phone on its side is a phone); this keeps a test's intent readable.
    static func usesTwoColumns(width: CGFloat, height: CGFloat, isPhone: Bool) -> Bool {
        usesTwoColumns(width: width, isPhone: isPhone)
    }
}
