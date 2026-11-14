package gg.dekiru.musicplayer.data.models

import gg.dekiru.musicplayer.data.api.TetsubiJson
import gg.dekiru.musicplayer.playback.PlaybackTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tracks with their own picture (The Slip, Mercy Girl), as iOS `TrackArtTests`. */
class TrackArtTest {

    private fun track(json: String): JellyfinTrack = TetsubiJson.decodeFromString(json)

    @Test
    fun ownArtOnlyWhenTheBlurHashDiffers() {
        // As the server sends them: every track has its own tag.
        assertFalse(track("""{"Id":"t1","ImageTags":{"Primary":"own"},"AlbumPrimaryImageTag":"alb","ImageBlurHashes":{"Primary":{"own":"eAKne","alb":"eAKne"}}}""").hasOwnArt)
        assertTrue(track("""{"Id":"t2","ImageTags":{"Primary":"own"},"AlbumPrimaryImageTag":"alb","ImageBlurHashes":{"Primary":{"own":"e69tM","alb":"eAKne"}}}""").hasOwnArt)
        assertFalse(track("""{"Id":"t3","ImageTags":{"Primary":"own"},"AlbumPrimaryImageTag":"alb"}""").hasOwnArt, "unknown: keep the album picture")
        assertTrue(track("""{"Id":"t4","ImageTags":{"Primary":"own"}}""").hasOwnArt)
        assertFalse(track("""{"Id":"t5"}""").hasOwnArt)
    }

    @Test
    fun queuedTrackShowsItsOwnPictureOnlyWhenItHasOne() {
        val album = JellyfinAlbum(id = "alb1", name = "The Slip")
        val own = track("""{"Id":"t2","ImageTags":{"Primary":"own"},"AlbumPrimaryImageTag":"alb","ImageBlurHashes":{"Primary":{"own":"x","alb":"y"}}}""")
        assertEquals("t2", PlaybackTrack.from(own, album, 0).artItemId)
        assertEquals("alb1", PlaybackTrack.from(track("""{"Id":"t1"}"""), album, 0).artItemId)
    }
}
