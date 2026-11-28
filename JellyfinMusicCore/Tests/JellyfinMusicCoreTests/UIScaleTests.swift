import XCTest
@testable import JellyfinMusicCore

/// The Mac's window-size factor (`UIScale`): 1.0 at the default 1100 × 760
/// window, up to 1.6, in steps of 0.05; always 1.0 on iOS.
final class UIScaleTests: XCTestCase {

    private func factor(_ w: CGFloat, _ h: CGFloat, scales: Bool = true) -> CGFloat {
        UIScale.factor(for: CGSize(width: w, height: h), scales: scales)
    }

    func testDefaultWindowIsOne() {
        XCTAssertEqual(factor(1100, 760), 1.0, accuracy: 0.0001)
    }

    func testSmallerWindowsClampToOne() {
        XCTAssertEqual(factor(820, 560), 1.0, accuracy: 0.0001, "the Mac's minimum window")
        XCTAssertEqual(factor(2000, 600), 1.0, accuracy: 0.0001, "wide but short")
    }

    func testBigWindowsClampToMax() {
        XCTAssertEqual(factor(3440, 1415), 1.6, accuracy: 0.0001, "3440 × 1440 screen, window filled")
        XCTAssertEqual(factor(6000, 4000), 1.6, accuracy: 0.0001)
    }

    func testTheSmallerRatioWins() {
        // 1650/1100 = 1.5, 950/760 = 1.25
        XCTAssertEqual(factor(1650, 950), 1.25, accuracy: 0.0001)
        // 1320/1100 = 1.2, 1140/760 = 1.5
        XCTAssertEqual(factor(1320, 1140), 1.2, accuracy: 0.0001)
    }

    func testRoundsDownToSteps() {
        // 1.149… → 1.10, exactly 1.15 → 1.15
        XCTAssertEqual(factor(1264, 2000), 1.10, accuracy: 0.0001)
        XCTAssertEqual(factor(1265, 2000), 1.15, accuracy: 0.0001)
        // A pixel or two of resizing doesn't change it.
        XCTAssertEqual(factor(1300, 2000), factor(1302, 2000), accuracy: 0.0001)
        for w in stride(from: 1100.0, through: 2000.0, by: 7.0) {
            let f = factor(CGFloat(w), 2000)
            XCTAssertEqual((f * 20).rounded(), f * 20, accuracy: 0.0001, "\(w) gives a 0.05 step")
        }
    }

    func testIOSIsAlwaysOne() {
        XCTAssertEqual(factor(1100, 760, scales: false), 1.0)
        XCTAssertEqual(factor(3440, 1440, scales: false), 1.0)
        XCTAssertEqual(factor(1366, 1024, scales: false), 1.0, "iPad Pro landscape")
    }

    func testOnlyTheMacScales() {
        #if os(macOS)
        XCTAssertTrue(UIScale.platformScales)
        #else
        XCTAssertFalse(UIScale.platformScales)
        #endif
    }

    func testEmptySizeIsOne() {
        XCTAssertEqual(factor(0, 0), 1.0)
    }

    @MainActor
    func testUpdateSetsTheSharedFactorAndScaledUsesIt() {
        let scale = UIScale.shared
        defer { scale.update(for: UIScale.baseSize) }
        scale.update(for: CGSize(width: 1650, height: 1140))
        let expected: CGFloat = UIScale.platformScales ? 1.5 : 1.0
        XCTAssertEqual(scale.factor, expected, accuracy: 0.0001)
        XCTAssertEqual(140.scaled, 140 * expected, accuracy: 0.0001)
        XCTAssertEqual(CGFloat(12).scaled, 12 * expected, accuracy: 0.0001)
        scale.update(for: UIScale.baseSize)
        XCTAssertEqual(scale.factor, 1.0, accuracy: 0.0001)
    }
}
