package gg.dekiru.musicplayer.widget

import gg.dekiru.musicplayer.playback.PlaybackTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The home-screen widget: its saved snapshot, what it shows, what Play does, and the once-a-second limit. */
class WidgetTest {

    private val track = PlaybackTrack(
        id = "t2",
        trackNumber = 2,
        title = "Song Two",
        artist = "The Band",
        album = "The Album",
        albumId = "d1",
        durationSeconds = 200.0,
        artItemId = "d1",
    )

    private val playing = WidgetLive(signedIn = true, track = track, discIds = listOf("d1", "d2"), isPlaying = true)

    // The snapshot

    @Test
    fun snapshotRoundTrips() {
        val snapshot = WidgetSnapshot.next(null, playing, accentRGB = 0xFFCC00, artFile = "art-1.jpg", now = 1234)
        assertEquals(snapshot, WidgetSnapshot.decode(snapshot.encode()))
        assertEquals("Song Two", snapshot.trackTitle)
        assertEquals(listOf("d1", "d2"), snapshot.discIds)
        assertEquals(0xFFCC00, snapshot.accentRGB)
        assertEquals(1234, snapshot.updatedAt)
    }

    @Test
    fun snapshotUsesTheDesignsFieldNames() {
        val text = WidgetSnapshot.next(null, playing, accentRGB = 1, artFile = "a.jpg", now = 5).encode()
        listOf("trackTitle", "artist", "album", "albumId", "discIds", "isPlaying", "accentRGB", "artFile", "signedIn", "updatedAt")
            .forEach { assertTrue("\"$it\"" in text, it) }
    }

    @Test
    fun unreadableSnapshotIsNone() {
        assertNull(WidgetSnapshot.decode(null))
        assertNull(WidgetSnapshot.decode(""))
        assertNull(WidgetSnapshot.decode("{not json"))
        assertNull(WidgetSnapshot.decode("[1, 2]"))
        // A newer app's extra fields don't stop an older reader.
        assertEquals("X", WidgetSnapshot.decode("""{"signedIn":true,"trackTitle":"X","somethingNew":3}""")?.trackTitle)
    }

    @Test
    fun nothingLoadedKeepsTheLastTrackPaused() {
        val before = WidgetSnapshot.next(null, playing, accentRGB = 0x123456, artFile = "art-1.jpg", now = 1)
        val after = WidgetSnapshot.next(before, WidgetLive(signedIn = true, maniacMode = true), accentRGB = null, artFile = null, now = 2)
        assertEquals("Song Two", after.trackTitle)
        assertEquals(listOf("d1", "d2"), after.discIds)
        assertEquals("art-1.jpg", after.artFile)
        assertEquals(0x123456, after.accentRGB)
        assertFalse(after.isPlaying)
        assertTrue(after.maniacMode)
        assertEquals(2, after.updatedAt)
    }

    @Test
    fun signingOutForgetsTheTrack() {
        val before = WidgetSnapshot.next(null, playing, accentRGB = null, artFile = "art-1.jpg", now = 1)
        val after = WidgetSnapshot.next(before, WidgetLive(signedIn = false), accentRGB = null, artFile = null, now = 2)
        assertFalse(after.signedIn)
        assertNull(after.trackTitle)
        assertNull(after.artFile)
        assertEquals(emptyList(), after.replayDiscIds)
    }

    @Test
    fun aPlaylistsTrackReplaysItsOwnAlbum() {
        val snapshot = WidgetSnapshot.next(null, playing.copy(discIds = emptyList()), accentRGB = null, artFile = null, now = 1)
        assertEquals(listOf("d1"), snapshot.replayDiscIds)
        assertEquals(emptyList(), snapshot.copy(albumId = "").replayDiscIds)
    }

    // What it shows

    @Test
    fun playingShowsTheTrackWithPauseAndSkips() {
        val shown = WidgetContent.of(WidgetSnapshot.next(null, playing, accentRGB = 0xFF0000, artFile = "a.jpg", now = 1), playerAlive = true, now = 1)
        shown as WidgetContent.Track
        assertEquals("Song Two", shown.title)
        assertEquals("The Album", shown.album)
        assertTrue(shown.isPlaying)
        assertTrue(shown.showSkips)
        assertEquals(0xFF0000, shown.accentRGB)
        assertEquals("a.jpg", shown.artFile)
        assertEquals("Song Two by The Band", shown.description)
    }

    @Test
    fun stoppedWithTheAppClosedShowsTheLastTrackWithPlay() {
        val snapshot = WidgetSnapshot.next(null, playing, accentRGB = null, artFile = null, now = 1)
        val shown = WidgetContent.of(snapshot, playerAlive = false, now = 1) as WidgetContent.Track
        assertEquals("Song Two", shown.title)
        // Said "playing" when the app was closed: out of date.
        assertFalse(shown.isPlaying)
        assertEquals(WidgetContent.TETSUBI_BLUE, shown.accentRGB)
    }

    @Test
    fun nothingPlayedYet() {
        assertEquals(WidgetContent.Message("Nothing playing"), WidgetContent.of(null, playerAlive = true, now = 1))
        assertEquals(WidgetContent.Message("Nothing playing"), WidgetContent.of(WidgetSnapshot(signedIn = true), playerAlive = true, now = 1))
    }

    @Test
    fun signedOut() {
        assertEquals(WidgetContent.Message("Open Tetsubi to sign in"), WidgetContent.of(WidgetSnapshot(signedIn = false), playerAlive = true, now = 1))
    }

    @Test
    fun maniacModeHidesSkips() {
        val snapshot = WidgetSnapshot.next(null, playing.copy(maniacMode = true), accentRGB = null, artFile = null, now = 1)
        assertFalse((WidgetContent.of(snapshot, playerAlive = true, now = 1) as WidgetContent.Track).showSkips)
    }

    @Test
    fun noArtistReadsAsTheTitle() {
        val shown = WidgetContent.of(WidgetSnapshot(signedIn = true, trackTitle = "Solo"), playerAlive = true, now = 1) as WidgetContent.Track
        assertEquals("Solo", shown.description)
    }

    // Duration and finish time

    /** Playing a 51m 06s album with 10 minutes left, read at 100 s past 1970. */
    private val timed = playing.copy(queueSeconds = 3066.0, remainingSeconds = 600.0, readAt = 100_000)

    @Test
    fun snapshotKeepsTheQueuesLengthAndTimeLeft() {
        // Written 2 s after it was read, while playing: 2 s less left.
        val snapshot = WidgetSnapshot.next(null, timed, accentRGB = null, artFile = null, now = 102_000)
        assertEquals(3066.0, snapshot.queueSeconds)
        assertEquals(598.0, snapshot.remainingSeconds)
        assertEquals(snapshot, WidgetSnapshot.decode(snapshot.encode()))
        val text = snapshot.encode()
        assertTrue("\"queueSeconds\"" in text)
        assertTrue("\"remainingSeconds\"" in text)
        // Paused: nothing goes by.
        assertEquals(600.0, WidgetSnapshot.next(null, timed.copy(isPlaying = false), null, null, now = 500_000).remainingSeconds)
        // An older snapshot without them reads as not known.
        assertEquals(0.0, WidgetSnapshot.decode("""{"signedIn":true,"trackTitle":"X"}""")?.queueSeconds)
    }

    @Test
    fun aKeptTrackHasItsWholeAlbumLeft() {
        val before = WidgetSnapshot.next(null, timed, accentRGB = null, artFile = null, now = 100_000)
        val after = WidgetSnapshot.next(before, WidgetLive(signedIn = true), accentRGB = null, artFile = null, now = 200_000)
        assertEquals(3066.0, after.queueSeconds)
        assertEquals(3066.0, after.remainingSeconds)
    }

    @Test
    fun playingFinishesTheTimeLeftAfterTheSnapshot() {
        val snapshot = WidgetSnapshot.next(null, timed, accentRGB = null, artFile = null, now = 100_000)
        val shown = WidgetContent.of(snapshot, playerAlive = true, now = 160_000) as WidgetContent.Track
        assertEquals(100_000L + 600_000, shown.finishAt)
        assertEquals("Duration: 51m 06s   End time: T700000", shown.times(medium = true) { "T$it" })
        assertEquals("End time: T700000", shown.times(medium = false) { "T$it" })
    }

    @Test
    fun pausedFinishesTheTimeLeftAfterNow() {
        val snapshot = WidgetSnapshot.next(null, timed.copy(isPlaying = false), accentRGB = null, artFile = null, now = 100_000)
        // A minute later the finish time is a minute later.
        assertEquals(160_000L + 600_000, WidgetContent.finishAt(snapshot, playerAlive = true, now = 160_000))
        assertEquals(220_000L + 600_000, WidgetContent.finishAt(snapshot, playerAlive = true, now = 220_000))
    }

    @Test
    fun appClosedShowsOnlyTheDuration() {
        val snapshot = WidgetSnapshot.next(null, timed, accentRGB = null, artFile = null, now = 100_000)
        val shown = WidgetContent.of(snapshot, playerAlive = false, now = 900_000) as WidgetContent.Track
        assertNull(shown.finishAt)
        assertEquals("Duration: 51m 06s", shown.times(medium = true) { "T$it" })
        assertEquals("Duration: 51m 06s", shown.times(medium = false) { "T$it" })
    }

    @Test
    fun unknownLengthShowsNoTimes() {
        val snapshot = WidgetSnapshot.next(null, playing, accentRGB = null, artFile = null, now = 1)
        val shown = WidgetContent.of(snapshot, playerAlive = true, now = 1) as WidgetContent.Track
        assertNull(shown.finishAt)
        assertNull(shown.times(medium = true) { "T$it" })
        assertNull(shown.times(medium = false) { "T$it" })
    }

    @Test
    fun theWidgetIsRedrawnOnlyWhenTheFinishTimeMoves() {
        // Playing on: the time left goes down as the clock goes up.
        assertTrue(timed.sameForWidget(timed.copy(remainingSeconds = 590.0, readAt = 110_000)))
        // A seek of a minute.
        assertFalse(timed.sameForWidget(timed.copy(remainingSeconds = 530.0, readAt = 110_000)))
        // Paused: the time left stays; a seek while paused changes it.
        val paused = timed.copy(isPlaying = false)
        assertTrue(paused.sameForWidget(paused.copy(readAt = 900_000)))
        assertFalse(paused.sameForWidget(paused.copy(remainingSeconds = 300.0)))
        // Anything else changing always counts.
        assertFalse(timed.sameForWidget(paused))
        assertFalse(timed.sameForWidget(timed.copy(queueSeconds = 100.0)))
        assertFalse(timed.sameForWidget(timed.copy(maniacMode = true)))
    }

    @Test
    fun pausedWidgetRedrawsJustAfterEachMinute() {
        assertEquals(60_250L, WidgetContent.millisToNextMinute(0))
        assertEquals(251L, WidgetContent.millisToNextMinute(119_999))
        assertEquals(30_250L, WidgetContent.millisToNextMinute(90_000))
    }

    // What Play does

    @Test
    fun playTogglesAQueue() {
        assertEquals(WidgetPlay.Toggle, WidgetPlay.of(hasQueue = true, snapshot = null))
    }

    @Test
    fun playWithNoQueueStartsTheLastAlbumAgain() {
        val snapshot = WidgetSnapshot.next(null, playing, accentRGB = null, artFile = null, now = 1)
        assertEquals(WidgetPlay.Replay(listOf("d1", "d2")), WidgetPlay.of(hasQueue = false, snapshot = snapshot))
    }

    @Test
    fun playWithNothingToPlayOpensTheApp() {
        assertEquals(WidgetPlay.OpenApp, WidgetPlay.of(hasQueue = false, snapshot = null))
        assertEquals(WidgetPlay.OpenApp, WidgetPlay.of(hasQueue = false, snapshot = WidgetSnapshot(signedIn = true)))
        assertEquals(WidgetPlay.OpenApp, WidgetPlay.of(hasQueue = false, snapshot = WidgetSnapshot(signedIn = false, albumId = "d1")))
    }

    // Once a second

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun throttlePassesTheFirstAtOnceThenTheLatestEachSecond() = runTest {
        val source = flow {
            // A burst: ten changes 50 ms apart, then one more after a pause.
            repeat(10) {
                emit(it)
                delay(50)
            }
            delay(3000)
            emit(10)
        }
        // The first, the latest of the burst, then the late one.
        assertEquals(listOf(0, 9, 10), source.throttleLatest(1000).toList())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun throttleWaitsASecondBetweenValues() = runTest {
        val times = mutableListOf<Long>()
        flow {
            repeat(5) {
                emit(it)
                delay(100)
            }
        }.throttleLatest(1000).collect { times += currentTime }
        assertEquals(listOf(0L, 1000L), times)
    }
}
