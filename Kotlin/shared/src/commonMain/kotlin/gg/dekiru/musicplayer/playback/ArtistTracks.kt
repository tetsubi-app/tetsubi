package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.releaseDate

/** The pure rules behind Play all and Shuffle on an artist's page (docs/redesign.md section 5, Artist). */
object ArtistTracks {
    /** The most tracks an artist's Play all or Shuffle queues; the rest are left out and the player says so. */
    const val CAP = 500

    /**
     * The albums as they are played: oldest release first (full release date, else Jan 1 of the production
     * year; unknown last), the same date by title. The tracks of each album follow in disc and track order.
     * The page itself lists the newest first.
     */
    fun albumOrder(albums: List<JellyfinAlbum>): List<JellyfinAlbum> =
        albums.sortedWith(compareBy<JellyfinAlbum>({ it.releaseDate == null }, { it.releaseDate }, { it.displayName.lowercase() }))

    /** `tracks` cut to [cap], and whether anything was left out. */
    fun <T> limit(tracks: List<T>, cap: Int = CAP): Pair<List<T>, Boolean> =
        if (tracks.size > cap) tracks.take(cap) to true else tracks to false

    /** More albums are only fetched while the tracks so far are within the cap (one over proves something is left out). */
    fun needsMore(trackCount: Int, cap: Int = CAP): Boolean = trackCount <= cap

    /** The notice after a capped play: "Queued the first 500 of this artist's tracks." */
    fun cappedMessage(cap: Int = CAP): String = "Queued the first $cap of this artist's tracks."
}
