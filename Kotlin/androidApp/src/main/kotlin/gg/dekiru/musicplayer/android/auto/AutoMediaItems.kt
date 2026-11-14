package gg.dekiru.musicplayer.android.auto

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants

/** Turns [AutoRow]s into the Media3 items Android Auto shows. */
@UnstableApi
class AutoMediaItems(private val context: Context) {

    fun item(row: AutoRow): MediaItem {
        val id = AutoId.parse(row.id)
        val extras = Bundle()
        row.group?.let { extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        row.childStyle?.let { style ->
            val value = style.value()
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, value)
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, if (id is AutoId.Tab && id.tab == AutoTab.DOWNLOADS) value else LIST)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(row.title)
            .setDisplayTitle(row.title)
            .setSubtitle(row.subtitle)
            .setIsBrowsable(row.browsable)
            .setIsPlayable(row.playable)
            .setMediaType(mediaType(id))
            .setArtworkUri(row.artItemId?.let { ArtworkProvider.uri(context, it) })
            .setExtras(extras.takeUnless { it.isEmpty })
            .build()
        return MediaItem.Builder().setMediaId(row.id).setMediaMetadata(metadata).build()
    }

    /** The top of the tree; its extras say covers for albums, a list for songs, and that search works. */
    fun root(): MediaItem = item(AutoRow(id = AutoId.ROOT, title = "Tetsubi", browsable = true))

    private fun mediaType(id: AutoId?): Int = when (id) {
        is AutoId.Tab, AutoId.Root -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        is AutoId.Artist -> MediaMetadata.MEDIA_TYPE_ARTIST
        is AutoId.Album, is AutoId.Downloaded -> MediaMetadata.MEDIA_TYPE_ALBUM
        is AutoId.Playlist -> MediaMetadata.MEDIA_TYPE_PLAYLIST
        is AutoId.AlbumTrack, is AutoId.PlaylistSong -> MediaMetadata.MEDIA_TYPE_MUSIC
        else -> MediaMetadata.MEDIA_TYPE_MIXED
    }

    companion object {
        private const val LIST = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        private const val GRID = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM

        /** Tells Android Auto search is offered (androidx.media's key). */
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"

        private fun AutoStyle.value() = if (this == AutoStyle.GRID) GRID else LIST

        /** The root's extras: albums as covers, songs as a list, search on. */
        fun rootExtras(): Bundle = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, GRID)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, LIST)
            putBoolean(SEARCH_SUPPORTED, true)
        }
    }
}
