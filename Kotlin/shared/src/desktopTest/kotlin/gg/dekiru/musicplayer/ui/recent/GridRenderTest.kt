package gg.dekiru.musicplayer.ui.recent

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Linux drawing of Recently Played's export (Android's needs a device for Bitmap). */
class GridRenderTest {

    private val colours = listOf(
        0xE53935, 0x43A047, 0x1E88E5, 0xFDD835, 0x8E24AA, 0x00ACC1, 0xFB8C00, 0x6D4C41, 0x546E7A,
    )

    /** A solid-colour cover, 600 × 400 so the crop is exercised too. */
    private fun cover(rgb: Int): ByteArray {
        val image = BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(rgb)
        g.fillRect(0, 0, 600, 400)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    // The app's font files (Gradle runs tests in the module folder).
    private val fonts = ExportFonts(
        File("src/commonMain/composeResources/font/dekirusans_regular.ttf").takeIf { it.exists() }?.readBytes(),
        File("src/commonMain/composeResources/font/dekirusans_bold.ttf").takeIf { it.exists() }?.readBytes(),
    )

    private fun render(labels: Boolean, covers: List<ByteArray?> = colours.map(::cover)): BufferedImage {
        val tiles = covers.mapIndexed { i, c -> ExportTile(c, "A Rather Long Album Title Number $i", "Some Artist") }
        val png = renderGridPng(tiles, 3, labels, fonts)
        // A real PNG.
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code), png.take(4).map { it.toInt() and 0xFF })
        return ImageIO.read(ByteArrayInputStream(png))
    }

    @Test
    fun threeByThreeIsTwelveHundredSquareWithEachCoverInItsTile() {
        assertTrue(fonts.bold != null && fonts.regular != null, "DEKIRU Sans files found")
        val image = render(labels = false)
        assertEquals(1200, image.width)
        assertEquals(1200, image.height)
        for ((i, rect) in ExportGrid.tileRects(3).withIndex()) {
            assertEquals(colours[i], image.getRGB(rect.x + rect.size / 2, rect.y + rect.size / 2) and 0xFFFFFF, "tile $i")
            // Edge to edge: no gaps or border.
            assertEquals(colours[i], image.getRGB(rect.x, rect.y) and 0xFFFFFF, "tile $i corner")
            assertEquals(colours[i], image.getRGB(rect.x + rect.size - 1, rect.y + rect.size - 1) and 0xFFFFFF, "tile $i far corner")
        }
    }

    @Test
    fun labelsDrawInTheTopLeftInsetOnly() {
        val image = render(labels = true)
        val layout = ExportGrid.labelLayout(400)
        val inset = layout.inset.toInt()
        // Text (white or its shadow) in tile 0's top-left label area.
        var changed = 0
        for (y in inset until inset + (layout.titleSize + layout.artistSize).toInt() * 2) {
            for (x in inset until 400 - inset) if (image.getRGB(x, y) and 0xFFFFFF != colours[0]) changed++
        }
        assertTrue(changed > 100, "label pixels: $changed")
        // White text and a dark shadow are both there.
        val pixels = (inset until 80).flatMap { y -> (inset until 380).map { x -> Color(image.getRGB(x, y)) } }
        assertTrue(pixels.any { it.red > 240 && it.green > 240 && it.blue > 240 })
        assertTrue(pixels.any { it.red < 70 && it.green < 20 && it.blue < 20 })
        // The rest of the tile is untouched, the centre and the bottom.
        assertEquals(colours[0], image.getRGB(200, 200) and 0xFFFFFF)
        assertEquals(colours[0], image.getRGB(200, 390) and 0xFFFFFF)
        // Nothing spills into the next tile.
        for (y in 0 until 120) assertEquals(colours[1], image.getRGB(400, y) and 0xFFFFFF, "row $y")
    }

    @Test
    fun missingCoverIsThePlaceholderNotAHole() {
        val image = render(labels = false, covers = listOf(null, byteArrayOf(1, 2, 3)) + colours.drop(2).map(::cover))
        assertEquals(ExportGrid.PLACEHOLDER_RGB, image.getRGB(5, 5) and 0xFFFFFF)
        assertEquals(ExportGrid.PLACEHOLDER_RGB, image.getRGB(405, 5) and 0xFFFFFF) // unreadable bytes too
        assertEquals(colours[2], image.getRGB(1000, 200) and 0xFFFFFF)
    }

    @Test
    fun savedNamesEndInPng() {
        assertEquals("grid.png", withPngExtension("grid"))
        assertEquals("grid.PNG", withPngExtension("grid.PNG"))
    }
}
