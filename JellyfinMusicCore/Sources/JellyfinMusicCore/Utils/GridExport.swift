// MARK: - GridExport — Recently Played ▸ Export grid: the n×n picture of covers

import Foundation
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// The maths of the exported grid: a 1200 × 1200 px PNG of the n×n most
/// recently played albums' covers, left to right, top to bottom, square
/// tiles with no gaps. Pure, so it's tested; the Kotlin side's
/// `GridExport` matches it number for number.
public enum GridExport {

    /// The picture's side in pixels.
    public static let imageSide = 1200

    /// The grid sizes offered (3×3, 4×4, 5×5).
    public static let sizes = [3, 4, 5]
    /// The size chosen until the user picks another.
    public static let defaultSize = 5
    /// Fewer albums than a 3×3 needs: no export at all.
    public static let minimumAlbums = 9
    /// What the section says instead of its controls then.
    public static let tooFewMessage = "Play at least 9 albums to export a grid."

    /// The size of cover asked of the server: at least the biggest tile (400).
    public static let coverRequestSize = 600

    // MARK: Which albums

    /// The `size`×`size` most recent albums, in order, each album once (by
    /// ID); nil when there aren't enough.
    public static func albums(_ albums: [JellyfinAlbum], size: Int) -> [JellyfinAlbum]? {
        var seen = Set<String>()
        let distinct = albums.filter { seen.insert($0.id).inserted }
        let count = size * size
        guard size > 0, distinct.count >= count else { return nil }
        return Array(distinct.prefix(count))
    }

    /// The albums the page's grid shows: every one in the list, each once (by
    /// ID, at its most recent place). The export's size does not narrow it.
    public static func distinct(_ albums: [JellyfinAlbum]) -> [JellyfinAlbum] {
        var seen = Set<String>()
        return albums.filter { seen.insert($0.id).inserted }
    }

    /// Whether a `size`×`size` grid can be filled from `albumCount` albums.
    public static func isAvailable(size: Int, albumCount: Int) -> Bool {
        albumCount >= size * size
    }

    /// Whether the section offers an export at all (at least 9 albums).
    public static func canExport(albumCount: Int) -> Bool {
        albumCount >= minimumAlbums
    }

    /// The size used: the chosen one when there are enough albums for it,
    /// else the biggest that fits; nil below 9 albums. The remembered
    /// choice isn't changed by this.
    public static func effectiveSize(chosen: Int, albumCount: Int) -> Int? {
        if sizes.contains(chosen), isAvailable(size: chosen, albumCount: albumCount) { return chosen }
        return sizes.last { isAvailable(size: $0, albumCount: albumCount) }
    }

    // MARK: Tiles

    /// A tile's side in pixels: 400, 300 or 240.
    public static func tileSide(size: Int) -> Int { imageSide / size }

    /// Tile `index`'s rectangle in pixels, from the picture's top-left
    /// corner (y downwards): left to right, then top to bottom.
    public static func tileRect(index: Int, size: Int) -> CGRect {
        let side = tileSide(size: size)
        return CGRect(x: (index % size) * side, y: (index / size) * side, width: side, height: side)
    }

    /// Where an image of `imageSize` goes to fill `tile` (aspect fill,
    /// centred; the overflow is clipped by the caller).
    public static func aspectFillRect(imageSize: CGSize, in tile: CGRect) -> CGRect {
        guard imageSize.width > 0, imageSize.height > 0 else { return tile }
        let scale = max(tile.width / imageSize.width, tile.height / imageSize.height)
        let w = imageSize.width * scale, h = imageSize.height * scale
        return CGRect(x: tile.midX - w / 2, y: tile.midY - h / 2, width: w, height: h)
    }

    // MARK: Labels

    /// Album title and artist in a tile's top-left corner, all in pixels.
    public struct LabelMetrics: Equatable, Sendable {
        /// From the tile's top and left edges: 2% of the tile.
        public let inset: CGFloat
        /// Album title (bold): 5.5% of the tile.
        public let titleSize: CGFloat
        /// Artist (regular): 4.5% of the tile.
        public let artistSize: CGFloat
        /// The widest a line may be: the tile less both insets.
        public let maxWidth: CGFloat
        /// The sharp shadow behind the text (no blur), offset this far right
        /// and down: 0.5% of the tile, rounded, at least 1 px.
        public let shadowOffset: CGFloat
    }

    /// The labels' inset from the tile's top and left (and right) edges,
    /// as a share of the tile.
    public static let labelInset: CGFloat = 0.02

    public static func labelMetrics(tileSide side: CGFloat) -> LabelMetrics {
        LabelMetrics(inset: side * labelInset,
                     titleSize: side * 0.055,
                     artistSize: side * 0.045,
                     maxWidth: side - 2 * side * labelInset,
                     shadowOffset: max(1, (side * 0.005).rounded()))
    }

    /// Shadow colour: black at 80%.
    public static let shadowOpacity: CGFloat = 0.8

    // MARK: File name

    /// "Tetsubi Recently Played 2026-11-22 14.05.32.png": 24-hour time in the
    /// device's time zone, dots rather than colons so it's a valid name on
    /// every file system.
    public static func fileName(date: Date = Date(), timeZone: TimeZone = .current) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = timeZone
        f.dateFormat = "yyyy-MM-dd HH.mm.ss"
        return "Tetsubi Recently Played \(f.string(from: date)).png"
    }
}

// MARK: - Rendering

extension GridExport {

    /// One tile's content: the cover (nil draws the no-art placeholder)
    /// and its two text lines.
    public struct Tile: @unchecked Sendable {
        public let cover: CGImage?
        public let title: String
        public let artist: String

        public init(cover: CGImage?, title: String, artist: String) {
            self.cover = cover
            self.title = title
            self.artist = artist
        }
    }

    /// No-art tile fill, sRGB #2D2D2D: about the placeholder's 35% grey
    /// over a black background.
    static let placeholderGrey: CGFloat = 45.0 / 255

    /// Draws the `size`×`size` grid at exactly 1200 × 1200 pixels (scale
    /// 1, whatever the screen). `tiles` beyond size² are ignored and
    /// missing ones get the placeholder. Safe off the main thread.
    public static func render(tiles: [Tile], size: Int, labels: Bool) -> CGImage? {
        let side = imageSide
        guard size > 0,
              let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        ctx.interpolationQuality = .high
        ctx.setFillColor(CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))

        let note = placeholderGlyph(side: CGFloat(tileSide(size: size)) * 0.28)
        for index in 0..<(size * size) {
            // Core Graphics counts y upwards; the tile maths counts down.
            let top = tileRect(index: index, size: size)
            let rect = CGRect(x: top.minX, y: CGFloat(side) - top.maxY, width: top.width, height: top.height)
            let tile = index < tiles.count ? tiles[index] : nil
            ctx.saveGState()
            ctx.clip(to: rect)
            if let cover = tile?.cover {
                ctx.draw(cover, in: aspectFillRect(imageSize: CGSize(width: cover.width, height: cover.height), in: rect))
            } else {
                drawPlaceholder(in: rect, note: note, ctx: ctx)
            }
            if labels, let tile {
                drawLabels(title: tile.title, artist: tile.artist, in: rect, ctx: ctx)
            }
            ctx.restoreGState()
        }
        return ctx.makeImage()
    }

    /// The picture as PNG bytes.
    public static func pngData(_ image: CGImage) -> Data? {
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, nil)
        return CGImageDestinationFinalize(dest) ? data as Data : nil
    }

    /// Renders and encodes in one go.
    public static func renderPNG(tiles: [Tile], size: Int, labels: Bool) -> Data? {
        render(tiles: tiles, size: size, labels: labels).flatMap(pngData)
    }

    // MARK: Placeholder

    private static func drawPlaceholder(in rect: CGRect, note: CGImage?, ctx: CGContext) {
        ctx.setFillColor(CGColor(srgbRed: placeholderGrey, green: placeholderGrey, blue: placeholderGrey, alpha: 1))
        ctx.fill(rect)
        if let note {
            // Fit the glyph in a square 28% of the tile, centred.
            let box = rect.width * 0.28
            let w = CGFloat(note.width), h = CGFloat(note.height)
            let s = min(box / w, box / h)
            ctx.draw(note, in: CGRect(x: rect.midX - w * s / 2, y: rect.midY - h * s / 2, width: w * s, height: h * s))
        } else {
            let font = CTFontCreateWithName("Helvetica" as CFString, rect.width * 0.28, nil)
            let line = textLine("♪", font: font, color: CGColor(gray: 1, alpha: 0.55))
            let bounds = CTLineGetImageBounds(line, ctx)
            ctx.textPosition = CGPoint(x: rect.midX - bounds.midX, y: rect.midY - bounds.midY)
            CTLineDraw(line, ctx)
        }
    }

    /// The app's "music.note" symbol, white at 55%, `side` pixels tall.
    private static func placeholderGlyph(side: CGFloat) -> CGImage? {
        #if canImport(UIKit)
        let config = UIImage.SymbolConfiguration(pointSize: side)
        guard let symbol = UIImage(systemName: "music.note", withConfiguration: config)?
            .withTintColor(UIColor(white: 1, alpha: 0.55), renderingMode: .alwaysOriginal) else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: symbol.size, format: format).image { _ in
            symbol.draw(at: .zero)
        }.cgImage
        #elseif canImport(AppKit)
        let config = NSImage.SymbolConfiguration(pointSize: side, weight: .regular)
            .applying(NSImage.SymbolConfiguration(paletteColors: [NSColor(white: 1, alpha: 0.55)]))
        guard let symbol = NSImage(systemSymbolName: "music.note", accessibilityDescription: nil)?
            .withSymbolConfiguration(config) else { return nil }
        var rect = CGRect(origin: .zero, size: symbol.size)
        return symbol.cgImage(forProposedRect: &rect, context: nil, hints: nil)
        #else
        return nil
        #endif
    }

    // MARK: Labels

    private static func drawLabels(title: String, artist: String, in rect: CGRect, ctx: CGContext) {
        PlexFont.register()
        let m = labelMetrics(tileSide: rect.width)
        let white = CGColor(gray: 1, alpha: 1)
        let titleFont = CTFontCreateWithName("DekiruSans-Bold" as CFString, m.titleSize, nil)
        let artistFont = CTFontCreateWithName("DekiruSans" as CFString, m.artistSize, nil)

        // Sharp: no blur. Core Graphics counts y upwards, so down is negative.
        ctx.setShadow(offset: CGSize(width: m.shadowOffset, height: -m.shadowOffset), blur: 0,
                      color: CGColor(gray: 0, alpha: shadowOpacity))
        // Baselines measured down from the tile's top: title's ascent below
        // the inset, then the artist below the title's descent.
        let titleBaseline = m.inset + CTFontGetAscent(titleFont)
        let artistBaseline = titleBaseline + CTFontGetDescent(titleFont) + CTFontGetLeading(titleFont)
            + CTFontGetAscent(artistFont)
        for (text, font, baseline) in [(title, titleFont, titleBaseline), (artist, artistFont, artistBaseline)]
            where !text.isEmpty {
            let line = truncated(text, font: font, color: white, width: m.maxWidth)
            ctx.textPosition = CGPoint(x: rect.minX + m.inset, y: rect.maxY - baseline)
            CTLineDraw(line, ctx)
        }
    }

    private static func textLine(_ text: String, font: CTFont, color: CGColor) -> CTLine {
        let attrs: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): color,
        ]
        return CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attrs))
    }

    /// One line, cut with "…" to fit `width`.
    private static func truncated(_ text: String, font: CTFont, color: CGColor, width: CGFloat) -> CTLine {
        // One line only: no line breaks from the tags.
        let flat = text.replacingOccurrences(of: "\n", with: " ")
        let line = textLine(flat, font: font, color: color)
        let ellipsis = textLine("…", font: font, color: color)
        return CTLineCreateTruncatedLine(line, Double(width), .end, ellipsis) ?? line
    }
}
