package gg.dekiru.musicplayer.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.android.gms.cast.MediaStatus
import gg.dekiru.musicplayer.AppInfo
import gg.dekiru.musicplayer.data.network.NetworkState
import gg.dekiru.musicplayer.playback.effects.AudioEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android's player: one ExoPlayer for the app, holding the whole queue as a
 * playlist (gapless; it loads the next track ahead). Streams send the
 * `Authorization` header. Audio focus (pause for calls, duck for
 * navigation), pausing when headphones are unplugged and a Wi-Fi wake lock
 * for screen-off streaming are ExoPlayer's own. `PlaybackService` puts it
 * in a MediaSession for the notification, lock screen and Bluetooth buttons.
 *
 * Google Cast: when a Cast session starts, the queue, the track, the point
 * in it, play/pause and repeat move to the Cast device's player
 * (media3-cast's `RemoteCastPlayer`) and ExoPlayer stops; when it ends,
 * they come back ([CastHandover]). Everything here then works on the
 * [activePlayer], whose events reach [PlayerController] as ExoPlayer's do,
 * and the session's player follows it. Main thread only, as Media3 requires.
 *
 * The queue is the controller's play order (several albums, shuffled or
 * not). ExoPlayer holds all of it, item for item, so its indices are the
 * queue's; a Cast device holds a window of it ([CastWindow], [castItems]),
 * so its indices are mapped through the items' queue entries. Everything
 * kept per item (a restarted stream's start, the item the Cast device
 * ended on) is kept by queue entry, as edits move items about.
 */
@UnstableApi
class Media3Engine(private val context: Context) : PlayerEngine {

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent("Tetsubi/${AppInfo.VERSION}")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)

    /**
     * The track-start diagnostic line ([StreamStartLog]). Its handler, the
     * waits and the stream events below are declared before [player], which
     * uses them.
     */
    private val startLog = StreamStartLog({ SystemClock.elapsedRealtime() })
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startCheckTask = Runnable { checkStart() }

    /**
     * Each stream's connection opening (the HTTP response, or a downloaded
     * file opening), on the loading thread: handed to [startLog] on the main
     * thread with the time it happened. Nothing per buffer.
     */
    private val streamOpened = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            val at = SystemClock.elapsedRealtime()
            val uri = dataSpec.uri.toString()
            mainHandler.post { startLog.responded(uri, at) }
        }
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    /** The equaliser and visualiser, worked by [TetsubiAudioProcessor] in the audio output. */
    override val audioEffects = AudioEffects()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(effectsRenderersFactory(context, audioEffects))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http).setTransferListener(streamOpened))
                .setLoadErrorHandlingPolicy(ConvertedStreamErrorPolicy()),
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    /** The `Authorization` header, also used to load lock-screen artwork. */
    var authorization: String? = null
        private set

    private val _skipEnabled = MutableStateFlow(true)

    /** False in Maniac Mode: the session hides skip and seek. */
    val skipEnabled: StateFlow<Boolean> = _skipEnabled.asStateFlow()

    override val isSupported = true

    /** ALAC isn't decoded on every device, so the server sends it as FLAC. */
    override val directPlayFormats = setOf(AudioFormat.FLAC, AudioFormat.MP3, AudioFormat.AAC, AudioFormat.OPUS, AudioFormat.WAV)

    override var listener: EngineListener? = null

    /** The queue as given (ExoPlayer's playlist, item for item): each item's own stream, from 0:00. */
    private val queueItems = mutableListOf<EngineItem>()

    /** Per queue entry: where a restarted converted stream begins, in ms (absent: 0). */
    private val startOffsets = mutableMapOf<String, Long>()

    /** What the Cast device holds, in its order: the whole queue or a window of it ([CastWindow]). */
    private val castItems = mutableListOf<EngineItem>()

    /** True while swapping in a restarted stream: that's not a change of track. */
    private var restarting = false

    // Reconnecting a converted stream (see StreamReconnect)

    /** Tries to get a converted stream back after its connection dropped. */
    private val reconnect = ReconnectState { SystemClock.elapsedRealtime() }
    private val retryTask = Runnable { retryStream() }

    /**
     * Keeps the CPU awake between tries with the screen off: ExoPlayer lets
     * its own wake lock go once the stream has failed. Times out by itself.
     */
    private val reconnectWakeLock: PowerManager.WakeLock? = runCatching {
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tetsubi:reconnect")
            .apply { setReferenceCounted(false) }
    }.getOrNull()

    private var repeat = RepeatMode.OFF
    private var volume = 1f

    // Google Cast

    /** Google Cast, where Play services is installed; its player is made on the main thread. */
    private val castConnection: CastConnection? = if (CastConnection.isAvailable(context)) CastConnection(context) else null

    override val cast: CastRoute? get() = castConnection

    private val remote: Player? get() = castConnection?.player

    private val _activePlayer = MutableStateFlow<Player>(player)

    /** The player playing: ExoPlayer, or the Cast device's while casting. */
    val activePlayer: StateFlow<Player> = _activePlayer.asStateFlow()

    private val active: Player get() = _activePlayer.value
    private val casting: Boolean get() = active !== player

    /**
     * The Cast device played the queue to its end: it holds nothing to play
     * until Play loads it again from this queue entry (the track Play starts
     * from). Null otherwise.
     */
    private var castEndedEntry: String? = null
    private val castEnded: Boolean get() = castEndedEntry != null

    /** The items `p` holds: ExoPlayer the whole queue, the Cast device its window. */
    private fun itemsOn(p: Player): List<EngineItem> = if (p === player) queueItems else castItems

    private fun itemAt(p: Player, index: Int): EngineItem? = itemsOn(p).getOrNull(index)

    /** The queue index of an entry; -1 when it isn't queued. */
    private fun queueIndexOf(entryId: String?): Int = if (entryId == null) -1 else queueItems.indexOfFirst { it.entryId == entryId }

    private fun offsetOf(item: EngineItem?): Long = item?.let { startOffsets[it.entryId] } ?: 0L

    /** The entry playing (or, on a Cast device that finished, the one Play starts from). */
    private fun currentEntryId(): String? = when {
        casting && castEnded -> castEndedEntry
        else -> itemAt(active, active.currentMediaItemIndex)?.entryId
    }

    /** The Cast device's repeat mode: its own Repeat All would only go round a window, so windows go round by [refreshCastWindow]. */
    private fun castRepeat(): Int =
        if (repeat == RepeatMode.ALL && CastWindow.isPartial(queueItems.size)) Player.REPEAT_MODE_OFF else repeat.media3

    /** Each player with converted streams made seekable (see [Seekable]). */
    private val seekables = mutableMapOf<Player, Player>()

    /** The active player with converted streams made seekable. */
    val seekable: Player get() = seekables.getOrPut(active) { Seekable(active) }

    /**
     * A player with converted streams made seekable. A stream the server
     * converts on the fly (`/universal`, e.g. ALAC, which Android can't
     * decode) can't be seeked, so a seek in one swaps in the same stream
     * started at that point (`StartTimeTicks`), and this adds the starting
     * point back on: position, duration and the seek commands look normal to
     * the app, the notification and the lock screen. Leaving the track puts
     * its own stream from 0:00 back. The same on a Cast device, whose
     * receiver can't seek such a stream either.
     */
    private inner class Seekable(private val wrapped: Player) : ForwardingPlayer(wrapped) {
        private val onCast get() = wrapped !== player
        private fun item() = itemAt(wrapped, currentMediaItemIndex)
        private fun offset() = offsetOf(item())
        private fun converted() = item()?.takeIf { it.seeksByRestart(onCast) && it.durationMillis > 0 }

        override fun getCurrentPosition() = super.getCurrentPosition() + offset()
        override fun getContentPosition() = super.getContentPosition() + offset()
        override fun getBufferedPosition() = super.getBufferedPosition() + offset()
        override fun getContentBufferedPosition() = super.getContentBufferedPosition() + offset()
        override fun getDuration() = converted()?.durationMillis ?: super.getDuration()
        override fun getContentDuration() = converted()?.durationMillis ?: super.getContentDuration()
        override fun isCurrentMediaItemSeekable() = converted() != null || super.isCurrentMediaItemSeekable()

        // ExoPlayer counts a stream of unknown length that can't be seeked as
        // live, and Media3 then hides the position from the notification and
        // lock screen. A converted track has a known length: it isn't live.
        override fun isCurrentMediaItemLive() = converted() == null && super.isCurrentMediaItemLive()
        override fun isCurrentMediaItemDynamic() = converted() == null && super.isCurrentMediaItemDynamic()

        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            if (converted() == null) return base
            return base.buildUpon().addAll(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            ).build()
        }

        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)

        override fun seekTo(positionMs: Long) = seekWithin(currentMediaItemIndex, positionMs)
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) = seekWithin(mediaItemIndex, positionMs)
        override fun seekToDefaultPosition() = seekWithin(currentMediaItemIndex, 0)
        override fun seekToDefaultPosition(mediaItemIndex: Int) = seekWithin(mediaItemIndex, 0)
        override fun seekBack() = seekWithin(currentMediaItemIndex, (currentPosition - seekBackIncrement).coerceAtLeast(0))
        override fun seekForward() = seekWithin(currentMediaItemIndex, currentPosition + seekForwardIncrement)

        /** Back to the start after 3 s (or on the first track), else the previous track. */
        override fun seekToPrevious() {
            if (currentPosition > 3_000 || !hasPreviousMediaItem()) seekWithin(currentMediaItemIndex, 0)
            else seekWithin(previousMediaItemIndex, 0)
        }

        override fun seekToPreviousMediaItem() {
            if (hasPreviousMediaItem()) seekWithin(previousMediaItemIndex, 0)
        }

        override fun seekToNext() = seekToNextMediaItem()

        override fun seekToNextMediaItem() {
            if (hasNextMediaItem()) seekWithin(nextMediaItemIndex, 0)
        }
    }

    /**
     * Seeks on the active player (`index`: in that player's list), restarting
     * a converted stream at the new point. While a dropped stream waits for
     * its next try, the try comes now.
     */
    private fun seekWithin(index: Int, positionMs: Long) {
        val from = active.currentMediaItemIndex
        moveWithin(index, positionMs)
        if (reconnect.active) retryStream()
        if (!casting) beginStart(if (index == from) StreamStartLog.Reason.SEEK else StreamStartLog.Reason.SKIP)
    }

    private fun moveWithin(index: Int, positionMs: Long) {
        val p = active
        if (casting && castEnded) {
            castEndedSkip(castItems.getOrNull(index)?.entryId)
            return
        }
        val item = itemAt(p, index)
        if (item == null || !item.seeksByRestart(casting)) {
            p.seekTo(index, positionMs)
            return
        }
        val target = positionMs.coerceIn(0, (item.durationMillis - 1_000).coerceAtLeast(0))
        if (target == 0L && offsetOf(item) == 0L) {
            // Its own stream, from the start: an ordinary move.
            p.seekTo(index, 0)
            return
        }
        if (casting) {
            // The receiver's queue is loaded again, this item's stream started at the point:
            // one request, where swapping one item would be two that race.
            val queueIndex = queueIndexOf(item.entryId)
            if (queueIndex < 0) return
            val moved = p.currentMediaItemIndex != index
            loadOnCast(PlaybackSnapshot(queueIndex, target, p.playWhenReady, repeat))
            // A new load isn't reported as a change of track: say so here.
            if (moved) listener?.onIndexChanged(queueIndex, automatic = false)
            return
        }
        restarting = true
        try {
            if (target == 0L) startOffsets.remove(item.entryId) else startOffsets[item.entryId] = target
            player.replaceMediaItem(index, mediaItem(item, StreamUrls.startingAt(item.url, target), onCast = false))
            if (player.currentMediaItemIndex != index) player.seekTo(index, 0)
        } finally {
            restarting = false
        }
    }

    /** Puts every other track's own stream back, so Previous or Repeat start it from 0:00. */
    private fun restoreOtherTracks() {
        val p = active
        val items = itemsOn(p)
        val current = items.getOrNull(p.currentMediaItemIndex)?.entryId
        for (entry in startOffsets.keys.filter { it != current }) {
            startOffsets.remove(entry)
            val index = items.indexOfFirst { it.entryId == entry }
            if (index >= 0) p.replaceMediaItem(index, mediaItem(items[index], items[index].url(casting), casting))
        }
    }

    /** Passes one player's events on to [listener] while it's the active one. */
    private inner class Events(private val source: Player) : Player.Listener {
        private val isActive get() = source === active

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A new queue (the controller knows where it starts) or a restarted stream.
            if (!isActive || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED || restarting) return
            restoreOtherTracks()
            val automatic = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            // Skips and seeks are logged where they're asked for (seekWithin).
            if (automatic && source === player) beginStart(StreamStartLog.Reason.NEXT, alreadyPlaying = player.isPlaying)
            val entry = itemAt(source, source.currentMediaItemIndex)?.entryId
            // The Cast device's window moves along with it.
            if (source !== player) refreshCastWindow()
            val index = queueIndexOf(entry)
            if (index >= 0) listener?.onIndexChanged(index, automatic)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isActive) return
            // Getting a dropped stream back: still playing as far as the app knows.
            if (!isPlaying && source === player && reconnect.active && player.playWhenReady) return
            listener?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Paused while reconnecting: the player wasn't playing, so it doesn't say it stopped.
            if (isActive && source === player && reconnect.active && !playWhenReady) listener?.onIsPlayingChanged(false)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!isActive) return
            if (source === player) {
                if (playbackState == Player.STATE_READY && reconnect.active) endReconnect()
                if (playbackState == Player.STATE_ENDED) listener?.onEnded()
            } else if (playbackState == Player.STATE_IDLE) {
                castIdle(source)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!isActive) return
            if (source === player && reconnectAfter(error)) return
            if (source === player) startLog.failed(error.errorCodeName)
            listener?.onError("Playback failed (${error.errorCodeName}).")
        }
    }

    /**
     * Converted streams get no retries from ExoPlayer: it would ask for the
     * stream again from its start (it has no length and can't be seeked),
     * and the track would jump back to 0:00 with no error. The error comes
     * through instead, once the audio already buffered has played, and
     * [reconnectAfter] starts the stream again where playback had got to.
     * Everything else: Media3's own retries (a direct stream resumes where it
     * stopped).
     */
    private class ConvertedStreamErrorPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (StreamUrls.isConverted(loadErrorInfo.loadEventInfo.dataSpec.uri.toString())) C.TIME_UNSET
            else super.getRetryDelayMsFor(loadErrorInfo)
    }

    /**
     * ExoPlayer failed: if a converted stream lost its connection, holds the
     * point reached and tries again (later if need be) instead of reporting
     * it. Returns false to report the error: not a dropped converted stream,
     * or the tries ran out (the point is still held, so Play resumes there).
     */
    private fun reconnectAfter(error: PlaybackException): Boolean {
        if (casting) return false
        val current = player.currentMediaItemIndex
        val failed = failedIndex(error) ?: current
        if (!StreamReconnect.shouldReconnect(error.errorCode, queueItems.getOrNull(failed), casting)) return false
        holdPosition(current)
        val wait = reconnect.failed()
        if (wait == null) {
            endReconnect()
            return false
        }
        reconnectWakeLock?.runCatching { acquire(StreamReconnect.GIVE_UP_AFTER_MILLIS + 30_000) }
        mainHandler.removeCallbacks(retryTask)
        mainHandler.postDelayed(retryTask, wait)
        return true
    }

    /** The queue index of the item whose stream failed, where ExoPlayer says. */
    private fun failedIndex(error: PlaybackException): Int? {
        val uid = (error as? ExoPlaybackException)?.mediaPeriodId?.periodUid ?: return null
        val timeline = player.currentTimeline
        val period = timeline.getIndexOfPeriod(uid).takeIf { it != C.INDEX_UNSET } ?: return null
        return timeline.getPeriod(period, Timeline.Period()).windowIndex
    }

    /**
     * Puts in the playing converted track's stream started where it had got
     * to, so preparing the player again (a try, or Play) carries on from
     * there; position and length still read as the whole track's
     * ([Seekable]). A direct stream needs nothing: ExoPlayer keeps its point.
     */
    private fun holdPosition(index: Int) {
        val item = queueItems.getOrNull(index)?.takeIf { it.seeksByRestart(casting = false) } ?: return
        val offset = offsetOf(item)
        val position = StreamReconnect.resumePosition(offset, player.currentPosition, item.durationMillis)
        if (position == offset) return // A try that failed before playing: it already starts there.
        restarting = true
        try {
            if (position == 0L) startOffsets.remove(item.entryId) else startOffsets[item.entryId] = position
            player.replaceMediaItem(index, mediaItem(item, StreamUrls.startingAt(item.url, position), onCast = false))
            player.seekTo(index, 0)
        } finally {
            restarting = false
        }
    }

    /** A try: prepares the failed player again (it carries on from the held point). */
    private fun retryStream() {
        mainHandler.removeCallbacks(retryTask)
        if (!reconnect.active || casting) return
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.prepare()
            beginStart(StreamStartLog.Reason.RECONNECT)
        }
    }

    /** Playing again, given up, or overtaken (a new queue, stop, Cast): no more tries. */
    private fun endReconnect() {
        reconnect.reset()
        mainHandler.removeCallbacks(retryTask)
        reconnectWakeLock?.runCatching { if (isHeld) release() }
    }

    /**
     * Tries at once when the network changes or comes back while a stream is
     * waiting for its next try. `network`: the app's network state.
     */
    fun watchNetwork(network: StateFlow<NetworkState>) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            network.collect { state -> if (state.online && reconnect.active) retryStream() }
        }
    }

    /**
     * The Cast device went idle. Its player never says "ended" or reports
     * errors, so the receiver's own reason tells: the queue played out, or
     * the stream failed.
     */
    private fun castIdle(cast: Player) {
        when (castConnection?.idleReason()) {
            MediaStatus.IDLE_REASON_FINISHED -> if (!castEnded && !cast.hasNextMediaItem() && queueItems.isNotEmpty()) {
                val entry = castItems.getOrNull(cast.currentMediaItemIndex)?.entryId
                val index = queueIndexOf(entry)
                if (index >= 0 && index < queueItems.lastIndex) {
                    // A window that ran out before the queue did (it's normally moved along first): the rest follows.
                    loadOnCast(PlaybackSnapshot(index + 1, 0, playWhenReady = true, repeat))
                    listener?.onIndexChanged(index + 1, automatic = true)
                    return
                }
                castEndedEntry = entry?.takeIf { index >= 0 } ?: queueItems.first().entryId
                listener?.onEnded()
            }
            MediaStatus.IDLE_REASON_ERROR -> listener?.onError("Playback failed on the Cast device.")
        }
    }

    /** A move while the Cast device holds nothing to play: remembered for Play. */
    private fun castEndedSkip(entryId: String?) {
        val index = queueIndexOf(entryId)
        if (index < 0 || entryId == castEndedEntry) return
        castEndedEntry = entryId
        listener?.onIndexChanged(index, automatic = false)
    }

    /**
     * Keeps the Cast device's list the window around its playing item: the
     * items after it as the queue has them (those already there in the same
     * order are left alone, so the next stays loaded), the ones before
     * trimmed (or replaced when the queue changed there), and its repeat
     * mode. Called as it moves on and after the queue changes.
     */
    private fun refreshCastWindow() {
        val cast = remote ?: return
        if (!casting || castEnded) return
        val cur = cast.currentMediaItemIndex
        val currentItem = castItems.getOrNull(cur) ?: return
        val queueIndex = queueIndexOf(currentItem.entryId)
        if (queueIndex < 0) return
        val window = CastWindow.indices(queueItems.size, queueIndex, repeat).map { queueItems[it] }
        val at = window.indexOfFirst { it.entryId == currentItem.entryId }
        val wantedBefore = window.take(at)
        val wantedAfter = window.drop(at + 1)
        replaceFollowing(cast, cur, castItems.drop(cur + 1), wantedAfter, onCast = true)
        val oldBefore = castItems.take(cur)
        var before = oldBefore
        if (oldBefore.map { it.entryId }.takeLast(wantedBefore.size) == wantedBefore.map { it.entryId }) {
            // Only played items to drop: a few at a time.
            val extra = oldBefore.size - wantedBefore.size
            if (extra > CastWindow.BEHIND) {
                cast.removeMediaItems(0, extra)
                before = oldBefore.drop(extra)
            }
        } else {
            if (cur > 0) cast.removeMediaItems(0, cur)
            if (wantedBefore.isNotEmpty()) cast.addMediaItems(0, wantedBefore.map { mediaItem(it, it.url(true), onCast = true) })
            before = wantedBefore
        }
        castItems.clear()
        castItems += before + currentItem + wantedAfter
        cast.repeatMode = castRepeat()
    }

    /**
     * Makes the items after `p`'s item `current` the `wanted` ones: those
     * that already follow in the same order stay (the next keeps what it has
     * loaded, for a gapless join), the rest is removed and added.
     */
    private fun replaceFollowing(p: Player, current: Int, existing: List<EngineItem>, wanted: List<EngineItem>, onCast: Boolean) {
        val keep = CastWindow.sharedStart(existing, wanted)
        if (keep < existing.size) p.removeMediaItems(current + 1 + keep, p.mediaItemCount)
        if (keep < wanted.size) p.addMediaItems(wanted.drop(keep).map { mediaItem(it, it.url(onCast), onCast) })
    }

    init {
        player.addListener(Events(player))
        player.addAnalyticsListener(StartEvents())
        if (castConnection != null) {
            // The Cast framework must start on the main thread.
            if (Looper.myLooper() == Looper.getMainLooper()) startCast() else Handler(Looper.getMainLooper()).post(::startCast)
        }
    }

    private fun startCast() {
        val connection = castConnection ?: return
        connection.start(::castSessionChanged)
        connection.player?.addListener(Events(connection.player!!))
    }

    /** A Cast session started (`available`) or ended: playback moves over. */
    private fun castSessionChanged(available: Boolean) {
        val cast = remote ?: return
        val target = if (available) cast else player
        val source = active
        if (source === target) return
        val snapshot = snapshotOf(source)
        val hadQueue = queueItems.isNotEmpty()
        // From here on the old player's events are ignored.
        _activePlayer.value = target
        endReconnect()
        endStart()
        if (source === player) player.stop()
        startOffsets.clear()
        castEndedEntry = null
        castItems.clear()
        if (!hadQueue) return
        if (target === cast) loadOnCast(snapshot) else loadHere(snapshot)
    }

    /** Where `source` is: its track, the point in it (with a restarted stream's start), play/pause. */
    /** Where `source` is (a queue index): its track, the point in it (with a restarted stream's start), play/pause. */
    private fun snapshotOf(source: Player): PlaybackSnapshot {
        if (source !== player && castEnded) {
            return PlaybackSnapshot(queueIndexOf(castEndedEntry).coerceAtLeast(0), 0, playWhenReady = false, repeat)
        }
        val item = itemAt(source, source.currentMediaItemIndex)
        val index = queueIndexOf(item?.entryId).coerceAtLeast(0)
        val ended = source.playbackState == Player.STATE_ENDED
        val position = if (ended) 0 else source.currentPosition + offsetOf(item)
        return PlaybackSnapshot(index, position, playWhenReady = source.playWhenReady && !ended, repeat)
    }

    /** Loads the queue (the window around `at`, a queue index) on the Cast device from `at`. */
    private fun loadOnCast(at: PlaybackSnapshot) {
        val cast = remote ?: return
        if (queueItems.isEmpty()) return
        val index = at.index.coerceIn(0, queueItems.lastIndex)
        val window = CastWindow.indices(queueItems.size, index, repeat)
        val items = window.map { queueItems[it] }
        val plan = CastHandover.plan(items, at.copy(index = window.indexOf(index)), toCast = true) ?: return
        startOffsets.clear()
        if (plan.startOffsetMillis > 0) startOffsets[items[plan.startIndex].entryId] = plan.startOffsetMillis
        castEndedEntry = null
        castItems.clear()
        castItems += items
        // Both are sent with the queue (autoplay and the queue's repeat mode).
        cast.repeatMode = castRepeat()
        if (cast.playWhenReady != plan.playWhenReady) cast.playWhenReady = plan.playWhenReady
        cast.setMediaItems(items.mapIndexed { i, item -> mediaItem(item, plan.urls[i], onCast = true) }, plan.startIndex, plan.startPositionMillis)
        cast.volume = volume
    }

    /** Loads the queue on this device's ExoPlayer from `at`. */
    private fun loadHere(at: PlaybackSnapshot) {
        val plan = CastHandover.plan(queueItems, at, toCast = false) ?: return
        startOffsets.clear()
        if (plan.startOffsetMillis > 0) startOffsets[queueItems[plan.startIndex].entryId] = plan.startOffsetMillis
        player.repeatMode = plan.repeat.media3
        player.volume = volume
        player.setMediaItems(queueItems.mapIndexed { i, item -> mediaItem(item, plan.urls[i], onCast = false) }, plan.startIndex, plan.startPositionMillis)
        player.prepare()
        player.playWhenReady = plan.playWhenReady
        beginStart(StreamStartLog.Reason.PLAY)
    }

    override fun setAuthorization(header: String?) {
        authorization = header
        http.setDefaultRequestProperties(header?.let { mapOf("Authorization" to it) }.orEmpty())
    }

    override fun setQueue(items: List<EngineItem>, startIndex: Int) {
        endReconnect()
        queueItems.clear()
        queueItems += items
        startOffsets.clear()
        if (casting) {
            // The controller plays a new queue straight away; the receiver needs to know as it loads.
            endStart()
            loadOnCast(PlaybackSnapshot(startIndex, 0, playWhenReady = true, repeat))
            return
        }
        player.setMediaItems(items.map { mediaItem(it, it.url, onCast = false) }, startIndex, 0)
        player.prepare()
        beginStart(StreamStartLog.Reason.PLAY)
    }

    override fun replaceAfterCurrent(items: List<EngineItem>) {
        val current = queueIndexOf(currentEntryId())
        if (current < 0) return
        replaceAroundCurrent(queueItems.take(current), items)
    }

    override fun replaceAroundCurrent(before: List<EngineItem>, after: List<EngineItem>) {
        val current = queueIndexOf(currentEntryId())
        if (current < 0) return
        val currentItem = queueItems[current]
        if (!casting) {
            // ExoPlayer holds the queue item for item: the playing one stays, everything round it changes.
            if (queueItems.take(current).map { it.entryId } != before.map { it.entryId }) {
                if (current > 0) player.removeMediaItems(0, current)
                if (before.isNotEmpty()) player.addMediaItems(0, before.map { mediaItem(it, it.url, onCast = false) })
            }
            replaceFollowing(player, before.size, queueItems.drop(current + 1), after, onCast = false)
        }
        queueItems.clear()
        queueItems += before + currentItem + after
        val queued = queueItems.mapTo(HashSet()) { it.entryId }
        startOffsets.keys.retainAll(queued)
        if (castEndedEntry != null && castEndedEntry !in queued) castEndedEntry = currentItem.entryId
        // While casting ExoPlayer holds nothing that plays: it's loaded from the queue when casting ends.
        if (casting) refreshCastWindow()
    }

    override fun updateCastUrls(urls: Map<String, String>) {
        // While casting the receiver has the queue already; it keeps the streams it was given.
        if (casting) return
        for (i in queueItems.indices) {
            val item = queueItems[i]
            urls[item.id]?.let { queueItems[i] = item.copy(castUrl = it) }
        }
    }

    override fun play() {
        if (casting) {
            if (castEnded) loadOnCast(PlaybackSnapshot(queueIndexOf(castEndedEntry).coerceAtLeast(0), 0, playWhenReady = true, repeat)) else active.play()
            return
        }
        if (player.playbackState == Player.STATE_IDLE) {
            // After an error: loads the item again.
            player.prepare()
            beginStart(StreamStartLog.Reason.RESUME)
        }
        // Ended: Play starts the queue again.
        if (player.playbackState == Player.STATE_ENDED) seekable.seekTo(0, 0)
        player.play()
    }

    override fun pause() = active.pause()
    override fun seekTo(millis: Long) = seekable.seekTo(millis)
    /** `index`: a queue index (on a Cast device, mapped to its window; outside it, the window moves there). */
    override fun skipTo(index: Int) {
        val item = queueItems.getOrNull(index) ?: return
        if (!casting) {
            seekable.seekTo(index, 0)
            return
        }
        if (castEnded) {
            castEndedSkip(item.entryId)
            return
        }
        val onCast = castItems.indexOfFirst { it.entryId == item.entryId }
        if (onCast >= 0) {
            seekable.seekTo(onCast, 0)
        } else {
            loadOnCast(PlaybackSnapshot(index, 0, active.playWhenReady, repeat))
            listener?.onIndexChanged(index, automatic = false)
        }
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeat = mode
        if (casting && castEnded) return
        if (casting) refreshCastWindow() else player.repeatMode = mode.media3
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        active.volume = volume
    }

    override fun setSkipControlsEnabled(enabled: Boolean) {
        _skipEnabled.value = enabled
    }

    override fun stop() {
        endReconnect()
        endStart()
        player.stop()
        player.clearMediaItems()
        if (casting) {
            active.stop()
            active.clearMediaItems()
        }
        queueItems.clear()
        castItems.clear()
        startOffsets.clear()
        castEndedEntry = null
    }

    override val positionMillis: Long get() = if (casting && castEnded) 0 else seekable.currentPosition
    override val durationMillis: Long
        get() = if (casting && castEnded) 0 else seekable.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0

    // The track-start diagnostic line (StreamStartLog)

    /** ExoPlayer was asked for the current item's audio afresh: starts timing it. */
    private fun beginStart(reason: StreamStartLog.Reason, alreadyPlaying: Boolean = false) {
        val item = queueItems.getOrNull(player.currentMediaItemIndex) ?: return
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        startLog.begin(item.id, StreamStartLog.kindOf(uri), reason, uri, alreadyPlaying)
        scheduleStartCheck()
    }

    private fun endStart() {
        startLog.cancel()
        mainHandler.removeCallbacks(startCheckTask)
    }

    private fun scheduleStartCheck() {
        mainHandler.removeCallbacks(startCheckTask)
        startLog.millisUntilCheck()?.let { mainHandler.postDelayed(startCheckTask, it) }
    }

    private fun checkStart() {
        val state = when (player.playbackState) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            else -> "ended"
        }
        startLog.check(paused = !player.playWhenReady, state = state)
        scheduleStartCheck()
    }

    private val eventWindow = Timeline.Window()

    /** The stream of the item an ExoPlayer event is about. */
    private fun sourceOf(eventTime: AnalyticsListener.EventTime): String? {
        val timeline = eventTime.timeline
        if (eventTime.windowIndex !in 0 until timeline.windowCount) return null
        return timeline.getWindow(eventTime.windowIndex, eventWindow).mediaItem.localConfiguration?.uri?.toString()
    }

    /**
     * ExoPlayer's milestones for [startLog]: the first audio of an item
     * reaching the decoder (its format read, per item, also for the next
     * item loaded ahead), playing, and the audio output starting to move
     * (once after each load, seek or pause). None of these come per buffer.
     */
    private inner class StartEvents : AnalyticsListener {
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            sourceOf(eventTime)?.let { startLog.firstAudio(it) }
        }

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
            if (!startLog.pending) return
            if (isPlaying) startLog.playing() else startLog.stoppedPlaying()
            scheduleStartCheck()
        }

        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            startLog.soundStarted()
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            if (state == Player.STATE_READY && !player.playWhenReady) startLog.readyWhilePaused()
        }
    }

    /**
     * `url`: the item's stream, or a restarted converted stream. On a Cast
     * device each item also says its MIME type and that it's music, and the
     * TV's cover carries the token.
     */
    private fun mediaItem(item: EngineItem, url: String, onCast: Boolean) = MediaItem.Builder()
        // The queue entry: unique, where a track may be queued twice (the session's queue, Android Auto's).
        .setMediaId(item.entryId)
        .setUri(url)
        .apply { if (onCast) setMimeType(StreamUrls.mimeType(url)) }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artist)
                .setAlbumTitle(item.album)
                .setAlbumArtist(item.artist)
                .setArtworkUri(Uri.parse(if (onCast) item.castArtworkUrl ?: item.artworkUrl else item.artworkUrl))
                .setDurationMs(item.durationMillis.takeIf { it > 0 })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .apply { if (onCast) setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC) }
                .build(),
        )
        .build()

    /**
     * The player the MediaSession shows: the active one (so the notification,
     * lock screen and volume keys reach the Cast device while casting),
     * seekable, and without skip and seek in Maniac Mode.
     */
    fun sessionPlayer(): Player = object : ForwardingPlayer(seekable) {
        private val blocked = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
        )
        private val allowSkips = _skipEnabled.value

        override fun getAvailableCommands(): Player.Commands =
            if (allowSkips) super.getAvailableCommands() else super.getAvailableCommands().buildUpon().removeAll(*blocked).build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    }
}

private val RepeatMode.media3: Int
    get() = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

/** Mobile data or a hotspot (the system marks them metered): streams are capped. */
fun isMeteredNetwork(context: Context): Boolean =
    runCatching { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(false)
