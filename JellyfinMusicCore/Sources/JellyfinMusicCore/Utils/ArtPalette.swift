// MARK: - ArtPalette — an accent colour picked from album art

import CoreGraphics

/// Picks colours from album art, e.g. to tint controls to match it.
public enum ArtPalette {

    /// An sRGB colour, each channel 0...1.
    public struct RGB: Equatable, Sendable {
        public let red: Double, green: Double, blue: Double
        public init(red: Double, green: Double, blue: Double) {
            self.red = red; self.green = green; self.blue = blue
        }

        /// Straight-line distance in RGB space (0 = same, ~1.73 = black vs white).
        func distance(to other: RGB) -> Double {
            let r = red - other.red, g = green - other.green, b = blue - other.blue
            return (r * r + g * g + b * b).squareRoot()
        }

        /// HSB brightness, 0...1.
        var brightness: Double { max(red, green, blue) }

        /// HSB saturation, 0 (grey) ... 1 (pure colour).
        var saturation: Double {
            let top = brightness
            return top == 0 ? 0 : (top - min(red, green, blue)) / top
        }

        /// HSB hue, 0...1 (red = 0, green ≈ 0.33, blue ≈ 0.67).
        var hue: Double {
            let top = brightness, bottom = min(red, green, blue), range = top - bottom
            guard range > 0 else { return 0 }
            var h: Double
            if top == red { h = (green - blue) / range }
            else if top == green { h = 2 + (blue - red) / range }
            else { h = 4 + (red - green) / range }
            h /= 6
            return h < 0 ? h + 1 : h
        }

        /// How far apart two hues are round the colour wheel: 0 (same) ... 1 (opposite).
        func hueDistance(to other: RGB) -> Double {
            let d = abs(hue - other.hue)
            return min(d, 1 - d) * 2
        }

        /// The same hue, lifted to at least `minimum` brightness.
        func brightened(to minimum: Double) -> RGB {
            let top = brightness
            guard top > 0, top < minimum else { return self }
            let k = minimum / top
            return RGB(red: red * k, green: green * k, blue: blue * k)
        }
    }

    /// A colour in the image and the share of the image it covers (0...1).
    public struct Swatch: Equatable, Sendable {
        public let color: RGB
        public let share: Double
    }

    /// Side length the image is shrunk to before counting. Small is fast and
    /// smooths out noise and JPEG artefacts.
    static let sampleSize = 40
    /// Smallest share of the image a colour needs to be picked (ignores specks).
    static let minimumShare = 0.02
    /// Colours greyer or darker than this aren't picked: they'd vanish on the
    /// dark background.
    static let minimumSaturation = 0.3
    static let minimumBrightness = 0.3
    /// The picked colour is lifted to at least this brightness so it shows up.
    static let displayBrightness = 0.8

    /// A vivid colour from the art that stands out against its main colour —
    /// e.g. the yellow highlight on mostly-red art. Nil if the art has no
    /// suitable colour (all one colour, or only greys).
    public static func accentColor(of image: CGImage) -> RGB? {
        accentColor(in: swatches(of: image))
    }

    /// Number of hue families vivid colours are grouped into (30° each).
    static let hueFamilies = 12

    /// Picks the accent from swatches listed most common first. Vivid
    /// swatches are grouped by hue first, so a colour split over many shades
    /// (red balls in light and shadow) counts as one; then the family that
    /// best combines contrast with the main colour, vividness and size wins.
    static func accentColor(in swatches: [Swatch]) -> RGB? {
        guard let main = swatches.first?.color else { return nil }
        let vivid = swatches.dropFirst().filter {
            $0.color.saturation >= minimumSaturation && $0.color.brightness >= minimumBrightness
        }
        let families = Dictionary(grouping: vivid) { Int($0.color.hue * Double(hueFamilies)) % hueFamilies }
            .map { _, members -> Swatch in
                let share = members.reduce(0) { $0 + $1.share }
                func avg(_ channel: (RGB) -> Double) -> Double {
                    members.reduce(0) { $0 + channel($1.color) * $1.share } / share
                }
                return Swatch(color: RGB(red: avg(\.red), green: avg(\.green), blue: avg(\.blue)), share: share)
            }
        let best = families
            .filter { $0.share >= minimumShare }
            .max { score($0, against: main) < score($1, against: main) }
        return best?.color.brightened(to: displayBrightness)
    }

    /// Contrast with the main colour counts most; vividness next; how much
    /// of the art it covers only a little, so a small highlight can win.
    /// Contrast is by hue, so lighter and darker shades of the main colour
    /// don't count. Against grey/black/white art, any colour contrasts.
    static func score(_ swatch: Swatch, against main: RGB) -> Double {
        let c = swatch.color
        let contrast = (1 - main.saturation) + main.saturation * c.hueDistance(to: main)
        return contrast * contrast * c.saturation * c.brightness * swatch.share.squareRoot()
    }

    /// The image's colours, most common first. Pixels are grouped into
    /// buckets (8 levels per channel) and each bucket reports its average.
    public static func swatches(of image: CGImage) -> [Swatch] {
        let side = sampleSize
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                data: &pixels, width: side, height: side, bitsPerComponent: 8,
                bytesPerRow: side * 4, space: space,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return [] }
        context.interpolationQuality = .medium
        context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))

        struct Bucket { var count = 0; var r = 0, g = 0, b = 0 }
        var buckets: [Int: Bucket] = [:]
        var total = 0
        for i in stride(from: 0, to: pixels.count, by: 4) {
            guard pixels[i + 3] > 200 else { continue }   // skip see-through pixels
            let r = Int(pixels[i]), g = Int(pixels[i + 1]), b = Int(pixels[i + 2])
            let key = (r >> 5) << 6 | (g >> 5) << 3 | (b >> 5)
            var bucket = buckets[key, default: Bucket()]
            bucket.count += 1; bucket.r += r; bucket.g += g; bucket.b += b
            buckets[key] = bucket
            total += 1
        }
        guard total > 0 else { return [] }
        // Ties broken by bucket, so the same art always gives the same answer.
        return buckets
            .sorted { $0.value.count != $1.value.count ? $0.value.count > $1.value.count : $0.key < $1.key }
            .map { _, b in
                let n = Double(b.count) * 255
                return Swatch(color: RGB(red: Double(b.r) / n, green: Double(b.g) / n, blue: Double(b.b) / n),
                              share: Double(b.count) / Double(total))
            }
    }
}
