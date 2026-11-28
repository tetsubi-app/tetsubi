package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.playback.effects.AudioEffects

/** One queued item as the platform player sees it. */
data class EngineItem(
    val id: String,
    val url: String,
    val title: String,
    val artist: String,
    val album: String,
    /** Album art for the lock screen / notification (fetched with the auth header). */
    val artworkUrl: String,
    val durationMillis: Long,
    /**
     * What a Google Cast device plays: the server's stream with the token in
     * the URL (the receiver can't send headers), in a format it plays. Also
     * for downloaded tracks (the receiver can't reach this device's files).
     * Null where there's no Cast.
     */
    val castUrl: String? = null,
    /** The cover for the TV, with the token. */
    val castArtworkUrl: String? = null,
    /**
     * The queue entry it plays (`QueueEntry.id`): unique in the queue, where
     * [id] (the track's) may be there twice. Engines keep their own
     * bookkeeping (restarted streams, Cast) by this, never by position,
     * since positions shift when the queue is edited.
     */
    val entryId: String = id,
    /**
     * Where a direct stream goes if the device can't decode it after all: the
     * same track converted by the server (see [DirectPlayFallback]). Null for
     * streams that have no such way out (already converted, files, other formats).
     */
    val directFallbackUrl: String? = null,
    /** The `play plan:` log line for this item (how its stream was chosen), logged by the engine when the track starts; null = none. */
    val planNote: String? = null,
) {
    /** Converted on the fly by the server: seeking restarts the stream at the new point. */
    val seeksByRestart: Boolean get() = StreamUrls.isConverted(url)

    /** The stream for this device's player, or for a Cast device when `casting`. */
    fun url(casting: Boolean): String = if (casting) castUrl ?: url else url

    /** [seeksByRestart] for the stream that's played, here or on a Cast device. */
    fun seeksByRestart(casting: Boolean): Boolean = StreamUrls.isConverted(url(casting))
}

/** What the player tells [PlayerController]. Called on the main thread. */
interface EngineListener {
    /**
     * The playing item changed to item `index` of the engine's list (as last
     * given by [PlayerEngine.setQueue] and the replace calls). `automatic`:
     * the previous one played to its end (or repeated).
     */
    fun onIndexChanged(index: Int, automatic: Boolean)
    fun onIsPlayingChanged(isPlaying: Boolean)
    /** The last item finished (Repeat off). */
    fun onEnded()
    fun onError(message: String)

    /**
     * Whether the playing item is loading after Play, a skip, a seek or a
     * reconnect: playback is wanted but no sound has come yet (Media3
     * buffering with play-when-ready, mpv waiting for its cache or file).
     * Now Playing shows a spinner then. `reconnecting`: a stream that had
     * already played is waiting to reconnect (not a start): Play/Pause
     * pauses then, ending the tries.
     */
    fun onStartingChanged(starting: Boolean, reconnecting: Boolean = false) = Unit
}

/**
 * The platform player: Media3 ExoPlayer on Android and libmpv on Linux
 * (`MpvEngine`), both gapless through their own playlist. It holds the whole queue; skips, repeat
 * and moving to the next item happen inside it, and the lock screen and
 * headset buttons reach it directly, so it reports changes back through
 * [listener]. Main thread only.
 */
interface PlayerEngine {
    /** False where there's no player (Linux without libmpv). */
    val isSupported: Boolean

    /** Why there's no player and what to do, for Home to show; null when it plays (or when there's nothing to say). */
    val unavailableReason: String? get() = null

    /** Formats it plays as they are; others are transcoded by the server. */
    val directPlayFormats: Set<AudioFormat>

    /**
     * Formats a file already on disk may be in and stay valid (others are deleted on start and downloaded
     * again as FLAC). Normally [directPlayFormats]. Android's is the full set with ALAC whatever the
     * per-build ALAC failure flag says, so that flag never deletes finished downloads; new downloads
     * follow [directPlayFormats] (see `DownloadManager`).
     */
    val storedPlayFormats: Set<AudioFormat> get() = directPlayFormats

    /** Device facts for the `play plan:` line, e.g. `alacDecoder=true alacFailed=false`; empty where there are none. */
    val planNote: String get() = ""

    var listener: EngineListener?

    /** The `Authorization` header sent with streams and artwork. */
    fun setAuthorization(header: String?)

    fun setQueue(items: List<EngineItem>, startIndex: Int)

    /**
     * Holds `items`, paused at `startMillis` into item `startIndex`, without
     * opening any stream (a restored queue at launch, or a paused queue
     * loaded again). The lock screen and notification show the item; the
     * first [play] (a skip then Play included) opens it there: a converted
     * stream starts at that point (`StartTimeTicks`), a direct stream or a
     * file seeks to it. Nothing is reported to the listener.
     *
     * The default (engines that can't wait) loads it paused.
     */
    fun cue(items: List<EngineItem>, startIndex: Int, startMillis: Long) {
        pause()
        setQueue(items, startIndex)
        if (startMillis > 0) seekTo(startMillis)
    }

    /**
     * Replaces the items after the playing one without interrupting it.
     * Items that already follow it in the same order (by [EngineItem.entryId])
     * are kept as they are, so the next one stays loaded for a gapless join.
     */
    fun replaceAfterCurrent(items: List<EngineItem>)

    /**
     * Replaces the items before and after the playing one without
     * interrupting it (the queue was shuffled, unshuffled or edited): the
     * playing item ends up at index `before.size`. Following items kept as
     * in [replaceAfterCurrent].
     */
    fun replaceAroundCurrent(before: List<EngineItem>, after: List<EngineItem>)

    fun play()
    fun pause()
    fun seekTo(millis: Long)

    /** Plays item `index` from its start. */
    fun skipTo(index: Int)
    fun setRepeatMode(mode: RepeatMode)

    /** 0…1, for normalisation. */
    fun setVolume(volume: Float)

    /** Whether the lock screen, notification and headset may skip and seek (off in Maniac Mode). */
    fun setSkipControlsEnabled(enabled: Boolean)
    fun stop()

    val positionMillis: Long

    /** The playing item's length, once known (0 before). */
    val durationMillis: Long

    /** The equaliser and visualiser, where this player has them (Android); null hides them. */
    val audioEffects: AudioEffects? get() = null

    /** Google Cast, where this player has it (Android with Play services); null hides the Cast button. */
    val cast: CastRoute? get() = null

    /**
     * Better Cast streams for queued items, by track ID, found after the
     * queue was loaded (a downloaded album's server formats). Items that
     * aren't queued are ignored, and so is the whole call while casting.
     */
    fun updateCastUrls(urls: Map<String, String>) = Unit
}

/** No player on this platform yet: Play stays off. */
class UnsupportedPlayerEngine : PlayerEngine {
    override val isSupported = false
    override val directPlayFormats = emptySet<AudioFormat>()
    override var listener: EngineListener? = null
    override fun setAuthorization(header: String?) = Unit
    override fun setQueue(items: List<EngineItem>, startIndex: Int) = Unit
    override fun replaceAfterCurrent(items: List<EngineItem>) = Unit
    override fun replaceAroundCurrent(before: List<EngineItem>, after: List<EngineItem>) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(millis: Long) = Unit
    override fun skipTo(index: Int) = Unit
    override fun setRepeatMode(mode: RepeatMode) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun setSkipControlsEnabled(enabled: Boolean) = Unit
    override fun stop() = Unit
    override val positionMillis = 0L
    override val durationMillis = 0L
}
