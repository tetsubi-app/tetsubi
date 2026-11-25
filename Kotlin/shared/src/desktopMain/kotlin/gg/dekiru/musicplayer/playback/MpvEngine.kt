package gg.dekiru.musicplayer.playback

import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import gg.dekiru.musicplayer.AppInfo
import java.awt.EventQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The Linux player: libmpv (mpv's library) through its C API. mpv's playlist
 * holds the whole queue, so mpv joins tracks gaplessly (`gapless-audio`)
 * and opens the next one ahead (`prefetch-playlist`); [MpvPlayback] keeps
 * the queue and turns mpv's events into [EngineListener] calls, as
 * `Media3Engine` does on Android. Streams send the `Authorization` header.
 *
 * Threads: the app calls in on the main thread (the AWT event thread);
 * commands go to mpv in order on one command thread, so a slow server never
 * blocks the window; one event thread waits for mpv's events and passes
 * them back to the main thread ([post]). Position and length are read from
 * values mpv reports, never by asking mpv on the main thread.
 *
 * Without libmpv (not installed) [isSupported] is false, Play stays off and
 * [unavailableReason] tells Home what to install; why is logged.
 *
 * `options`: extra mpv options set before it starts (tests use `ao=null`).
 */
class MpvEngine(
    options: Map<String, String> = emptyMap(),
    /** Where listener calls run: the main thread. */
    private val post: (Runnable) -> Unit = EventQueue::invokeLater,
) : PlayerEngine, AutoCloseable {

    private val lib: MpvLibrary?
    private val ctx: Pointer?

    override val isSupported: Boolean
    override val unavailableReason: String?

    /**
     * Everything FFmpeg decodes is played as it is, so the server sends the
     * original file: a file the server converts has padding at each end, a
     * gap between tracks.
     */
    override val directPlayFormats: Set<AudioFormat> = DIRECT_PLAY_FORMATS

    override var listener: EngineListener? = null

    private val commands: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "mpv-commands").apply { isDaemon = true } }
    private var eventThread: Thread? = null
    private val closed = AtomicBoolean(false)

    private val playback = MpvPlayback(send = ::send, listener = { listener })

    // Written by the event thread.
    @Volatile private var timePos = 0.0
    @Volatile private var mpvDuration = 0.0

    /**
     * A held start being sought after Play loaded a direct stream (a cued
     * queue): shown as the position until mpv gets there, rather than 0:00
     * while the file opens. Null otherwise.
     */
    @Volatile private var seekTarget: Double? = null

    /** Repeat One, for the event thread (mpv loops the file itself). */
    @Volatile private var loopOne = false

    /** Seeks we asked for and haven't seen mpv start: any other seek is mpv's own (Repeat One going round). */
    private val ownSeeks = AtomicInteger(0)

    /**
     * Whether mpv has the playing file open. mpv rejects a seek before then
     * ("error running command"), so a seek that comes early waits in
     * [pendingSeek] and is sent when the file has loaded. A new file (load,
     * skip, stop) drops a waiting seek: it was for the one before.
     */
    @Volatile private var fileLoaded = false
    private val pendingSeek = AtomicReference<List<String>?>(null)

    init {
        val loaded = MpvLibrary.load()
        var handle: Pointer? = null
        var reason: String? = null
        val library = loaded.getOrNull()
        if (library == null) {
            log("Playback is off: ${loaded.exceptionOrNull()?.message}")
            reason = MISSING_LIBRARY_MESSAGE
        } else {
            handle = start(library, options)
            if (handle == null) reason = "The audio player (libmpv) couldn't start. Playback is off."
        }
        lib = library.takeIf { handle != null }
        ctx = handle
        isSupported = handle != null
        unavailableReason = reason
        if (handle != null && library != null) {
            eventThread = Thread({ eventLoop(library, handle) }, "mpv-events").apply {
                isDaemon = true
                start()
            }
            Runtime.getRuntime().addShutdownHook(Thread(::close, "mpv-shutdown"))
        }
    }

    /** Makes and starts the player; null (logged) if it won't. */
    private fun start(library: MpvLibrary, extra: Map<String, String>): Pointer? {
        CLibrary.useCNumericLocale().onFailure { log("Couldn't set the C numeric locale libmpv needs: ${it.message}") }
        val handle = library.mpv_create() ?: run {
            log("mpv_create failed")
            return null
        }
        for ((name, value) in baseOptions() + extra) {
            val code = library.mpv_set_option_string(handle, name, value)
            if (code < 0) log("Option $name: ${library.mpv_error_string(code)}")
        }
        val code = library.mpv_initialize(handle)
        if (code < 0) {
            log("mpv_initialize failed: ${library.mpv_error_string(code)}")
            library.mpv_terminate_destroy(handle)
            return null
        }
        library.mpv_request_log_messages(handle, "warn")
        library.mpv_observe_property(handle, PROP_TIME_POS, "time-pos", MpvLibrary.FORMAT_DOUBLE)
        library.mpv_observe_property(handle, PROP_DURATION, "duration", MpvLibrary.FORMAT_DOUBLE)
        library.mpv_observe_property(handle, PROP_PAUSE, "pause", MpvLibrary.FORMAT_FLAG)
        library.mpv_observe_property(handle, PROP_IDLE, "idle-active", MpvLibrary.FORMAT_FLAG)
        library.mpv_observe_property(handle, PROP_CORE_IDLE, "core-idle", MpvLibrary.FORMAT_FLAG)
        return handle
    }

    // PlayerEngine

    override fun setAuthorization(header: String?) = authorizationCommands(header).forEach(::send)

    override fun setQueue(items: List<EngineItem>, startIndex: Int) {
        if (!isSupported) return
        resetTimes()
        seekTarget = null
        playback.setQueue(items, startIndex)
    }

    /** mpv's playlist holds the queue, but no file is opened until Play ([MpvPlayback.cue]). */
    override fun cue(items: List<EngineItem>, startIndex: Int, startMillis: Long) {
        if (!isSupported) return
        resetTimes()
        seekTarget = null
        playback.cue(items, startIndex, startMillis)
    }

    override fun replaceAfterCurrent(items: List<EngineItem>) {
        if (isSupported) playback.replaceAfterCurrent(items)
    }

    override fun replaceAroundCurrent(before: List<EngineItem>, after: List<EngineItem>) {
        if (isSupported) playback.replaceAroundCurrent(before, after)
    }

    override fun play() {
        if (!isSupported) return
        val held = playback.heldStartMillis
        playback.play()
        // The held point is sought once the file opens: shown meanwhile.
        if (held > 0 && playback.heldStartMillis == 0L) seekTarget = held / 1000.0
    }

    override fun pause() {
        if (isSupported) playback.pause()
    }

    override fun seekTo(millis: Long) {
        if (!isSupported) return
        seekTarget = null
        val converted = playback.currentItem?.seeksByRestart == true
        // mpv will report this seek: it isn't Repeat One going round.
        if (!converted) ownSeeks.incrementAndGet()
        val seeked = playback.seekTo(millis)
        if (!converted && !seeked) ownSeeks.updateAndGet { (it - 1).coerceAtLeast(0) }
        // A restarted stream begins at 0:00 of its own, at the offset of the track.
        if (converted) resetTimes() else if (seeked) timePos = millis.coerceAtLeast(0) / 1000.0
    }

    override fun skipTo(index: Int) {
        if (!isSupported || index !in playback.items.indices) return
        seekTarget = null
        if (index != playback.current) {
            resetTimes()
        } else {
            // Back to 0:00 of the playing track: mpv's seek is ours, not Repeat One.
            ownSeeks.incrementAndGet()
            timePos = 0.0
        }
        playback.skipTo(index)
    }

    override fun setRepeatMode(mode: RepeatMode) {
        loopOne = mode == RepeatMode.ONE
        if (isSupported) playback.setRepeatMode(mode)
    }

    override fun setVolume(volume: Float) = send(listOf("set", "volume", mpvVolume(volume)))

    /** Media keys and the desktop's panel go through the app's controller (`MprisBridge`), which knows Maniac Mode itself. */
    override fun setSkipControlsEnabled(enabled: Boolean) = Unit

    override fun stop() {
        if (!isSupported) return
        resetTimes()
        seekTarget = null
        playback.stop()
    }

    override val positionMillis: Long
        get() = ((seekTarget ?: timePos) * 1000).toLong().coerceAtLeast(0) + playback.currentOffsetMillis

    override val durationMillis: Long
        get() {
            // A converted stream's length is the track's (a restarted one's is the rest of it).
            playback.currentItem?.takeIf { it.seeksByRestart && it.durationMillis > 0 }?.let { return it.durationMillis }
            return (mpvDuration * 1000).toLong().coerceAtLeast(0)
        }

    /** Stops mpv and frees it (the window closing, or the JVM exiting). */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val library = lib ?: return
        val handle = ctx ?: return
        commands.shutdown()
        runCatching { commands.awaitTermination(2, TimeUnit.SECONDS) }
        library.mpv_wakeup(handle)
        eventThread?.let { if (it !== Thread.currentThread()) it.join(2_000) }
        library.mpv_terminate_destroy(handle)
    }

    private fun resetTimes() {
        timePos = 0.0
        mpvDuration = 0.0
    }

    /** Runs an mpv command on the command thread, in order. */
    private fun send(args: List<String>) {
        val library = lib ?: return
        val handle = ctx ?: return
        if (closed.get()) return
        val task = Runnable {
            if (closed.get()) return@Runnable
            when (args.first()) {
                // (loadfile here only appends to the playlist: not a new file.)
                "playlist-play-index", "playlist-next", "playlist-prev", "stop" -> {
                    fileLoaded = false
                    pendingSeek.set(null)
                }
                "seek" -> if (!fileLoaded) {
                    pendingSeek.set(args)
                    return@Runnable
                }
            }
            val code = library.mpv_command(handle, (args + null).toTypedArray())
            if (code < 0) {
                // Not the arguments: they can be stream addresses.
                log("Command ${args.first()} failed: ${library.mpv_error_string(code)}")
                if (args.first() == "playlist-play-index") deliver(MpvEvent.MoveFailed)
            }
        }
        try {
            commands.execute(task)
        } catch (e: RejectedExecutionException) {
            // Closing.
        }
    }

    private fun deliver(event: MpvEvent) = post(Runnable { if (!closed.get()) playback.handle(event) })

    /** The event thread: waits for mpv's events until the engine closes. */
    private fun eventLoop(library: MpvLibrary, handle: Pointer) {
        while (!closed.get()) {
            val event = MpvRawEvent(library.mpv_wait_event(handle, -1.0))
            when (event.id) {
                MpvLibrary.EVENT_SHUTDOWN -> return
                MpvLibrary.EVENT_PROPERTY_CHANGE -> event.data?.let { property(event.replyUserdata, it) }
                MpvLibrary.EVENT_FILE_LOADED -> {
                    fileLoaded = true
                    pendingSeek.getAndSet(null)?.let { seek ->
                        // Ours (START_FILE forgot any count): not Repeat One going round.
                        ownSeeks.incrementAndGet()
                        send(seek)
                    }
                }
                MpvLibrary.EVENT_START_FILE -> {
                    fileLoaded = false
                    resetTimes()
                    ownSeeks.set(0)
                    val pos = LongByReference(-1)
                    val code = library.mpv_get_property(handle, "playlist-pos", MpvLibrary.FORMAT_INT64, pos)
                    val position = if (code < 0) -1 else pos.value.toInt()
                    // What the entry was loaded from: the queue may have moved round it meanwhile.
                    val path = if (position < 0) null else library.mpv_get_property_string(handle, "playlist/$position/filename")?.let {
                        try {
                            it.getString(0, "UTF-8")
                        } finally {
                            library.mpv_free(it)
                        }
                    }
                    deliver(MpvEvent.StartFile(position, path))
                }
                MpvLibrary.EVENT_END_FILE -> event.data?.let { data ->
                    val reason = MpvEndReason.of(data.getInt(0))
                    val error = if (reason == MpvEndReason.ERROR) library.mpv_error_string(data.getInt(4)) else null
                    deliver(MpvEvent.EndFile(reason, error))
                }
                MpvLibrary.EVENT_SEEK -> {
                    if (ownSeeks.get() > 0) {
                        ownSeeks.decrementAndGet()
                    } else if (loopOne && (mpvDuration <= 0 || timePos >= mpvDuration - LOOP_END_WINDOW_SECONDS)) {
                        // loop-file went back to 0:00 at the end.
                        deliver(MpvEvent.Repeated)
                    }
                }
                MpvLibrary.EVENT_PLAYBACK_RESTART -> ownSeeks.set(0)
                MpvLibrary.EVENT_LOG_MESSAGE -> event.data?.let { data ->
                    val prefix = data.getPointer(0)?.getString(0, "UTF-8").orEmpty()
                    val text = data.getPointer(16)?.getString(0, "UTF-8").orEmpty().trimEnd()
                    log("[$prefix] $text")
                }
            }
        }
    }

    /** An observed property changed (mpv_event_property: name, format, data). */
    private fun property(id: Long, data: Pointer) {
        val format = data.getInt(8)
        val value = data.getPointer(16)
        when (id) {
            PROP_TIME_POS -> {
                timePos = if (format == MpvLibrary.FORMAT_DOUBLE && value != null) value.getDouble(0) else 0.0
                seekTarget?.let { if (timePos >= it - 1.0) seekTarget = null }
            }
            PROP_DURATION -> mpvDuration = if (format == MpvLibrary.FORMAT_DOUBLE && value != null) value.getDouble(0) else 0.0
            PROP_PAUSE -> if (format == MpvLibrary.FORMAT_FLAG && value != null) deliver(MpvEvent.Paused(value.getInt(0) != 0))
            PROP_IDLE -> if (format == MpvLibrary.FORMAT_FLAG && value != null) deliver(MpvEvent.Idle(value.getInt(0) != 0))
            PROP_CORE_IDLE -> if (format == MpvLibrary.FORMAT_FLAG && value != null) deliver(MpvEvent.CoreIdle(value.getInt(0) != 0))
        }
    }

    companion object {
        /** Home's notice when libmpv isn't installed. */
        const val MISSING_LIBRARY_MESSAGE = "Install mpv (libmpv) to play music on Linux."

        /** Every format the app names: FFmpeg plays them all. */
        val DIRECT_PLAY_FORMATS: Set<AudioFormat> = AudioFormat.entries.toSet()

        private const val PROP_TIME_POS = 1L
        private const val PROP_DURATION = 2L
        private const val PROP_PAUSE = 3L
        private const val PROP_IDLE = 4L
        private const val PROP_CORE_IDLE = 5L

        /** A seek mpv makes by itself this close to the end, with Repeat One on, is the track going round. */
        private const val LOOP_END_WINDOW_SECONDS = 2.0

        /**
         * mpv's settings: audio only, none of the user's mpv config or
         * scripts, no keys or terminal; idle with the playlist kept between
         * tracks; gapless joins (`gapless-audio=yes` keeps one audio output
         * across tracks, resampling a track whose format differs rather
         * than reopening it) and the next track opened ahead
         * (`prefetch-playlist`); a cache that reads well ahead of a stream.
         */
        internal fun baseOptions(): Map<String, String> = linkedMapOf(
            "config" to "no",
            "load-scripts" to "no",
            "vo" to "null",
            "video" to "no",
            "audio-display" to "no",
            "ytdl" to "no",
            "terminal" to "no",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "idle" to "yes",
            "keep-open" to "no",
            "gapless-audio" to "yes",
            "prefetch-playlist" to "yes",
            "cache" to "yes",
            "demuxer-readahead-secs" to "30",
            // A converted stream's first response gets no shorter limit here (Android's has one): Linux
            // converts only formats the app doesn't recognise, and FFmpeg has one timeout for everything.
            "network-timeout" to "30",
            // Normalisation is the app's own volume: no ReplayGain on top.
            "replaygain" to "no",
            "audio-client-name" to "Tetsubi",
            "user-agent" to "Tetsubi/${AppInfo.VERSION}",
        )

        private fun log(message: String) = System.err.println("Tetsubi playback: $message")
    }
}
