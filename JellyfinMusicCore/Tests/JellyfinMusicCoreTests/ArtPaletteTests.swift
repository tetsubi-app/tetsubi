import XCTest
import CoreGraphics
import SwiftUI
@testable import JellyfinMusicCore

final class ArtPaletteTests: XCTestCase {

    /// An image of horizontal bands: (colour, rows).
    private func image(_ bands: [(r: UInt8, g: UInt8, b: UInt8, rows: Int)]) -> CGImage {
        let width = 100, height = bands.reduce(0) { $0 + $1.rows }
        var pixels: [UInt8] = []
        for band in bands {
            for _ in 0..<(band.rows * width) { pixels += [band.r, band.g, band.b, 255] }
        }
        let context = CGContext(data: &pixels, width: width, height: height, bitsPerComponent: 8,
                                bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        return context.makeImage()!
    }

    /// Like the SCREW UP cover: mostly red, dark shadows, a yellow highlight.
    func testPicksTheContrastingHighlightNotTheSecondCommonest() {
        let art = image([(200, 45, 25, 55),    // red
                         (40, 15, 10, 30),     // dark shadow (2nd commonest)
                         (220, 210, 40, 15)])  // yellow highlight
        let accent = ArtPalette.accentColor(of: art)
        XCTAssertNotNil(accent)
        XCTAssertGreaterThan(accent?.green ?? 0, 0.7, "should be the yellow")
        XCTAssertLessThan(accent?.blue ?? 1, 0.3)
    }

    func testSmallSpecksAreIgnored() {
        // 99% blue, 1% orange: too little to count.
        let blue = ArtPalette.Swatch(color: .init(red: 0.1, green: 0.15, blue: 0.8), share: 0.99)
        let orange = ArtPalette.Swatch(color: .init(red: 0.95, green: 0.5, blue: 0.1), share: 0.01)
        XCTAssertNil(ArtPalette.accentColor(in: [blue, orange]))
    }

    func testGreyOnlyArtHasNoAccent() {
        XCTAssertNil(ArtPalette.accentColor(of: image([(30, 30, 30, 60), (200, 200, 200, 40)])))
    }

    func testDarkAccentIsBrightened() {
        let main = ArtPalette.Swatch(color: .init(red: 0.9, green: 0.9, blue: 0.9), share: 0.7)
        let darkRed = ArtPalette.Swatch(color: .init(red: 0.4, green: 0.05, blue: 0.05), share: 0.3)
        let accent = ArtPalette.accentColor(in: [main, darkRed])
        XCTAssertEqual(accent?.brightness ?? 0, ArtPalette.displayBrightness, accuracy: 0.001)
        XCTAssertGreaterThan(accent?.red ?? 0, accent?.green ?? 1)
    }

    /// A near-black cover with a deep-red band (brightness about 0.27, under the old 0.3 floor) gives a lifted red.
    func testDeepRedOnBlackGivesARedAccent() {
        for redRows in [10, 20, 35] {
            let art = image([(10, 10, 12, 100 - redRows), (70, 12, 14, redRows)])
            let accent = ArtPalette.accentColor(of: art)
            XCTAssertNotNil(accent, "\(redRows)% deep red")
            XCTAssertGreaterThan(accent?.red ?? 0, 0.6)
            XCTAssertLessThan(accent?.green ?? 1, 0.4)
            XCTAssertEqual(accent?.brightness ?? 0, ArtPalette.displayBrightness, accuracy: 0.001)
        }
    }

    /// A near-black cover with a dark blue band (brightness about 0.27) gives a lifted blue, as on Android.
    func testDarkBlueCoverGivesABlueAccent() {
        let accent = ArtPalette.accentColor(of: image([(4, 6, 16, 60), (10, 24, 70, 40)]))
        XCTAssertNotNil(accent)
        XCTAssertGreaterThan(accent?.blue ?? 0, accent?.red ?? 1)
        XCTAssertEqual(accent?.brightness ?? 0, ArtPalette.displayBrightness, accuracy: 0.001)
    }

    /// Different covers give different accents (the playlist case: every track its own cover).
    func testAccentChangesWithTheCover() {
        let red = ArtPalette.accentColor(of: image([(10, 10, 12, 80), (70, 12, 14, 20)]))
        let green = ArtPalette.accentColor(of: image([(10, 12, 10, 80), (20, 140, 40, 20)]))
        XCTAssertNotNil(red)
        XCTAssertNotNil(green)
        XCTAssertGreaterThan(red?.red ?? 0, red?.green ?? 1)
        XCTAssertGreaterThan(green?.green ?? 0, green?.red ?? 1)
    }
}

final class NowPlayingAccentTests: XCTestCase {
    func testFallsBackToBrandBlueWithoutArt() {
        XCTAssertEqual(NowPlayingAccent.resolve(nil), TetsubiColors.blue)
    }

    func testUsesTheArtAccentWhenThereIsOne() {
        let art = Color(.sRGB, red: 0.9, green: 0.8, blue: 0.1)
        XCTAssertEqual(NowPlayingAccent.resolve(art), art)
    }
}
