package gg.dekiru.musicplayer.ui.recent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.FileDialog
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilenameFilter
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@Composable
actual fun rememberGridExporter(): GridExporter = remember { DesktopGridExporter() }

private class DesktopGridExporter : GridExporter {
    private val outcome = MutableStateFlow<SaveOutcome?>(null)
    override val saveOutcome: StateFlow<SaveOutcome?> = outcome.asStateFlow()

    override fun saveOutcomeShown() {
        outcome.value = null
    }

    override suspend fun render(tiles: List<ExportTile>, n: Int, labels: Boolean, fonts: ExportFonts): ByteArray =
        withContext(Dispatchers.Default) { renderGridPng(tiles, n, labels, fonts) }

    override suspend fun copy(png: ByteArray) {
        val image = withContext(Dispatchers.Default) { ImageIO.read(ByteArrayInputStream(png)) } ?: error("No picture")
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageSelection(image), null)
    }

    override suspend fun save(png: ByteArray, fileName: String) {
        // After the click has been handled: the dialog blocks until it's closed.
        SwingUtilities.invokeLater {
            val outcome = try {
                val dialog = FileDialog(null as Frame?, "Save Grid", FileDialog.SAVE).apply {
                    file = fileName
                    filenameFilter = FilenameFilter { _, name -> name.endsWith(".png", ignoreCase = true) }
                }
                dialog.isVisible = true
                val name = dialog.file
                val dir = dialog.directory
                if (name == null) {
                    SaveOutcome.Cancelled
                } else {
                    File(dir, withPngExtension(name)).writeBytes(png)
                    SaveOutcome.Saved
                }
            } catch (e: Exception) {
                SaveOutcome.Failed(e.message.orEmpty())
            }
            // Still on the UI (AWT) thread.
            this.outcome.value = outcome
        }
    }
}

/** "grid" → "grid.png"; a name already ending in .png (any case) is kept. */
internal fun withPngExtension(name: String): String = if (name.endsWith(".png", ignoreCase = true)) name else "$name.png"

/** An image on the AWT clipboard. */
private class ImageSelection(private val image: Image) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
        return image
    }
}

/** A cover's bytes as an image: ImageIO (JPEG, PNG), else Skia (WebP and the rest). */
private fun decode(bytes: ByteArray): BufferedImage? {
    runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()?.let { return it }
    return runCatching {
        val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val png = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes ?: return null
        ImageIO.read(ByteArrayInputStream(png))
    }.getOrNull()
}

private fun font(bytes: ByteArray?, bold: Boolean): Font =
    bytes?.let { runCatching { Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(it)) }.getOrNull() }
        ?: Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, 12)

/** [base] at [size], or the system's sans serif (which falls back further) for text it can't draw (Japanese, say). */
private fun fontFor(text: String, base: Font, size: Float, bold: Boolean): Font =
    if (base.canDisplayUpTo(text) == -1) base.deriveFont(size)
    else Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, 12).deriveFont(size)

/** The grid as a 1200 × 1200 PNG (see [ExportGrid]), DEKIRU Sans from [fonts]. */
internal fun renderGridPng(tiles: List<ExportTile>, n: Int, labels: Boolean, fonts: ExportFonts): ByteArray {
    val size = ExportGrid.IMAGE_SIZE
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    val regular = font(fonts.regular, bold = false)
    val bold = font(fonts.bold, bold = true)
    for ((tile, rect) in tiles.zip(ExportGrid.tileRects(n))) {
        val cover = tile.cover?.let(::decode)
        if (cover != null) {
            val (l, t, r, b) = ExportGrid.aspectFillSource(cover.width, cover.height)
            g.drawImage(cover, rect.x, rect.y, rect.x + rect.size, rect.y + rect.size, l, t, r, b, null)
        } else {
            drawPlaceholder(g, rect)
        }
        if (labels) drawLabels(g, tile, rect, regular, bold)
    }
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}

/** No cover: the app's dark tile with a music note. */
private fun drawPlaceholder(g: Graphics2D, rect: ExportGrid.TileRect) {
    g.color = Color(ExportGrid.PLACEHOLDER_RGB)
    g.fillRect(rect.x, rect.y, rect.size, rect.size)
    val font = Font(Font.SANS_SERIF, Font.PLAIN, 12).deriveFont(rect.size * ExportGrid.NOTE_SIZE)
    if (font.canDisplayUpTo(ExportGrid.NOTE) != -1) return
    g.font = font
    g.color = Color(1f, 1f, 1f, ExportGrid.NOTE_ALPHA)
    val metrics = g.fontMetrics
    val x = rect.x + (rect.size - metrics.stringWidth(ExportGrid.NOTE)) / 2f
    val y = rect.y + (rect.size - metrics.ascent - metrics.descent) / 2f + metrics.ascent
    g.drawString(ExportGrid.NOTE, x, y)
}

/** Title (bold) over artist (regular), white, top left, with a sharp shadow down and right. */
private fun drawLabels(g: Graphics2D, tile: ExportTile, rect: ExportGrid.TileRect, regular: Font, bold: Font) {
    val layout = ExportGrid.labelLayout(rect.size)
    val titleFont = fontFor(tile.title, bold, layout.titleSize, bold = true)
    val artistFont = fontFor(tile.artist, regular, layout.artistSize, bold = false)
    val titleMetrics = g.getFontMetrics(titleFont)
    val artistMetrics = g.getFontMetrics(artistFont)
    val (titleBase, artistBase) = ExportGrid.baselines(
        layout, titleMetrics.ascent.toFloat(), titleMetrics.descent.toFloat(), titleMetrics.leading.toFloat(), artistMetrics.ascent.toFloat(),
    )
    fun width(font: Font, text: String) = font.getStringBounds(text, g.fontRenderContext).width.toFloat()
    val lines = listOf(
        Triple(ExportGrid.ellipsize(tile.title, layout.maxWidth) { width(titleFont, it) }, titleFont, titleBase),
        Triple(ExportGrid.ellipsize(tile.artist, layout.maxWidth) { width(artistFont, it) }, artistFont, artistBase),
    )
    val x = rect.x + layout.inset
    val offset = layout.shadowOffset.toFloat()
    val clip = g.clip
    g.clipRect(rect.x, rect.y, rect.size, rect.size)
    for ((text, font, baseline) in lines) {
        if (text.isEmpty()) continue
        g.font = font
        g.composite = AlphaComposite.SrcOver
        g.color = Color(0f, 0f, 0f, ExportGrid.SHADOW_ALPHA)
        g.drawString(text, x + offset, rect.y + baseline + offset)
        g.color = Color.WHITE
        g.drawString(text, x, rect.y + baseline)
    }
    g.clip = clip
}
