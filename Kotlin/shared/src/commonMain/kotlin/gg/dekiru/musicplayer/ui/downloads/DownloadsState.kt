package gg.dekiru.musicplayer.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.allDiscs
import gg.dekiru.musicplayer.downloads.DownloadAlbumState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.downloads.DownloadedPlaylist
import gg.dekiru.musicplayer.downloads.LocalFiles
import kotlinx.coroutines.flow.MutableStateFlow

// The manager's lists as Compose state, so screens follow downloads as they change.

private val noActive = MutableStateFlow(emptyMap<String, DownloadAlbumState>())
private val noDownloaded = MutableStateFlow(emptyList<DownloadedAlbum>())
private val noPlaylists = MutableStateFlow(emptyList<DownloadedPlaylist>())

@Composable
fun DownloadManager?.collectActive(): State<Map<String, DownloadAlbumState>> = (this?.active ?: noActive).collectAsState()

@Composable
fun DownloadManager?.collectDownloaded(): State<List<DownloadedAlbum>> = (this?.downloaded ?: noDownloaded).collectAsState()

/** Every playlist with a download, complete or not. */
@Composable
fun DownloadManager?.collectPlaylists(): State<List<DownloadedPlaylist>> = (this?.playlists?.all ?: noPlaylists).collectAsState()

/** Whether an album (every disc of it) is downloaded: the cards' green tick. */
@Composable
fun rememberIsDownloaded(downloads: DownloadManager?): (JellyfinAlbum) -> Boolean {
    val active by downloads.collectActive()
    val downloaded by downloads.collectDownloaded()
    return remember(active, downloaded) {
        val ids = downloaded.map { it.albumId }.toSet()
        val check: (JellyfinAlbum) -> Boolean = { album -> album.allDiscs.all { it.id in ids && it.id !in active } }
        check
    }
}

/** A downloaded album's saved cover (its first disc's), if it's on disk. */
fun DownloadManager?.coverPath(album: JellyfinAlbum): String? =
    this?.downloadedAlbum(album.allDiscs.first().id)?.coverPath?.takeIf(LocalFiles::exists)
