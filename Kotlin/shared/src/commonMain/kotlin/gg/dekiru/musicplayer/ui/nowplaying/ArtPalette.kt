package gg.dekiru.musicplayer.ui.nowplaying

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Picks an accent colour from album art for Now Playing's progress line: a
 * vivid colour that stands out against the art's main colour (the yellow
 * highlight on mostly-red art). A port of iOS `ArtPalette` with one
 * difference: [MINIMUM_BRIGHTNESS] is 0.15 here (0.3 on iOS), so dark covers still get an accent.
 */
object ArtPalette {

    /** An sRGB colour, each channel 0..1. */
    data class Rgb(val red: Double, val green: Double, val blue: Double) {
        /** HSB brightness, 0..1. */
        val brightness: Double get() = max(red, max(green, blue))

        /** HSB saturation, 0 (grey) .. 1 (pure colour). */
        val saturation: Double
            get() = brightness.let { top -> if (top == 0.0) 0.0 else (top - min(red, min(green, blue))) / top }

        /** HSB hue, 0..1 (red = 0, green ≈ 0.33, blue ≈ 0.67). */
        val hue: Double
            get() {
                val top = brightness
                val range = top - min(red, min(green, blue))
                if (range <= 0) return 0.0
                var h = when (top) {
                    red -> (green - blue) / range
                    green -> 2 + (blue - red) / range
                    else -> 4 + (red - green) / range
                }
                h /= 6
                return if (h < 0) h + 1 else h
            }

        /** How far apart two hues are round the colour wheel: 0 (same) .. 1 (opposite). */
        fun hueDistance(other: Rgb): Double {
            val d = abs(hue - other.hue)
            return min(d, 1 - d) * 2
        }

        /** The same hue, lifted to at least [minimum] brightness. */
        fun brightened(minimum: Double): Rgb {
            val top = brightness
            if (top <= 0 || top >= minimum) return this
            val k = minimum / top
            return Rgb(red * k, green * k, blue * k)
        }
    }

    /** A colour in the image and the share of the image it covers (0..1). */
    data class Swatch(val color: Rgb, val share: Double)

    /** Side length the art is shrunk to before counting. */
    const val SAMPLE_SIZE = 40
    /** Smallest share of the image a colour needs to be picked (ignores specks). */
    const val MINIMUM_SHARE = 0.02
    /**
     * Colours greyer or darker than this aren't picked. Brightness is low (iOS has 0.3) because the pick is
     * lifted to [DISPLAY_BRIGHTNESS] anyway: a moody red/black cover has no red above 0.3 and got no accent.
     */
    const val MINIMUM_SATURATION = 0.3
    const val MINIMUM_BRIGHTNESS = 0.15
    /** The picked colour is lifted to at least this brightness so it shows up. */
    const val DISPLAY_BRIGHTNESS = 0.8
    /** Number of hue families vivid colours are grouped into (30° each). */
    const val HUE_FAMILIES = 12

    /** The accent from ARGB pixels (any size; Now Playing passes the art shrunk to 40×40). Null if none suits. */
    fun accentColor(argb: IntArray): Rgb? = accentColor(swatches(argb))

    /**
     * Picks the accent from swatches listed most common first. Vivid swatches
     * are grouped by hue first, so a colour split over many shades counts as
     * one; then the family that best combines contrast with the main colour,
     * vividness and size wins.
     */
    fun accentColor(swatches: List<Swatch>): Rgb? {
        val main = swatches.firstOrNull()?.color ?: return null
        val vivid = swatches.drop(1).filter {
            it.color.saturation >= MINIMUM_SATURATION && it.color.brightness >= MINIMUM_BRIGHTNESS
        }
        val families = vivid.groupBy { (it.color.hue * HUE_FAMILIES).toInt() % HUE_FAMILIES }.values.map { members ->
            val share = members.sumOf { it.share }
            fun avg(channel: (Rgb) -> Double) = members.sumOf { channel(it.color) * it.share } / share
            Swatch(Rgb(avg { it.red }, avg { it.green }, avg { it.blue }), share)
        }
        val best = families.filter { it.share >= MINIMUM_SHARE }.maxByOrNull { score(it, main) }
        return best?.color?.brightened(DISPLAY_BRIGHTNESS)
    }

    /** Contrast with the main colour counts most; vividness next; size only a little. */
    fun score(swatch: Swatch, main: Rgb): Double {
        val c = swatch.color
        val contrast = (1 - main.saturation) + main.saturation * c.hueDistance(main)
        return contrast * contrast * c.saturation * c.brightness * sqrt(swatch.share)
    }

    /** The image's colours, most common first: pixels grouped into buckets (8 levels per channel), each reporting its average. */
    fun swatches(argb: IntArray): List<Swatch> {
        class Bucket { var count = 0; var r = 0; var g = 0; var b = 0 }
        val buckets = HashMap<Int, Bucket>()
        var total = 0
        for (p in argb) {
            if ((p ushr 24) and 0xFF <= 200) continue // skip see-through pixels
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val key = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
            val bucket = buckets.getOrPut(key) { Bucket() }
            bucket.count++; bucket.r += r; bucket.g += g; bucket.b += b
            total++
        }
        if (total == 0) return emptyList()
        // Ties broken by bucket, so the same art always gives the same answer.
        return buckets.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Bucket>> { it.value.count }.thenBy { it.key })
            .map { (_, b) ->
                val n = b.count * 255.0
                Swatch(Rgb(b.r / n, b.g / n, b.b / n), b.count.toDouble() / total)
            }
    }
}
