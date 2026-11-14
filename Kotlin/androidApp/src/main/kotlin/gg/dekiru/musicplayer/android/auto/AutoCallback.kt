package gg.dekiru.musicplayer.android.auto

import android.app.PendingIntent
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Android Auto's and Google Assistant's way in: the browse tree, search,
 * and play requests (a tapped row, or "Play <album> on Tetsubi").
 *
 * Every play request is turned into the app's own player call by
 * [AutoLibrary] (play album, play playlist, shuffle), which sets the
 * queue itself. Media3 then asks the session's player to set the items
 * this returns; it returns [PlayRequests.HANDLED], which [PlayRequests]
 * tells the player to ignore, so the app's queue stays as the app made it.
 *
 * Runs on the main thread (the player's); pages load in the background
 * and come back as futures.
 */
@UnstableApi
class AutoCallback(
    private val library: AutoLibrary,
    private val items: AutoMediaItems,
    private val scope: CoroutineScope,
    /** This app's package: only its own screens may send [PLAY_FROM_SEARCH]. */
    private val packageName: String,
    /** "Open Tetsubi", offered with "Open Tetsubi and sign in first." */
    private val openApp: PendingIntent,
) : MediaLibrarySession.Callback {

    /** Album and playlist pages the car has open, with their row counts: Maniac Mode changes their Shuffle row. */
    private val openPages = mutableMapOf<String, Int>()

    /** Recent searches' rows, so paging through results doesn't search again. */
    private val searches = LinkedHashMap<String, List<AutoRow>>()

    /** Rows handed out, so the car can ask for one again by ID. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, AutoRow>()

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val base = super.onConnect(session, controller)
        // The system's notification/lock-screen controller is also "ours": leave its result alone, or the
        // platform session loses its play/pause/skip actions (media keys and Bluetooth stop working).
        if (!base.isAccepted || controller.packageName != packageName || session.isMediaNotificationController(controller)) return base
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(base.availableSessionCommands.buildUpon().add(PLAY_FROM_SEARCH_COMMAND).build())
            // Every player command: the default leaves out play/pause for the app's own controllers (the
            // widget's buttons). What the player itself allows (Maniac Mode, casting) still applies.
            .setAvailablePlayerCommands(Player.Commands.Builder().addAllCommands().build())
            .build()
    }

    // Browsing

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // "Recent" asks for something to resume from the system's media controls; there's nothing saved to offer.
        if (params?.isRecent == true) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
        library.restore()
        val rootParams = LibraryParams.Builder().setExtras(AutoMediaItems.rootExtras()).build()
        return Futures.immediateFuture(LibraryResult.ofItem(items.root(), rootParams))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val id = AutoId.parse(parentId) ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        library.restore()
        return future {
            val rows = library.children(id)
            rows.forEach { known[it.id] = it }
            if (id is AutoId.Album || id is AutoId.Playlist) openPages[parentId] = rows.size
            LibraryResult.ofItemList(AutoTree.page(rows, page, pageSize).map(items::item), params)
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        known[mediaId]?.let { return Futures.immediateFuture(LibraryResult.ofItem(items.item(it), null)) }
        library.restore()
        return future {
            val row = when (val id = AutoId.parse(mediaId)) {
                AutoId.Root -> return@future LibraryResult.ofItem(items.root(), null)
                is AutoId.Tab -> AutoTree.tabs().firstOrNull { AutoId.parse(it.id) == id }
                is AutoId.Album -> library.album(id.discIds)?.let { AutoTree.album(it).copy(id = mediaId) }
                else -> null
            } ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItem(items.item(row), null)
        }
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        if (AutoId.parse(parentId) == null) {
            Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        } else {
            Futures.immediateFuture(LibraryResult.ofVoid())
        }

    // Search (Android Auto's search screen)

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        library.restore()
        scope.launch {
            val rows = runCatching { library.search(query) }.getOrDefault(emptyList())
            remember(query, rows)
            session.notifySearchResultChanged(browser, query, rows.size, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val rows = searches[query] ?: library.search(query).also { remember(query, it) }
        LibraryResult.ofItemList(AutoTree.page(rows, page, pageSize).map(items::item), params)
    }

    private fun remember(query: String, rows: List<AutoRow>) {
        searches.remove(query)
        searches[query] = rows
        while (searches.size > MAX_SEARCHES) searches.remove(searches.keys.first())
        rows.forEach { known[it.id] = it }
    }

    // Playing

    /** A tapped row (play from media ID) or a voice request (play from search). */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val request = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
            ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to play"))
        return Futures.transform(
            handle(mediaSession, controller, request),
            { MediaSession.MediaItemsWithStartPosition(listOf(PlayRequests.HANDLED), 0, C.TIME_UNSET) },
            MoreExecutors.directExecutor(),
        )
    }

    /** "Add to queue" from a Media3 controller: played as a tap would be (the app's queue is its own). */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val request = mediaItems.firstOrNull() ?: return Futures.immediateFuture(mutableListOf())
        return Futures.transform(
            handle(mediaSession, controller, request),
            { mutableListOf(PlayRequests.HANDLED) },
            MoreExecutors.directExecutor(),
        )
    }

    /** The app's own voice-search screen (MEDIA_PLAY_FROM_SEARCH on the phone), with the answer sent back. */
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction != PLAY_FROM_SEARCH) return super.onCustomCommand(session, controller, customCommand, args)
        library.restore()
        return future {
            val message = library.playFromSearch(voiceRequest(args.getString(EXTRA_QUERY), args))
            if (message == null) SessionResult(SessionResult.RESULT_SUCCESS) else SessionResult(error(message))
        }
    }

    /**
     * Plays `request` through the app's player. Fails (and tells the car
     * or Assistant why, in iOS's words) when nothing played.
     */
    private fun handle(session: MediaSession, controller: MediaSession.ControllerInfo, request: MediaItem): ListenableFuture<Unit> {
        library.restore()
        return future {
            val query = request.requestMetadata.searchQuery
            val id = AutoId.parse(request.mediaId)
            val message = when {
                id != null -> library.play(playable(id))
                query != null || request.mediaId.isEmpty() ->
                    library.playFromSearch(voiceRequest(query, request.requestMetadata.extras))
                else -> AutoTree.CANT_LOAD_ALBUM
            }
            if (message != null) {
                runCatching { session.sendError(controller, error(message)) }
                throw PlayRefused(message)
            }
        }
    }

    /** An album or playlist itself asked to play (Assistant, or a controller): play it from the start. */
    private fun playable(id: AutoId): AutoId = when (id) {
        is AutoId.Album -> AutoId.PlayAlbum(id.discIds, shuffled = false)
        is AutoId.Playlist -> AutoId.PlayPlaylist(id.playlistId, shuffled = false)
        else -> id
    }

    private fun voiceRequest(query: String?, extras: Bundle?): VoiceRequest = VoiceQuery.parse(
        query,
        focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS),
        album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
        artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
        playlist = extras?.getString(VoiceQuery.EXTRA_PLAYLIST),
    )

    /** The error the car or Assistant shows; "sign in first" comes with an Open Tetsubi button. */
    private fun error(message: String): SessionError =
        if (message == VoiceText.NOT_SIGNED_IN) {
            SessionError(
                SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                message,
                Bundle().apply {
                    putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, "Open Tetsubi")
                    putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, openApp)
                },
            )
        } else {
            SessionError(if (message == VoiceText.LIBRARY_EMPTY) SessionError.ERROR_IO else SessionError.ERROR_BAD_VALUE, message)
        }

    // Changes

    /** Maniac Mode switched: open album and playlist pages gain or lose Shuffle. */
    fun maniacModeChanged(session: MediaLibrarySession) {
        openPages.forEach { (id, count) -> session.notifyChildrenChanged(id, count, null) }
    }

    /** Signed in or out, downloads or the connection changed: the tabs load again. */
    fun tabsChanged(session: MediaLibrarySession, tabs: Collection<AutoTab> = AutoTab.entries) {
        tabs.forEach { session.notifyChildrenChanged(AutoId.Tab(it).format(), 1, null) }
    }

    /** Runs `block` on the main thread and hands back its result (or failure) as a future; cancelling it stops the work. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try {
                result.set(block())
            } catch (e: CancellationException) {
                result.cancel(false)
                throw e
            } catch (e: Exception) {
                result.setException(e)
            }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return result
    }

    private class PlayRefused(message: String) : Exception(message)

    companion object {
        /** Sent by the app's voice-search screen: `query` plus Assistant's extras. */
        const val PLAY_FROM_SEARCH = "gg.dekiru.musicplayer.PLAY_FROM_SEARCH"
        const val EXTRA_QUERY = "query"
        val PLAY_FROM_SEARCH_COMMAND = SessionCommand(PLAY_FROM_SEARCH, Bundle.EMPTY)

        private const val MAX_SEARCHES = 5
    }
}
