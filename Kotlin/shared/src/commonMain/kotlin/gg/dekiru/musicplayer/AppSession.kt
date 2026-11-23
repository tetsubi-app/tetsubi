package gg.dekiru.musicplayer

import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.artist.ArtistInfoCache
import gg.dekiru.musicplayer.data.artist.ArtistInfoClient
import gg.dekiru.musicplayer.data.auth.Authenticator
import gg.dekiru.musicplayer.data.settings.AppEnvironment
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.update.UpdateChecker
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadSettings
import gg.dekiru.musicplayer.playback.PlayQueueSaver
import gg.dekiru.musicplayer.playback.PlayQueueStore
import gg.dekiru.musicplayer.playback.PlaybackSettings
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.playback.effects.EffectsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * App-wide owner of the signed-in state (as `AppSession` on iOS). One per
 * process: on Android it lives in the Application, so the playback service
 * and Android Auto (later) share it with the screens.
 * Downloads (A4) live here too, one manager per sign-in like the player.
 */
class AppSession(val authenticator: Authenticator, val environment: AppEnvironment) {

    private val _api = MutableStateFlow<JellyfinApi?>(null)

    /** The signed-in server, or null when signed out. */
    val api: StateFlow<JellyfinApi?> = _api.asStateFlow()

    val favorites = FavoritesStore(environment.preferences, ::nowMillis)
    val artistInfo = ArtistInfoClient(artistInfoCache(environment.cacheDir), ::nowMillis)
    val playbackSettings = PlaybackSettings(environment.preferences)
    val downloadSettings = DownloadSettings(environment.preferences)

    /** Equaliser and visualiser settings (saved); the sound itself is `environment.playerEngine.audioEffects`. */
    val effectsSettings = EffectsSettings(environment.preferences)

    private val _player = MutableStateFlow<PlayerController?>(null)

    /** The player for the signed-in server (a new one per sign-in). */
    val player: StateFlow<PlayerController?> = _player.asStateFlow()

    private val _downloads = MutableStateFlow<DownloadManager?>(null)

    /** Downloads for the signed-in server (a new manager per sign-in). */
    val downloads: StateFlow<DownloadManager?> = _downloads.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The listening queue, saved between runs (restored at sign-in, forgotten at sign-out). */
    private val queueStore = PlayQueueStore.inDataFolder(environment.dataDir)

    /** The GitHub update check (Android and Linux): looked at once when the signed-in app opens. */
    val updates = UpdateChecker(environment.preferences, scope, ::nowMillis)

    init {
        // The saved equaliser, from the first track.
        environment.playerEngine.audioEffects?.let(effectsSettings::applyTo)
        // Downloads stop on mobile data (if not allowed) and carry on with Wi‑Fi.
        // Undispatched: nothing reaches the main thread until the network changes.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            environment.network.state.drop(1).collect { _downloads.value?.networkChanged() }
        }
    }

    /** Restores the saved login without a network round trip. Returns whether signed in. */
    fun restore(): Boolean {
        if (_api.value != null) return true
        val saved = authenticator.restore() ?: return false
        signIn(saved)
        return true
    }

    /**
     * Starts a fresh player and download manager for `api`, and puts back
     * the queue saved for this server and user (paused where it was).
     */
    fun signIn(api: JellyfinApi) {
        _player.value?.stop()
        val player = PlayerController(
            api, environment.playerEngine, playbackSettings, scope, environment.isExpensiveNetwork,
            isOnline = environment.isOnline, downloads = { _downloads.value },
            queueSaver = PlayQueueSaver(scope, queueStore),
        )
        _player.value = player
        _downloads.value?.close()
        _downloads.value = DownloadManager(
            api, environment.downloadsDir, downloadSettings, scope, environment.isExpensiveNetwork, environment.downloadRunner,
            playable = environment.playerEngine.directPlayFormats, isOnline = environment.isOnline,
        )
        _api.value = api
        // After the downloads, so downloaded entries play from their files.
        queueStore.load(serverId = api.baseUrl, userId = api.userId)?.let(player::restore)
    }

    /** Writes the queue now: the app going to the background, the window closing. */
    suspend fun saveQueue() {
        _player.value?.saveQueueNow()
    }

    /** [saveQueue] without waiting (Android's onStop). */
    fun saveQueueSoon() {
        scope.launch { saveQueue() }
    }

    /** Stops playback and forgets the saved login. */
    fun signOut() {
        _player.value?.stop()
        _player.value = null
        queueStore.clear()
        _downloads.value?.close()
        _downloads.value = null
        authenticator.signOut()
        _api.value = null
    }
}

@OptIn(ExperimentalTime::class)
internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

internal expect fun artistInfoCache(cacheDir: String): ArtistInfoCache
