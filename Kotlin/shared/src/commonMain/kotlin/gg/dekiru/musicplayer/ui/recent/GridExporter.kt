package gg.dekiru.musicplayer.ui.recent

import androidx.compose.runtime.Composable
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.downloads.LocalFiles
import gg.dekiru.musicplayer.resources.Res
import gg.dekiru.musicplayer.resources.dekirusans_bold
import gg.dekiru.musicplayer.resources.dekirusans_regular
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getFontResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment

/**
 * The platform half of "Export grid": drawing the PNG, the clipboard and
 * the save dialog. Android: Bitmap / Canvas, `ClipboardManager` with a
 * FileProvider `content://` URI, the Storage Access Framework's Create
 * Document. Linux: `BufferedImage` / `Graphics2D`, the AWT clipboard,
 * `FileDialog`.
 */
interface GridExporter {
    /** Draws the 1200 × 1200 PNG (off the main thread; callers needn't switch). */
    suspend fun render(tiles: List<ExportTile>, n: Int, labels: Boolean, fonts: ExportFonts): ByteArray

    /** Puts the picture on the clipboard as an image. Throws when it can't. */
    suspend fun copy(png: ByteArray)

    /**
     * Asks where to save it (suggesting [fileName]) and saves it there.
     * Returns once the dialog has been asked for; how it went arrives in
     * [saveOutcome] (on Android after the system picker, which may outlive
     * this screen), a failure (the dialog couldn't be shown, the file
     * couldn't be written) as [SaveOutcome.Failed].
     */
    suspend fun save(png: ByteArray, fileName: String)

    /** The last save's outcome, until [saveOutcomeShown]. */
    val saveOutcome: StateFlow<SaveOutcome?>

    /** The outcome has been shown: forget it. */
    fun saveOutcomeShown()
}

sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data object Cancelled : SaveOutcome
    data class Failed(val message: String) : SaveOutcome
}

@Composable
expect fun rememberGridExporter(): GridExporter

/** DEKIRU Sans' files (null: that one couldn't be read; the system font is used). */
class ExportFonts(val regular: ByteArray?, val bold: ByteArray?)

/** The app's font files, from the shared resources. */
@OptIn(ExperimentalResourceApi::class)
suspend fun loadExportFonts(): ExportFonts {
    suspend fun read(resource: org.jetbrains.compose.resources.FontResource): ByteArray? = try {
        getFontResourceBytes(getSystemResourceEnvironment(), resource)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return ExportFonts(read(Res.font.dekirusans_regular), read(Res.font.dekirusans_bold))
}

/**
 * The tiles for [albums], covers fetched together: a downloaded album's
 * saved cover ([localCover]) if there is one, else the server's at
 * [ExportGrid.COVER_SIZE]. A cover that can't be had is null (the
 * placeholder), never an error.
 */
suspend fun loadExportTiles(
    api: JellyfinApi,
    albums: List<JellyfinAlbum>,
    localCover: (JellyfinAlbum) -> String?,
): List<ExportTile> = coroutineScope {
    albums.map { album ->
        async {
            val local = localCover(album)?.let(LocalFiles::readBytes)
            ExportTile(local ?: fetchCover(api, album.id), album.displayName, album.artistName)
        }
    }.awaitAll()
}

private suspend fun fetchCover(api: JellyfinApi, itemId: String): ByteArray? = try {
    val response = api.http.get(api.imageUrl(itemId, ExportGrid.COVER_SIZE)) {
        header(HttpHeaders.Authorization, api.authorizationHeader())
        // JPEG or PNG, which both platforms' decoders read.
        header(HttpHeaders.Accept, "image/jpeg, image/png;q=0.9")
    }
    response.readRawBytes().takeIf { response.status.isSuccess() && it.isNotEmpty() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
