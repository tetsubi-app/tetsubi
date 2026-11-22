// Recently Played ▸ Export grid: which albums, tile maths, labels, sizes,
// remembered settings, and the rendered 1200 px picture.

import XCTest
import CoreGraphics
import ImageIO
@testable import JellyfinMusicCore

final class GridExportTests: XCTestCase {

    private func album(_ id: String, _ name: String) -> JellyfinAlbum {
        JellyfinAlbum(id: id, name: name, serverId: nil, premiereDate: nil, productionYear: nil,
                      albumArtists: nil, album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
    }

    private func albums(_ count: Int) -> [JellyfinAlbum] {
        (0..<count).map { album("a\($0)", "Album \($0)") }
    }

    // MARK: Which albums

    func testTakesTheMostRecentInOrder() {
        let picked = GridExport.albums(albums(30), size: 3)
        XCTAssertEqual(picked?.map(\.id), (0..<9).map { "a\($0)" })
        XCTAssertEqual(GridExport.albums(albums(30), size: 5)?.count, 25)
        XCTAssertEqual(GridExport.albums(albums(16), size: 4)?.last?.id, "a15")
    }

    func testEachAlbumOnce() {
        var list = albums(10)
        list.insert(album("a0", "Album 0 again"), at: 2)
        let picked = GridExport.albums(list, size: 3)
        XCTAssertEqual(picked?.map(\.id), (0..<9).map { "a\($0)" })
        // 9 distinct needed: 8 distinct plus a repeat isn't enough.
        var short = albums(8)
        short.append(album("a1", "dup"))
        XCTAssertNil(GridExport.albums(short, size: 3))
    }

    func testTooFew() {
        XCTAssertNil(GridExport.albums(albums(8), size: 3))
        XCTAssertNil(GridExport.albums(albums(24), size: 5))
        XCTAssertFalse(GridExport.canExport(albumCount: 8))
        XCTAssertTrue(GridExport.canExport(albumCount: 9))
        XCTAssertEqual(GridExport.tooFewMessage, "Play at least 9 albums to export a grid.")
    }

    // MARK: Sizes

    func testAvailability() {
        XCTAssertTrue(GridExport.isAvailable(size: 3, albumCount: 9))
        XCTAssertFalse(GridExport.isAvailable(size: 4, albumCount: 15))
        XCTAssertTrue(GridExport.isAvailable(size: 4, albumCount: 16))
        XCTAssertFalse(GridExport.isAvailable(size: 5, albumCount: 24))
        XCTAssertTrue(GridExport.isAvailable(size: 5, albumCount: 25))
    }

    func testEffectiveSize() {
        XCTAssertEqual(GridExport.effectiveSize(chosen: 5, albumCount: 50), 5)
        XCTAssertEqual(GridExport.effectiveSize(chosen: 3, albumCount: 50), 3)
        XCTAssertEqual(GridExport.effectiveSize(chosen: 5, albumCount: 20), 4)
        XCTAssertEqual(GridExport.effectiveSize(chosen: 5, albumCount: 12), 3)
        XCTAssertEqual(GridExport.effectiveSize(chosen: 4, albumCount: 9), 3)
        XCTAssertNil(GridExport.effectiveSize(chosen: 5, albumCount: 8))
        XCTAssertEqual(GridExport.effectiveSize(chosen: 7, albumCount: 50), 5)
    }

    // MARK: Albums the page shows

    func testPageShowsExactlyTheExportedAlbums() {
        func shown(_ count: Int, _ chosen: Int) -> [String] {
            GridExport.shownAlbums(albums(count), chosen: chosen).map(\.id)
        }
        // Enough for every size: n² for the chosen n.
        XCTAssertEqual(shown(60, 3), (0..<9).map { "a\($0)" })
        XCTAssertEqual(shown(60, 4), (0..<16).map { "a\($0)" })
        XCTAssertEqual(shown(60, 5), (0..<25).map { "a\($0)" })
        XCTAssertEqual(shown(25, 5).count, 25)
        // Too few for the choice: the biggest that fits.
        XCTAssertEqual(shown(24, 5).count, 16)
        XCTAssertEqual(shown(15, 5).count, 9)
        XCTAssertEqual(shown(15, 4).count, 9)
        XCTAssertEqual(shown(20, 4).count, 16)
        XCTAssertEqual(shown(20, 3).count, 9)
        // Under 9 (no export): all of them.
        XCTAssertEqual(shown(8, 5), (0..<8).map { "a\($0)" })
        XCTAssertEqual(shown(1, 3), ["a0"])
        XCTAssertEqual(shown(0, 5), [])
        // A repeat counts once, at its most recent place.
        var list = albums(10)
        list.insert(list[0], at: 2)
        XCTAssertEqual(GridExport.shownAlbums(list, chosen: 3).map(\.id), (0..<9).map { "a\($0)" })
    }

    @MainActor
    func testGridFollowsTheChosenSize() {
        let defaults = UserDefaults(suiteName: "GridExportTests.follow")!
        defaults.removePersistentDomain(forName: "GridExportTests.follow")
        let settings = GridExportSettings(defaults: defaults)
        let list = albums(30)
        XCTAssertEqual(GridExport.shownAlbums(list, chosen: settings.size).count, 25)
        settings.size = 3
        XCTAssertEqual(GridExport.shownAlbums(list, chosen: settings.size).count, 9)
        settings.size = 4
        XCTAssertEqual(GridExport.shownAlbums(list, chosen: settings.size).count, 16)
        XCTAssertEqual(defaults.integer(forKey: "recentlyPlayed.exportSize"), 4)
        defaults.removePersistentDomain(forName: "GridExportTests.follow")
    }

    /// The page's grid is as many across as the export's size; below 9
    /// albums the Library's usual columns (nil).
    func testPageColumnsFollowTheExportSize() {
        let list = albums(30)
        XCTAssertEqual(GridExport.gridColumns(list, chosen: 3), 3)
        XCTAssertEqual(GridExport.gridColumns(list, chosen: 4), 4)
        XCTAssertEqual(GridExport.gridColumns(list, chosen: 5), 5)
        XCTAssertEqual(GridExport.gridColumns(albums(12), chosen: 5), 3)   // the size used
        XCTAssertNil(GridExport.gridColumns(albums(8), chosen: 5))
        XCTAssertNil(GridExport.gridColumns(albums(8) + albums(8), chosen: 3))   // each album once
    }

    /// Covers fill exactly n columns on any width; the text grows with
    /// the card but never below a phone's.
    func testFixedColumnsFillTheirColumns() {
        let phone3 = LibraryView.gridMetrics(width: 393 - 8, columns: 3, displayScale: 3)
        XCTAssertEqual(phone3.columns, 3)
        XCTAssertEqual(phone3.spacing, 12)
        XCTAssertEqual(phone3.cardWidth, (385 - 24) / 3, accuracy: 0.001)
        XCTAssertEqual(phone3.imageSize, 600)

        let phone5 = LibraryView.gridMetrics(width: 360 - 8, columns: 5)
        XCTAssertEqual(phone5.columns, 5)
        XCTAssertEqual(phone5.cardWidth, (352 - 48) / 5, accuracy: 0.001)
        XCTAssertEqual(phone5.textScale, 1)   // the phone's text, cut short

        let mac5 = LibraryView.gridMetrics(width: 1000, columns: 5)
        XCTAssertEqual(mac5.cardWidth, (1000 - 48) / 5, accuracy: 0.001)
        XCTAssertEqual(mac5.textScale, (1000 - 48) / 5 / 120, accuracy: 0.001)

        XCTAssertEqual(LibraryView.gridMetrics(width: 3000, columns: 3).textScale, 3)   // at most 3×
        // The Mac's UI scale grows the gap and the card the text is measured against.
        let scaled = LibraryView.gridMetrics(width: 1000, scale: 1.5, columns: 4)
        XCTAssertEqual(scaled.spacing, 18)
        XCTAssertEqual(scaled.textScale, (1000 - 54) / 4 / 180, accuracy: 0.001)
    }

    // MARK: Tiles

    func testTileSides() {
        XCTAssertEqual(GridExport.tileSide(size: 3), 400)
        XCTAssertEqual(GridExport.tileSide(size: 4), 300)
        XCTAssertEqual(GridExport.tileSide(size: 5), 240)
    }

    func testTileRects() {
        XCTAssertEqual(GridExport.tileRect(index: 0, size: 3), CGRect(x: 0, y: 0, width: 400, height: 400))
        XCTAssertEqual(GridExport.tileRect(index: 2, size: 3), CGRect(x: 800, y: 0, width: 400, height: 400))
        XCTAssertEqual(GridExport.tileRect(index: 3, size: 3), CGRect(x: 0, y: 400, width: 400, height: 400))
        XCTAssertEqual(GridExport.tileRect(index: 8, size: 3), CGRect(x: 800, y: 800, width: 400, height: 400))
        XCTAssertEqual(GridExport.tileRect(index: 5, size: 4), CGRect(x: 300, y: 300, width: 300, height: 300))
        XCTAssertEqual(GridExport.tileRect(index: 24, size: 5), CGRect(x: 960, y: 960, width: 240, height: 240))
    }

    func testAspectFill() {
        let tile = CGRect(x: 0, y: 0, width: 400, height: 400)
        XCTAssertEqual(GridExport.aspectFillRect(imageSize: CGSize(width: 600, height: 600), in: tile), tile)
        XCTAssertEqual(GridExport.aspectFillRect(imageSize: CGSize(width: 1200, height: 600), in: tile),
                       CGRect(x: -200, y: 0, width: 800, height: 400))
        XCTAssertEqual(GridExport.aspectFillRect(imageSize: CGSize(width: 300, height: 600), in: tile),
                       CGRect(x: 0, y: -200, width: 400, height: 800))
    }

    // MARK: Labels

    func testLabelMetrics() {
        let big = GridExport.labelMetrics(tileSide: 400)
        XCTAssertEqual(big.inset, 8, accuracy: 0.001)   // 2%
        XCTAssertEqual(big.titleSize, 22, accuracy: 0.001)
        XCTAssertEqual(big.artistSize, 18, accuracy: 0.001)
        XCTAssertEqual(big.maxWidth, 384, accuracy: 0.001)
        XCTAssertEqual(big.shadowOffset, 2)

        let mid = GridExport.labelMetrics(tileSide: 300)
        XCTAssertEqual(mid.inset, 6, accuracy: 0.001)
        XCTAssertEqual(mid.titleSize, 16.5, accuracy: 0.001)
        XCTAssertEqual(mid.artistSize, 13.5, accuracy: 0.001)
        XCTAssertEqual(mid.maxWidth, 288, accuracy: 0.001)
        XCTAssertEqual(mid.shadowOffset, 2)   // 1.5 rounds up

        let small = GridExport.labelMetrics(tileSide: 240)
        XCTAssertEqual(small.inset, 4.8, accuracy: 0.001)
        XCTAssertEqual(small.titleSize, 13.2, accuracy: 0.001)
        XCTAssertEqual(small.artistSize, 10.8, accuracy: 0.001)
        XCTAssertEqual(small.maxWidth, 230.4, accuracy: 0.001)
        XCTAssertEqual(small.shadowOffset, 1)

        // At least a pixel, however small.
        XCTAssertEqual(GridExport.labelMetrics(tileSide: 50).shadowOffset, 1)
        XCTAssertEqual(GridExport.shadowOpacity, 0.8)
    }

    func testFileName() {
        let date = Date(timeIntervalSince1970: 1_795_356_332)   // 2026-11-22 14:05:32 UTC
        XCTAssertEqual(GridExport.fileName(date: date, timeZone: TimeZone(identifier: "UTC")!),
                       "Tetsubi Recently Played 2026-11-22 14.05.32.png")
        // The device's time zone, 24-hour, past midnight into the next day.
        XCTAssertEqual(GridExport.fileName(date: date, timeZone: TimeZone(identifier: "Asia/Tokyo")!),
                       "Tetsubi Recently Played 2026-11-22 23.05.32.png")
        XCTAssertEqual(GridExport.fileName(date: date, timeZone: TimeZone(identifier: "Pacific/Auckland")!),
                       "Tetsubi Recently Played 2026-11-23 03.05.32.png")
        XCTAssertFalse(GridExport.fileName(date: date).contains(":"))
    }

    // MARK: Remembered settings

    @MainActor
    func testSettingsDefaultsAndMemory() {
        let suite = "GridExportTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { UserDefaults().removePersistentDomain(forName: suite) }

        let fresh = GridExportSettings(defaults: defaults)
        XCTAssertEqual(fresh.size, 5)
        XCTAssertFalse(fresh.showsLabels)

        fresh.size = 4
        fresh.showsLabels = true
        XCTAssertEqual(defaults.integer(forKey: "recentlyPlayed.exportSize"), 4)
        XCTAssertTrue(defaults.bool(forKey: "recentlyPlayed.exportLabels"))

        let again = GridExportSettings(defaults: defaults)
        XCTAssertEqual(again.size, 4)
        XCTAssertTrue(again.showsLabels)

        defaults.set(9, forKey: "recentlyPlayed.exportSize")
        XCTAssertEqual(GridExportSettings(defaults: defaults).size, 5)
    }

    // MARK: Rendering

    private func solid(_ r: UInt8, _ g: UInt8, _ b: UInt8, width: Int = 64, height: Int = 64) -> CGImage {
        let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(CGColor(srgbRed: CGFloat(r) / 255, green: CGFloat(g) / 255, blue: CGFloat(b) / 255, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        return ctx.makeImage()!
    }

    /// RGBA pixels, row 0 at the top.
    private func pixels(_ image: CGImage) -> (Int, Int, [UInt8]) {
        let w = image.width, h = image.height
        var buffer = [UInt8](repeating: 0, count: w * h * 4)
        buffer.withUnsafeMutableBytes { raw in
            let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        return (w, h, buffer)
    }

    private func pixel(_ p: (Int, Int, [UInt8]), _ x: Int, _ y: Int) -> [UInt8] {
        let i = (y * p.0 + x) * 4
        return Array(p.2[i..<i + 3])
    }

    private let colours: [(UInt8, UInt8, UInt8)] = [
        (255, 0, 0), (0, 255, 0), (0, 0, 255),
        (255, 255, 0), (0, 255, 255), (255, 0, 255),
        (200, 100, 50), (50, 100, 200), (128, 128, 128),
    ]

    private func tiles(labels: Bool = false) -> [GridExport.Tile] {
        colours.enumerated().map { i, c in
            GridExport.Tile(cover: solid(c.0, c.1, c.2, width: i == 4 ? 128 : 64),
                            title: "A rather long album title that will not fit \(i)", artist: "Artist \(i)")
        }
    }

    func testRendersTilesAt1200() throws {
        let image = try XCTUnwrap(GridExport.render(tiles: tiles(), size: 3, labels: false))
        XCTAssertEqual(image.width, 1200)
        XCTAssertEqual(image.height, 1200)
        let p = pixels(image)
        for (i, c) in colours.enumerated() {
            let rect = GridExport.tileRect(index: i, size: 3)
            XCTAssertEqual(pixel(p, Int(rect.midX), Int(rect.midY)), [c.0, c.1, c.2], "tile \(i)")
        }
        // No gaps: the tile edges are cover colour too.
        XCTAssertEqual(pixel(p, 399, 399), [255, 0, 0])
        XCTAssertEqual(pixel(p, 400, 0), [0, 255, 0])
    }

    func testPNGIs1200() throws {
        let data = try XCTUnwrap(GridExport.renderPNG(tiles: tiles(), size: 4, labels: true))
        let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        let decoded = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(decoded.width, 1200)
        XCTAssertEqual(decoded.height, 1200)
    }

    func testLabelsDrawInTheTopLeftCorner() throws {
        let plain = pixels(try XCTUnwrap(GridExport.render(tiles: tiles(), size: 3, labels: false)))
        let labelled = pixels(try XCTUnwrap(GridExport.render(tiles: tiles(), size: 3, labels: true)))
        let m = GridExport.labelMetrics(tileSide: 400)
        var changed = 0
        for y in Int(m.inset)..<Int(m.inset + m.titleSize + m.artistSize + 8) {
            for x in Int(m.inset)..<Int(400 - m.inset) where pixel(labelled, x, y) != [255, 0, 0] {
                changed += 1
            }
        }
        XCTAssertGreaterThan(changed, 100)
        // White text and its black shadow are both there.
        let region = (Int(m.inset)..<60).flatMap { y in (Int(m.inset)..<300).map { pixel(labelled, $0, y) } }
        XCTAssertTrue(region.contains { $0[0] > 240 && $0[1] > 240 && $0[2] > 240 })
        XCTAssertTrue(region.contains { $0[0] < 80 && $0[1] < 10 && $0[2] < 10 })
        // Nothing outside the label area changes.
        XCTAssertEqual(pixel(labelled, 200, 300), pixel(plain, 200, 300))
        XCTAssertEqual(pixel(labelled, 390, 390), [255, 0, 0])
    }

    func testMissingCoverIsThePlaceholder() throws {
        var list = tiles()
        list[4] = GridExport.Tile(cover: nil, title: "", artist: "")
        let p = pixels(try XCTUnwrap(GridExport.render(tiles: list, size: 3, labels: false)))
        let rect = GridExport.tileRect(index: 4, size: 3)
        // The dark tile near its corner (the note is in the middle).
        let corner = pixel(p, Int(rect.minX) + 10, Int(rect.minY) + 10)
        XCTAssertEqual(Int(corner[0]), 45, accuracy: 3)
        XCTAssertEqual(corner[0], corner[1])
        // The note's lighter pixels somewhere in the middle.
        let middle = (Int(rect.midY) - 50..<Int(rect.midY) + 50).flatMap { y in
            (Int(rect.midX) - 50..<Int(rect.midX) + 50).map { pixel(p, $0, y) }
        }
        XCTAssertTrue(middle.contains { $0[0] > 100 })
    }
}
