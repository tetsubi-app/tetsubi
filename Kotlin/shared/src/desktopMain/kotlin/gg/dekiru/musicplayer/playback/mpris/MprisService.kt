@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package gg.dekiru.musicplayer.playback.mpris

import gg.dekiru.musicplayer.playback.RepeatMode
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.InvalidMethodArgument
import org.freedesktop.dbus.errors.NotSupported
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownInterface
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/** `org.mpris.MediaPlayer2`: the app itself. */
@DBusInterfaceName(Mpris.ROOT_INTERFACE)
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

/** `org.mpris.MediaPlayer2.Player`: playback. */
@DBusInterfaceName(Mpris.PLAYER_INTERFACE)
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()

    /** `offset` in µs, either way. */
    fun Seek(offset: Long)

    /** `position` in µs; ignored unless `trackId` is the playing track. */
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)

    /** The position jumped (a seek, from here or the app); µs. */
    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

/**
 * What MPRIS asks of the app. Called on D-Bus threads: implementations move
 * to the main thread themselves, and [positionMicros] must answer from any thread.
 */
interface MprisControls {
    fun play()
    fun pause()
    fun playPause()

    /** MPRIS's Stop. */
    fun stop()
    fun next()
    fun previous()

    /** By `offsetMicros` from where it is. */
    fun seekBy(offsetMicros: Long)

    /** To `positionMicros` in queue entry `entryId`, if that's the one playing. */
    fun setPosition(entryId: String, positionMicros: Long)
    fun setRepeat(mode: RepeatMode)
    fun setShuffle(on: Boolean)
    fun raise()
    fun quit()
    fun positionMicros(): Long
}

/**
 * Tetsubi on the session bus as an MPRIS player (`org.mpris.MediaPlayer2.tetsubi`),
 * so media keys, the desktop's now-playing panel and `playerctl` reach it.
 * [update] gives it the player's state and signals what changed.
 *
 * [start] returns null where there's no session bus (logged): the app plays on without it.
 */
class MprisService private constructor(
    private val connection: DBusConnection,
    private val controls: MprisControls,
    private val identity: String,
    private val desktopEntry: String?,
) : AutoCloseable {

    @Volatile private var state = MprisState()

    /** Entry IDs by their D-Bus path, for SetPosition (paths can't hold every character an ID may). */
    @Volatile private var playingPath: Pair<String, String>? = null

    private val exported = Exported()

    /** Takes the new state and signals the properties that changed. */
    fun update(new: MprisState) {
        val old = state
        state = new
        playingPath = new.track?.let { Mpris.trackPath(it.entryId) to it.entryId }
        val changed = Mpris.changed(Mpris.playerProperties(old), Mpris.playerProperties(new))
        if (changed.isNotEmpty()) signal(Properties.PropertiesChanged(Mpris.OBJECT_PATH, Mpris.PLAYER_INTERFACE, changed, emptyList()))
    }

    /** The position jumped to `positionMicros`. */
    fun seeked(positionMicros: Long) = signal(MediaPlayer2Player.Seeked(Mpris.OBJECT_PATH, positionMicros))

    private fun signal(signal: DBusSignal) {
        try {
            connection.sendMessage(signal)
        } catch (e: Exception) {
            log("Couldn't send ${signal.name}: ${e.message}")
        }
    }

    override fun close() {
        runCatching { connection.unExportObject(Mpris.OBJECT_PATH) }
        runCatching { connection.close() }
    }

    /** The object at /org/mpris/MediaPlayer2. */
    private inner class Exported : MediaPlayer2, MediaPlayer2Player, Properties {
        override fun getObjectPath() = Mpris.OBJECT_PATH

        override fun Raise() = controls.raise()
        override fun Quit() = controls.quit()

        override fun Next() { if (state.canSkip) controls.next() }
        override fun Previous() { if (state.canSkip) controls.previous() }
        override fun Pause() = controls.pause()
        override fun PlayPause() = controls.playPause()
        override fun Stop() = controls.stop()
        override fun Play() = controls.play()

        override fun Seek(offset: Long) { if (state.canSkip) controls.seekBy(offset) }

        override fun SetPosition(trackId: DBusPath, position: Long) {
            val (path, entryId) = playingPath ?: return
            if (!state.canSkip || trackId.path != path) return
            val length = state.track?.lengthMicros ?: 0
            if (position < 0 || (length > 0 && position > length)) return
            controls.setPosition(entryId, position)
        }

        override fun OpenUri(uri: String): Unit = throw NotSupported("Tetsubi plays from your Jellyfin server only")

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            (GetAll(interfaceName)[propertyName] ?: throw UnknownProperty("No property $interfaceName.$propertyName")) as A

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            Mpris.ROOT_INTERFACE -> Mpris.rootProperties(identity, desktopEntry)
            Mpris.PLAYER_INTERFACE -> Mpris.playerProperties(state) + ("Position" to Variant(controls.positionMicros().coerceAtLeast(0)))
            else -> throw UnknownInterface("No interface $interfaceName")
        }

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
            if (interfaceName != Mpris.PLAYER_INTERFACE) throw PropertyReadOnly("Nothing to set on $interfaceName")
            val raw = (value as? Variant<*>)?.value ?: value
            when (propertyName) {
                "LoopStatus" -> {
                    val mode = Mpris.repeatMode(raw as? String ?: "") ?: throw InvalidMethodArgument("LoopStatus: None, Track or Playlist")
                    if (state.canSkip) controls.setRepeat(mode)
                }
                "Shuffle" -> if (state.canSkip) controls.setShuffle(raw as? Boolean ?: throw InvalidMethodArgument("Shuffle: a boolean"))
                // Rate stays 1, and the volume is the system's (see playerProperties): accepted and ignored.
                "Rate", "Volume" -> Unit
                else -> throw PropertyReadOnly("$propertyName can't be set")
            }
        }
    }

    companion object {
        /**
         * Connects to the session bus and takes [Mpris.BUS_NAME] (or, if
         * another Tetsubi already has it, that name with `.instance<pid>`, as
         * the spec says). Null if there's no session bus. `busAddress`: another
         * bus than the session's (tests).
         */
        fun start(
            controls: MprisControls,
            identity: String = "Tetsubi",
            desktopEntry: String? = null,
            busAddress: String? = null,
        ): MprisService? {
            val connection = try {
                val builder = if (busAddress != null) DBusConnectionBuilder.forAddress(busAddress) else DBusConnectionBuilder.forSessionBus()
                builder.withShared(false).build()
            } catch (e: Throwable) {
                // Throwable: a runtime missing jdk.security.auth fails with NoClassDefFoundError.
                log("No session bus, so no media keys: ${e.message}")
                return null
            }
            val service = MprisService(connection, controls, identity, desktopEntry)
            try {
                connection.exportObject(Mpris.OBJECT_PATH, service.exported)
                try {
                    connection.requestBusName(Mpris.BUS_NAME)
                } catch (e: Exception) {
                    connection.requestBusName("${Mpris.BUS_NAME}.instance${ProcessHandle.current().pid()}")
                }
            } catch (e: Exception) {
                log("Couldn't register on the session bus: ${e.message}")
                service.close()
                return null
            }
            return service
        }

        internal fun log(message: String) = System.err.println("Tetsubi MPRIS: $message")
    }
}
