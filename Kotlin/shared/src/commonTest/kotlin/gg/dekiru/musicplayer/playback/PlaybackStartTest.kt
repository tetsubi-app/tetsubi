package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Starting a track: a restored queue opens nothing until Play, and the loading state while a stream starts. */
class PlaybackStartTest {

    private fun track(album: String, i: Int) = """
        {"Id":"$album-t$i","Name":"$album $i","IndexNumber":$i,"AlbumId":"$album","RunTimeTicks":${i * 600_000_000L},
         "AlbumArtists":[{"Name":"Band"}],"MediaSources":[{"Container":"flac","Codecs":"flac"}]}
    """

    private fun album(id: String) = JellyfinAlbum(id = id, name = "Album $id", albumArtists = listOf(JellyfinPerson(name = "Band")))

    private class Setup(val server: FakeServer, val engine: FakeEngine, val player: PlayerController) {
        /** The controller's clock, in ms. */
        var now = 0L
    }

    private fun TestScope.setup(): Setup {
        val server = FakeServer { request ->
            when (request.path) {
                "/Items" -> {
                    val parent = request.url.parameters["ParentId"]!!
                    json("""{"Items":[${(1..3).joinToString(",") { track(parent, it) }}]}""")
                }
                else -> json("")
            }
        }
        val engine = FakeEngine()
        var setup: Setup? = null
        val player = PlayerController(
            server.api(token = "tok", userId = "u1"), engine, PlaybackSettings(InMemoryPreferences()), backgroundScope,
            random = Random(7), clock = { setup!!.now },
        )
        return Setup(server, engine, player).also { setup = it }
    }

    private fun Setup.reports() = server.requests.filter { it.path.startsWith("/Sessions/Playing") }
        .map { it.path.removePrefix("/Sessions/Playing").ifEmpty { "start" } }

    /** A queue saved 42 s into album A's second track. */
    private suspend fun TestScope.savedQueue(): PlayQueueSnapshot {
        val s = setup()
        s.player.play(album("A"))
        settle()
        s.engine.finishTrack()
        return s.player.playQueue.snapshot(42.0, "http://jellyfin-box:8096", "u1")
    }

    @Test
    fun aRestoredQueueOpensNoStreamUntilPlay() = runTest {
        val snapshot = savedQueue()
        val r = setup()
        r.player.restore(snapshot)
        settle()
        // Shown (Now Playing, the bar, the widget) at the saved point…
        assertEquals("A-t2", r.player.currentTrack?.id)
        assertEquals(42.0, r.player.position)
        assertEquals(120.0, r.player.duration)
        // …held by the engine, but nothing opened, played or reported.
        assertEquals(listOf("A-t1", "A-t2", "A-t3"), r.engine.items.map { it.id })
        assertEquals(1, r.engine.cues)
        assertEquals(0, r.engine.loads)
        assertFalse(r.engine.opened)
        assertFalse(r.engine.playing)
        assertTrue(r.engine.seeks.isEmpty())
        assertEquals(emptyList(), r.reports())
        assertFalse(r.player.isStarting)
    }

    @Test
    fun playAfterARestoreOpensTheTrackAtTheSavedPoint() = runTest {
        val snapshot = savedQueue()
        val r = setup()
        r.player.restore(snapshot)
        r.player.togglePlayPause()
        settle()
        assertTrue(r.engine.opened)
        assertEquals(42_000L, r.engine.openedAt)
        assertEquals(0, r.engine.loads, "the cued queue is played, not loaded again")
        assertTrue(r.player.isPlaying)
        assertEquals(listOf("start"), r.reports())
    }

    @Test
    fun aSeekBeforePlayMovesTheHeldPoint() = runTest {
        val snapshot = savedQueue()
        val r = setup()
        r.player.restore(snapshot)
        r.player.seek(70.0)
        assertFalse(r.engine.opened)
        r.player.resume()
        assertEquals(70_000L, r.engine.openedAt)
    }

    @Test
    fun whileATrackStartsTheButtonShowsLoadingAndPressesDontPause() = runTest {
        val s = setup()
        s.engine.startsInstantly = false
        s.player.play(album("A"))
        settle()
        assertTrue(s.player.isStarting, "loading until the first sound")
        assertFalse(s.player.isPlaying)
        // Impatient presses: ignored, nothing paused or toggled.
        repeat(3) { s.player.togglePlayPause() }
        assertTrue(s.engine.starting)
        assertTrue(s.player.isStarting)
        s.engine.soundStarts()
        assertFalse(s.player.isStarting)
        assertTrue(s.player.isPlaying)
        // Once it plays, the button is Pause again.
        s.player.togglePlayPause()
        assertFalse(s.engine.playing)
        assertFalse(s.player.isPlaying)
    }

    @Test
    fun aPressAfterTenSecondsOfStartingPauses() = runTest {
        val s = setup()
        s.engine.startsInstantly = false
        s.player.play(album("A"))
        settle()
        s.now += 9_000
        s.player.togglePlayPause()
        assertTrue(s.engine.starting, "still within the first 10 s: ignored")
        s.now += 1_500
        assertFalse(s.player.ignoresPlayPause)
        s.player.togglePlayPause()
        assertFalse(s.engine.starting, "a start going nowhere is stopped")
        assertFalse(s.player.isStarting)
        assertFalse(s.player.isPlaying)
        // Play starts it again.
        s.player.togglePlayPause()
        assertTrue(s.engine.starting)
        assertTrue(s.player.ignoresPlayPause, "a new start: presses ignored again")
    }

    @Test
    fun aPressWhileAPlayedStreamReconnectsPausesAndPlayResumesThere() = runTest {
        val s = setup()
        s.player.play(album("A"))
        settle()
        s.engine.positionMillis = 30_000
        s.engine.streamDrops()
        assertTrue(s.player.isStarting, "the spinner shows while it reconnects")
        assertTrue(s.player.isPlaying)
        assertFalse(s.player.ignoresPlayPause)
        s.player.togglePlayPause()
        assertFalse(s.engine.reconnecting, "the tries end")
        assertFalse(s.player.isStarting)
        assertFalse(s.player.isPlaying)
        s.player.togglePlayPause()
        assertTrue(s.engine.playing)
        assertEquals(30_000L, s.engine.positionMillis, "Play carries on from the held point")
    }

    @Test
    fun playAfterARestoreShowsLoadingUntilSound() = runTest {
        val snapshot = savedQueue()
        val r = setup()
        r.engine.startsInstantly = false
        r.player.restore(snapshot)
        r.player.togglePlayPause()
        assertTrue(r.player.isStarting)
        r.player.togglePlayPause()
        assertTrue(r.engine.starting, "a second press didn't pause the start")
        r.engine.soundStarts()
        assertFalse(r.player.isStarting)
        assertTrue(r.player.isPlaying)
    }

    @Test
    fun aFailedStartEndsTheLoadingState() = runTest {
        val s = setup()
        s.engine.startsInstantly = false
        s.player.play(album("A"))
        settle()
        assertTrue(s.player.isStarting)
        s.engine.listener?.onError("Playback failed (ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT).")
        assertFalse(s.player.isStarting)
        assertTrue(s.player.trackFailed)
    }

    @Test
    fun onlyConvertedStreamsGetAShortFirstResponseLimit() {
        val converted = "http://server/Audio/t1/universal?UserId=u&Container=flac&StartTimeTicks=490000000"
        val direct = "http://server/Audio/t1/stream.flac?static=true"
        assertEquals(StreamTimeouts.CONVERTED_FIRST_RESPONSE_MILLIS, StreamTimeouts.firstResponseLimitMillis(converted))
        assertEquals(8_000L, StreamTimeouts.firstResponseLimitMillis(converted))
        assertNull(StreamTimeouts.firstResponseLimitMillis(direct))
        assertNull(StreamTimeouts.firstResponseLimitMillis("file:///data/music/01 - Song.flac"))
        assertTrue(StreamTimeouts.CONVERTED_FIRST_RESPONSE_MILLIS < StreamTimeouts.READ_MILLIS)
    }
}
