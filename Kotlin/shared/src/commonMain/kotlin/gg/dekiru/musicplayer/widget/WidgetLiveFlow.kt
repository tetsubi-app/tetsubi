package gg.dekiru.musicplayer.widget

import androidx.compose.runtime.snapshotFlow
import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.nowMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * What the app is doing, for the home-screen widget, each time it changes:
 * signed in or out, the track, play/pause, Maniac Mode and the queue's
 * length and time left (a new value only when the finish time moved). The player's
 * Compose state is read with `snapshotFlow`, so outside a screen someone
 * has to send the state's change notifications (the Android writer does).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun AppSession.widgetLive(): Flow<WidgetLive> =
    combine(api, player) { api, player -> player?.takeIf { api != null } }
        .flatMapLatest { player ->
            if (player == null) {
                flowOf(WidgetLive(signedIn = false, maniacMode = playbackSettings.maniacMode))
            } else {
                snapshotFlow {
                    WidgetLive(
                        signedIn = true,
                        track = player.currentTrack,
                        discIds = player.playingDiscIds,
                        isPlaying = player.isPlaying,
                        maniacMode = player.maniacMode,
                        queueSeconds = player.queueLength,
                        remainingSeconds = player.queueRemaining,
                        readAt = nowMillis(),
                    )
                }
            }
        }
        .distinctUntilChanged(WidgetLive::sameForWidget)
