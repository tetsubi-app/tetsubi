import XCTest
@testable import JellyfinMusicCore

/// Now Playing's own scale `s` (`NowPlayingView.scale`): min(width / 800,
/// height / 600), clamped to 1.0…1.6, rounded down to 0.05, and 1 whenever the
/// layout is one column. Kotlin's `NowPlayingLayout.scale` has the same tests.
final class NowPlayingScaleTests: XCTestCase {

    private func s(_ w: CGFloat, _ h: CGFloat, wide: Bool = true) -> CGFloat {
        NowPlayingView.scale(width: w, height: h, wide: wide)
    }

    func testTheExamples() {
        XCTAssertEqual(s(1100, 760), 1.25, accuracy: 0.0001, "the Mac and Linux default window")
        XCTAssertEqual(s(1069, 668), 1.10, accuracy: 0.0001, "a Pad Mini on its side")
        XCTAssertEqual(s(1600, 1000), 1.6, accuracy: 0.0001)
        XCTAssertEqual(s(2560, 1400), 1.6, accuracy: 0.0001)
    }

    func testIPads() {
        XCTAssertEqual(s(1024, 768), 1.25, accuracy: 0.0001, "an iPad on its side")   // 1.28 → 1.25
        XCTAssertEqual(s(1366, 1024), 1.6, accuracy: 0.0001, "a 13-inch iPad on its side")
        XCTAssertEqual(s(1180, 820), 1.35, accuracy: 0.0001, "an iPad Air on its side")   // 1.3667
    }

    func testOneColumnIsAlwaysOne() {
        XCTAssertEqual(s(393, 852, wide: false), 1.0, "an iPhone")
        XCTAssertEqual(s(2560, 1400, wide: false), 1.0, "a big window, but one column")
        XCTAssertEqual(s(2560, 1400, wide: NowPlayingView.isWide(width: 2560, height: 1400)), 1.6)
        XCTAssertEqual(s(932, 430, wide: NowPlayingView.isWide(width: 932, height: 430)), 1.0, "an iPhone on its side")
    }

    func testClampsToOneAndTheMaximum() {
        XCTAssertEqual(s(640, 520), 1.0, accuracy: 0.0001, "the smallest wide layout")
        XCTAssertEqual(s(2000, 590), 1.0, accuracy: 0.0001, "wide but short")
        XCTAssertEqual(s(800, 600), 1.0, accuracy: 0.0001)
        XCTAssertEqual(s(6000, 4000), 1.6, accuracy: 0.0001)
        XCTAssertEqual(s(0, 0), 1.0)
    }

    func testTheSmallerRatioWins() {
        XCTAssertEqual(s(1200, 660), 1.10, accuracy: 0.0001)   // 1.5 and 1.1
        XCTAssertEqual(s(880, 1200), 1.10, accuracy: 0.0001)   // 1.1 and 2.0
    }

    func testRoundsDownToSteps() {
        XCTAssertEqual(s(919, 2000), 1.10, accuracy: 0.0001)   // 1.14875
        XCTAssertEqual(s(920, 2000), 1.15, accuracy: 0.0001)   // exactly 1.15
        XCTAssertEqual(s(1000, 2000), s(1003, 2000), accuracy: 0.0001, "a few points of resizing change nothing")
        for w in stride(from: 800.0, through: 1400.0, by: 7.0) {
            let v = s(CGFloat(w), 2000)
            XCTAssertEqual((v * 20).rounded(), v * 20, accuracy: 0.0001, "\(w) gives a 0.05 step")
        }
    }

    func testTheMacsUIScaleIsNotApplied() {
        let saved = UIScale.shared.factor
        defer { UIScale.shared.factor = saved }
        UIScale.shared.factor = 1.5
        XCTAssertEqual(s(1100, 760), 1.25, accuracy: 0.0001)
        XCTAssertEqual(s(1100, 760, wide: false), 1.0)
    }

    #if os(macOS)
    func testTheMacSheetSetsTheScaleNotTheWindow() {
        // The default 1100 × 760 window opens a 940 × 700 sheet; s comes from the sheet.
        let windowS = NowPlayingView.scale(width: 1100, height: 760, wide: true)
        let w = NowPlayingView.macSheetIdealWidth(windowWidth: 1100, s: windowS)
        let h = NowPlayingView.macSheetIdealHeight(windowHeight: 760, s: windowS)
        XCTAssertEqual(w, 940)
        XCTAssertEqual(h, 700)
        let sheetS = s(w, h, wide: NowPlayingView.isWide(width: w, height: h))
        XCTAssertEqual(sheetS, 1.15, accuracy: 0.0001)
        // The cover then fits: 700 - 430 × 1.15 = 205.5.
        let left = NowPlayingView.wideLeftColumn(width: w, s: sheetS)
        XCTAssertEqual(NowPlayingView.wideArtSize(columnWidth: left, height: h, visualiser: false, s: sheetS),
                       205.5, accuracy: 0.01)
    }

    func testABigMacWindowStillReachesTheMaximum() {
        // 2560 × 1400 window → sheet 1568 (980 × 1.6) × 1320 → s 1.6.
        let windowS = NowPlayingView.scale(width: 2560, height: 1400, wide: true)
        let w = NowPlayingView.macSheetIdealWidth(windowWidth: 2560, s: windowS)
        let h = NowPlayingView.macSheetIdealHeight(windowHeight: 1400, s: windowS)
        XCTAssertEqual(s(w, h), 1.6, accuracy: 0.0001)
    }
    #endif
}
