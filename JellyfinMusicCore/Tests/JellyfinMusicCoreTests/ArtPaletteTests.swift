import XCTest
import CoreGraphics
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
}
