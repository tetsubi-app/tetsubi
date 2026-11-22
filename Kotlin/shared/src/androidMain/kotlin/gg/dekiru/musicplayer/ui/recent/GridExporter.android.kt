package gg.dekiru.musicplayer.ui.recent

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream

/** The FileProvider (declared in the app's manifest) that hands the copied picture to other apps. */
internal fun exportAuthority(context: Context) = "${context.packageName}.exports"

@Composable
actual fun rememberGridExporter(): GridExporter {
    val context = LocalContext.current.applicationContext
    val exporter = remember(context) { AndroidGridExporter(context) }
    // While a screen that can show it is there, a save's outcome is kept for it.
    DisposableEffect(exporter) {
        GridSave.screens++
        onDispose { GridSave.screens-- }
    }
    return exporter
}

/**
 * Recently Played's "Save" on Android: the Storage Access Framework's
 * Create Document. The picker is registered by the activity
 * ([attach], [documentChosen]), not by the screen, so its answer arrives
 * even when the screen or the activity was rebuilt while it was open (a
 * configuration change, or the system reclaiming the app). The picture
 * waits in a cache file, not in memory, and is copied in a process-wide
 * scope that outlives the screen.
 */
object GridSave {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var launcher: ((String) -> Unit)? = null
    private var owner: Any? = null

    internal val outcome = MutableStateFlow<SaveOutcome?>(null)

    /** Screens showing Export grid now (main thread only). */
    internal var screens = 0

    /** The picture waiting for the picker's answer. */
    internal fun pendingFile(context: Context) = File(File(context.cacheDir, "exports"), "pending-save.png")

    /** The activity's Create Document launcher, from its onCreate. */
    fun attach(owner: Any, launch: (String) -> Unit) {
        this.owner = owner
        launcher = launch
    }

    /** From the activity's onDestroy; a newer activity's launcher is kept. */
    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        launcher = null
    }

    /** Opens the picker for [fileName]; false when there's no activity to ask. */
    internal fun launch(fileName: String): Boolean {
        val launch = launcher ?: return false
        launch(fileName)
        return true
    }

    /**
     * The picker's answer (null: cancelled). Copies the waiting picture into
     * [uri], truncating whatever is there; on failure removes the empty
     * document. Never throws.
     */
    fun documentChosen(context: Context, uri: Uri?) {
        val app = context.applicationContext
        val file = pendingFile(app)
        scope.launch {
            if (uri == null) {
                file.delete()
                return@launch
            }
            val result = try {
                ExportFiles.copyInto(file) { open(app, uri) }
                SaveOutcome.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
                SaveOutcome.Failed(e.message.orEmpty())
            } finally {
                file.delete()
            }
            withContext(Dispatchers.Main) { report(result) }
        }
    }

    /** Shown if Export grid is on screen; otherwise dropped (not shown on a later visit). */
    internal fun report(result: SaveOutcome) {
        if (screens > 0) outcome.value = result
    }

    /** "wt" (write, truncate); a provider that doesn't know it gets plain "w". */
    private fun open(context: Context, uri: Uri): OutputStream? {
        val resolver = context.contentResolver
        return try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        } catch (e: FileNotFoundException) {
            resolver.openOutputStream(uri, "w")
        }
    }
}

private class AndroidGridExporter(private val context: Context) : GridExporter {

    override val saveOutcome: StateFlow<SaveOutcome?> = GridSave.outcome.asStateFlow()

    override fun saveOutcomeShown() {
        GridSave.outcome.value = null
    }

    override suspend fun render(tiles: List<ExportTile>, n: Int, labels: Boolean, fonts: ExportFonts): ByteArray =
        withContext(Dispatchers.Default) {
            val cache = File(context.cacheDir, "export-fonts")
            renderGridPng(tiles, n, labels, typeface(cache, "regular", fonts.regular, false), typeface(cache, "bold", fonts.bold, true))
        }

    override suspend fun copy(png: ByteArray) {
        val uri = withContext(Dispatchers.IO) {
            val file = File(File(context.cacheDir, "exports"), "Tetsubi Recently Played.png")
            // Whole and non-empty before it goes on the clipboard (throws otherwise).
            ExportFiles.writeWhole(file, png)
            FileProvider.getUriForFile(context, exportAuthority(context), file)
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData(ClipDescription("Tetsubi Recently Played", arrayOf("image/png")), ClipData.Item(uri))
        clipboard.setPrimaryClip(clip)
    }

    override suspend fun save(png: ByteArray, fileName: String) {
        val file = GridSave.pendingFile(context)
        val launched = try {
            // On disk before the picker opens: the answer may reach a rebuilt app.
            withContext(Dispatchers.IO) { ExportFiles.writeWhole(file, png) }
            GridSave.launch(fileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!launched) {
            withContext(Dispatchers.IO) { file.delete() }
            GridSave.report(SaveOutcome.Failed("The save dialog couldn't be opened."))
        }
    }
}

/** DEKIRU Sans from its bytes (Android reads typefaces from files), else the system's. */
private fun typeface(dir: File, name: String, bytes: ByteArray?, bold: Boolean): Typeface {
    val fallback = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    if (bytes == null) return fallback
    return runCatching {
        dir.mkdirs()
        val file = File(dir, "dekirusans_$name.ttf")
        if (!file.exists() || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
        Typeface.createFromFile(file)
    }.getOrDefault(fallback)
}

/** The grid as a 1200 × 1200 PNG (see [ExportGrid]); characters DEKIRU Sans lacks fall back to the system font. */
internal fun renderGridPng(tiles: List<ExportTile>, n: Int, labels: Boolean, regular: Typeface, bold: Typeface): ByteArray {
    val size = ExportGrid.IMAGE_SIZE
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val fill = Paint().apply { color = 0xFF000000.toInt() or ExportGrid.PLACEHOLDER_RGB }
    val rects = ExportGrid.tileRects(n)
    for ((tile, rect) in tiles.zip(rects)) {
        val dst = Rect(rect.x, rect.y, rect.x + rect.size, rect.y + rect.size)
        val cover = tile.cover?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (cover != null) {
            val (l, t, r, b) = ExportGrid.aspectFillSource(cover.width, cover.height)
            canvas.drawBitmap(cover, Rect(l, t, r, b), dst, imagePaint)
            cover.recycle()
        } else {
            canvas.drawRect(dst, fill)
            val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb((255 * ExportGrid.NOTE_ALPHA).toInt(), 255, 255, 255)
                textSize = rect.size * ExportGrid.NOTE_SIZE
                textAlign = Paint.Align.CENTER
            }
            val fm = note.fontMetrics
            canvas.drawText(ExportGrid.NOTE, rect.x + rect.size / 2f, rect.y + rect.size / 2f - (fm.ascent + fm.descent) / 2, note)
        }
        if (labels) drawLabels(canvas, tile, rect, regular, bold)
    }
    val out = ByteArrayOutputStream()
    val encoded = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    if (!encoded || out.size() == 0) error("The image couldn't be encoded.")
    return out.toByteArray()
}

/** Title (bold) over artist (regular), white, top left, with a sharp shadow down and right. */
private fun drawLabels(canvas: Canvas, tile: ExportTile, rect: ExportGrid.TileRect, regular: Typeface, bold: Typeface) {
    val layout = ExportGrid.labelLayout(rect.size)
    fun paint(face: Typeface, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
    }
    val titlePaint = paint(bold, layout.titleSize)
    val artistPaint = paint(regular, layout.artistSize)
    val titleMetrics = titlePaint.fontMetrics
    val artistMetrics = artistPaint.fontMetrics
    val (titleBase, artistBase) = ExportGrid.baselines(
        layout, -titleMetrics.ascent, titleMetrics.descent, titleMetrics.leading, -artistMetrics.ascent,
    )
    val lines = listOf(
        Triple(ExportGrid.ellipsize(tile.title, layout.maxWidth) { titlePaint.measureText(it) }, titlePaint, titleBase),
        Triple(ExportGrid.ellipsize(tile.artist, layout.maxWidth) { artistPaint.measureText(it) }, artistPaint, artistBase),
    )
    val x = rect.x + layout.inset
    val offset = layout.shadowOffset.toFloat()
    val shadow = android.graphics.Color.argb((255 * ExportGrid.SHADOW_ALPHA).toInt(), 0, 0, 0)
    canvas.save()
    canvas.clipRect(rect.x, rect.y, rect.x + rect.size, rect.y + rect.size)
    for ((text, p, baseline) in lines) {
        if (text.isEmpty()) continue
        p.color = shadow
        canvas.drawText(text, x + offset, rect.y + baseline + offset, p)
        p.color = android.graphics.Color.WHITE
        canvas.drawText(text, x, rect.y + baseline, p)
    }
    canvas.restore()
}
