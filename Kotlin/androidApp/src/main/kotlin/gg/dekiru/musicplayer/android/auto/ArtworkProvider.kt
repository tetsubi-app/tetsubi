package gg.dekiru.musicplayer.android.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.media3.common.util.UnstableApi
import gg.dekiru.musicplayer.android.TetsubiApplication
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.playback.ArtworkLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/**
 * Album and playlist pictures for Android Auto's lists. The car loads each
 * row's picture itself, and Jellyfin's images need the `Authorization`
 * header, so rows point here (`content://<app>.artwork/art/<item id>`) and
 * this hands over the file: a download's saved cover when there is one
 * (so it works offline), else the server's picture fetched through
 * [ArtworkLoader] (the app's image cache, with the header) and kept in
 * the cache folder.
 *
 * Read-only, pictures only.
 */
@UnstableApi
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val itemId = uri.lastPathSegment?.takeIf { uri.pathSegments.firstOrNull() == PATH && SAFE_ID.matches(it) }
            ?: throw FileNotFoundException("No such picture")
        // The session is made on the main thread (it starts the player), so it's read there.
        val read = { app.session.api.value to app.session.downloads.value }
        val (api, downloads) = if (Looper.myLooper() == Looper.getMainLooper()) read() else runBlocking(Dispatchers.Main) { read() }
        val file = savedCover(downloads, itemId) ?: cached(api, itemId) ?: throw FileNotFoundException("No picture for $itemId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private val app: TetsubiApplication get() = context!!.applicationContext as TetsubiApplication

    /** A downloaded album's or playlist's own saved picture. */
    private fun savedCover(downloads: DownloadManager?, itemId: String): File? {
        downloads ?: return null
        val path = downloads.downloadedAlbum(itemId)?.coverPath?.takeIf { File(it).isFile }
            ?: downloads.playlists.saved(itemId)?.coverPath
        return path?.let(::File)?.takeIf { it.isFile }
    }

    /** The server's picture, fetched once and kept in the cache folder. */
    private fun cached(api: JellyfinApi?, itemId: String): File? {
        val dir = File(app.cacheDir, CACHE_FOLDER).apply { mkdirs() }
        val file = File(dir, "$itemId.jpg")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MILLIS) return file
        api ?: return file.takeIf { it.isFile }
        val bitmap = runCatching {
            ArtworkLoader(app) { api.authorizationHeader() }
                .loadBitmap(Uri.parse(api.imageUrl(itemId, SIZE)))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrNull() ?: return file.takeIf { it.isFile } // offline: an older copy will do
        val partial = File(dir, "$itemId.part")
        partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        partial.renameTo(file)
        return file
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val PATH = "art"
        private const val CACHE_FOLDER = "auto-art"
        private const val SIZE = 400
        private const val TIMEOUT_SECONDS = 15L
        private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private val SAFE_ID = Regex("[A-Za-z0-9-]+")

        /** The address a row's picture is loaded from. */
        fun uri(context: Context, itemId: String): Uri =
            Uri.Builder().scheme("content").authority("${context.packageName}.artwork").appendPath(PATH).appendPath(itemId).build()
    }
}
