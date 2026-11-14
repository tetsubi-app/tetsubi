package gg.dekiru.musicplayer.android.auto

import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The session's player, letting play requests from Android Auto and
 * Assistant through to the app. Those requests are carried out by the
 * app's own player ([AutoCallback] → `PlayerController`), which loads the
 * queue itself; Media3 then hands the session's player the stand-in item
 * [HANDLED] to load, and this drops it, so the app's queue stays as the
 * app made it. Everything else passes straight through.
 */
@UnstableApi
class PlayRequests(player: Player) : ForwardingPlayer(player) {

    private fun handled(items: List<MediaItem>) = items.size == 1 && items[0].mediaId == HANDLED_ID

    override fun setMediaItem(mediaItem: MediaItem) {
        if (!handled(listOf(mediaItem))) super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (!handled(listOf(mediaItem))) super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (!handled(listOf(mediaItem))) super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        if (!handled(mediaItems)) super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (!handled(mediaItems)) super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (!handled(mediaItems)) super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        if (!handled(listOf(mediaItem))) super.addMediaItem(mediaItem)
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        if (!handled(listOf(mediaItem))) super.addMediaItem(index, mediaItem)
    }

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        if (!handled(mediaItems)) super.addMediaItems(mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        if (!handled(mediaItems)) super.addMediaItems(index, mediaItems)
    }

    companion object {
        private const val HANDLED_ID = "tetsubi:handled"

        /** Stands for "the app has already started playing this". */
        val HANDLED: MediaItem = MediaItem.Builder().setMediaId(HANDLED_ID).setUri(Uri.parse(HANDLED_ID)).build()
    }
}
