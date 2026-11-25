package gg.dekiru.musicplayer.playback.mpris

import gg.dekiru.musicplayer.playback.RepeatMode
import gg.dekiru.musicplayer.testing.PrivateBus
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The MPRIS object over a private D-Bus, asked as a desktop would. Skipped without dbus-daemon. */
class MprisServiceTest {
    private val calls = LinkedBlockingQueue<String>()
    private var bus: PrivateBus? = null
    private var service: MprisService? = null
    private var client: DBusConnection? = null

    private val controls = object : MprisControls {
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun playPause() { calls += "playPause" }
        override fun stop() { calls += "stop" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekBy(offsetMicros: Long) { calls += "seekBy $offsetMicros" }
        override fun setPosition(entryId: String, positionMicros: Long) { calls += "setPosition $entryId $positionMicros" }
        override fun setRepeat(mode: RepeatMode) { calls += "repeat $mode" }
        override fun setShuffle(on: Boolean) { calls += "shuffle $on" }
        override fun raise() { calls += "raise" }
        override fun quit() { calls += "quit" }
        override fun positionMicros() = 42_000_000L
    }

    private val track = MprisTrack(
        entryId = "e-1",
        title = "Song",
        artist = "Band",
        album = "Record",
        lengthMicros = 200_000_000,
        artUrl = "file:///tmp/cover.jpg",
    )

    private fun start(): DBusConnection {
        val b = PrivateBus.start().also { bus = it }
        service = assertNotNull(MprisService.start(controls, desktopEntry = "tetsubi", busAddress = b.address))
        return DBusConnectionBuilder.forAddress(b.address).withShared(false).build().also { client = it }
    }

    @AfterTest
    fun tearDown() {
        client?.close()
        service?.close()
        bus?.close()
    }

    private fun DBusConnection.properties() = getRemoteObject(Mpris.BUS_NAME, Mpris.OBJECT_PATH, Properties::class.java)
    private fun DBusConnection.player() = getRemoteObject(Mpris.BUS_NAME, Mpris.OBJECT_PATH, MediaPlayer2Player::class.java)
    private fun DBusConnection.root() = getRemoteObject(Mpris.BUS_NAME, Mpris.OBJECT_PATH, MediaPlayer2::class.java)

    private fun nextCall(): String? = calls.poll(5, TimeUnit.SECONDS)

    @Test
    fun answersPropertiesAndSignalsChanges() {
        val c = start()
        val changes = LinkedBlockingQueue<Properties.PropertiesChanged>()
        c.addSigHandler(Properties.PropertiesChanged::class.java) { changes += it }

        val idle = c.properties().GetAll(Mpris.PLAYER_INTERFACE)
        assertEquals("Stopped", idle["PlaybackStatus"]?.value)
        assertEquals(emptyMap<String, Any>(), idle["Metadata"]?.value)
        assertEquals(false, idle["CanPlay"]?.value)

        val root = c.properties().GetAll(Mpris.ROOT_INTERFACE)
        assertEquals("Tetsubi", root["Identity"]?.value)
        assertEquals("tetsubi", root["DesktopEntry"]?.value)

        service!!.update(MprisState(track, MprisStatus.PLAYING, RepeatMode.ALL, shuffle = true, hasNext = true, canPlay = true))
        val change = assertNotNull(changes.poll(5, TimeUnit.SECONDS))
        assertEquals(Mpris.PLAYER_INTERFACE, change.interfaceName)
        assertEquals("Playing", change.propertiesChanged["PlaybackStatus"]?.value)
        assertFalse("Rate" in change.propertiesChanged, "only what changed")

        val now = c.properties().GetAll(Mpris.PLAYER_INTERFACE)
        assertEquals("Playlist", now["LoopStatus"]?.value)
        assertEquals(true, now["Shuffle"]?.value)
        assertEquals(42_000_000L, now["Position"]?.value)
        assertEquals(true, now["CanGoNext"]?.value)
        assertEquals(true, now["CanSeek"]?.value)
        @Suppress("UNCHECKED_CAST")
        val metadata = now["Metadata"]?.value as Map<String, Variant<*>>
        assertEquals("Song", metadata["xesam:title"]?.value)
        assertEquals(listOf("Band"), metadata["xesam:artist"]?.value)
        assertEquals("Record", metadata["xesam:album"]?.value)
        assertEquals(200_000_000L, metadata["mpris:length"]?.value)
        assertEquals("file:///tmp/cover.jpg", metadata["mpris:artUrl"]?.value)
        assertEquals(Mpris.trackPath("e-1"), (metadata["mpris:trackid"]?.value as DBusPath).path)

        // Nothing changed: no signal.
        service!!.update(MprisState(track, MprisStatus.PLAYING, RepeatMode.ALL, shuffle = true, hasNext = true, canPlay = true))
        assertNull(changes.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun passesControlsOn() {
        val c = start()
        service!!.update(MprisState(track, MprisStatus.PAUSED, hasNext = true, canPlay = true))
        val player = c.player()
        player.PlayPause()
        assertEquals("playPause", nextCall())
        player.Next()
        assertEquals("next", nextCall())
        player.Previous()
        assertEquals("previous", nextCall())
        player.Seek(-5_000_000)
        assertEquals("seekBy -5000000", nextCall())
        player.SetPosition(DBusPath(Mpris.trackPath("e-1")), 10_000_000)
        assertEquals("setPosition e-1 10000000", nextCall())
        // Another track's ID, or past the end: ignored (the spec).
        player.SetPosition(DBusPath(Mpris.trackPath("e-2")), 10_000_000)
        player.SetPosition(DBusPath(Mpris.trackPath("e-1")), 300_000_000)
        player.Stop()
        assertEquals("stop", nextCall())

        c.properties().Set(Mpris.PLAYER_INTERFACE, "LoopStatus", "Track")
        assertEquals("repeat ONE", nextCall())
        c.properties().Set(Mpris.PLAYER_INTERFACE, "Shuffle", true)
        assertEquals("shuffle true", nextCall())

        c.root().Raise()
        assertEquals("raise", nextCall())
    }

    @Test
    fun maniacModeRefusesSkipsAndSeeks() {
        val c = start()
        service!!.update(MprisState(track, MprisStatus.PLAYING, hasNext = true, canPlay = true, canSkip = false))
        val now = c.properties().GetAll(Mpris.PLAYER_INTERFACE)
        assertEquals(false, now["CanGoNext"]?.value)
        assertEquals(false, now["CanGoPrevious"]?.value)
        assertEquals(false, now["CanSeek"]?.value)
        val player = c.player()
        player.Next()
        player.Previous()
        player.Seek(1_000_000)
        c.properties().Set(Mpris.PLAYER_INTERFACE, "Shuffle", true)
        player.Pause()
        // Only the pause got through.
        assertEquals("pause", nextCall())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun trackPathsAreValidObjectPaths() {
        assertEquals("/gg/dekiru/musicplayer/entry/abc_1_2", Mpris.trackPath("abc-1.2"))
        assertEquals("/gg/dekiru/musicplayer/entry/_", Mpris.trackPath(""))
    }
}
