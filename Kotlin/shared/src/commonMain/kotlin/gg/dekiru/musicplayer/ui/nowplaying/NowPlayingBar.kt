package gg.dekiru.musicplayer.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import gg.dekiru.musicplayer.ui.components.SeekSlider
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import gg.dekiru.musicplayer.playback.CastRoute
import gg.dekiru.musicplayer.playback.formatTrackTime
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.components.QueueIcon
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.tetsubiType
import kotlinx.coroutines.flow.MutableStateFlow
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.PauseIcon
import gg.dekiru.musicplayer.ui.components.PlayIcon
import gg.dekiru.musicplayer.ui.components.SkipIcon

/**
 * The mini player, docked full width of the content area (as `NowPlayingBarView` on iOS, but not a
 * floating pill): 44 art, title and artist, previous / play-pause / next (no skips in Maniac Mode)
 * and a 3 dp progress line on the top edge. Medium and Expanded (72 high) add a seek bar, Queue
 * (opens Now Playing, where the queue is) and Cast when there is one; Compact is 64 high. Tapping
 * the art or title opens Now Playing.
 */
@Composable
fun NowPlayingBar(
    api: JellyfinApi,
    player: PlayerController,
    modifier: Modifier = Modifier,
    cast: CastRoute? = null,
    isOnline: Boolean = true,
) {
    val track = player.currentTrack ?: return
    val windowClass = LocalWindowClass.current
    val wide = windowClass.isWide
    // The art's accent (brand blue until it's worked out), fading as the art changes.
    val line = rememberHeldAccent(rememberArtAccentResult(api, track.artItemId, track.localArtPath))
    val progress = if (player.duration > 0) (player.position / player.duration).toFloat().coerceIn(0f, 1f) else 0f
    Column(
        modifier.fillMaxWidth().background(TetsubiColors.background.copy(alpha = 0.55f)).background(TetsubiColors.surface)
            .drawBehind { drawLine(TetsubiColors.hairline, Offset(0f, 0f), Offset(size.width, 0f), 1f) },
    ) {
        // The 3 dp progress line on the top edge.
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.12f))) {
            if (player.duration > 0) Box(Modifier.fillMaxWidth(progress).height(3.dp).background(line))
        }
        Row(
            Modifier.fillMaxWidth().height(if (wide) 69.dp else 61.dp).padding(horizontal = if (wide) windowClass.pageMargin else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier
                    .weight(if (wide) 0.9f else 1f)
                    .clip(RoundedCornerShape(Radii.thumb))
                    .clickable(role = Role.Button) { player.isNowPlayingOpen = true }
                    .semantics { contentDescription = "Now playing: ${track.title}. Open Now Playing" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlbumArt(api, track.artItemId, size = 44.dp, cornerRadius = 6.dp, maxWidth = 100, localPath = track.localArtPath)
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = tetsubiType.rowTitle, color = TetsubiColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (player.trackFailed) {
                        Text("Couldn't play \u2014 press Play to try again", style = tetsubiType.caption, color = TetsubiColors.pink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        val subtitle = if (wide) listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" \u00B7 ") else track.artist
                        if (subtitle.isNotBlank()) {
                            Text(subtitle, style = tetsubiType.caption, color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (!player.maniacMode) ControlButton("Previous", 40.dp, player::skipToPrevious) { SkipIcon(forward = false, size = 22.dp) }
            PlayPauseButton(player, 44.dp, 26.dp)
            if (!player.maniacMode) ControlButton("Next", 40.dp, player::skipToNext) { SkipIcon(forward = true, size = 22.dp) }
            if (wide) {
                MiniSeekBar(player, line, Modifier.weight(1f).padding(horizontal = 8.dp))
                ControlButton("Queue", 40.dp, { player.isNowPlayingOpen = true }) { QueueIcon(22.dp, TetsubiColors.textSecondary) }
                MiniCastButton(cast, isOnline, line)
            }
        }
    }
}

/** A seek bar for the docked player: a slider with the time at each end; none in Maniac Mode (no scrubbing). */
@Composable
private fun MiniSeekBar(player: PlayerController, accent: Color, modifier: Modifier) {
    if (player.maniacMode) {
        Box(modifier)
        return
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val progress = if (player.duration > 0) (player.position / player.duration).toFloat().coerceIn(0f, 1f) else 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val shown = dragging?.let { it * player.duration } ?: player.position
        Text(formatTrackTime(shown), style = tetsubiType.caption, color = TetsubiColors.textSecondary, maxLines = 1)
        SeekSlider(
            value = dragging ?: progress,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekToProgress(it.toDouble()) }
                dragging = null
            },
            accent = accent,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Position" },
        )
        Text(formatTrackTime(player.duration), style = tetsubiType.caption, color = TetsubiColors.textSecondary, maxLines = 1)
    }
}

/** Opens the Cast device list; hidden where there is no Cast (the desktop) or nothing to pick. */
@Composable
private fun MiniCastButton(cast: CastRoute?, isOnline: Boolean, accent: Color) {
    val name by (cast?.deviceName ?: noCastName).collectAsState()
    val devices by (cast?.devicesAvailable ?: noCastDevices).collectAsState()
    val casting = name != null
    val picker = rememberCastPicker(casting)
    if (cast == null || picker == null || !CastRoute.showsButton(isOnline, devices, casting)) return
    ControlButton(if (casting) "Casting. Change or stop" else "Play on a Cast device", 40.dp, picker) {
        CastIcon(22.dp, if (casting) accent else TetsubiColors.textSecondary)
    }
}

private val noCastName = MutableStateFlow<String?>(null)
private val noCastDevices = MutableStateFlow(false)

/**
 * Play or Pause; while the track is starting ([PlayerController.isStarting])
 * a small spinner in the same space, and a press does nothing.
 */
@Composable
internal fun PlayPauseButton(player: PlayerController, size: Dp, iconSize: Dp) {
    val label = when {
        player.isStarting -> "Loading"
        player.isPlaying -> "Pause"
        else -> "Play"
    }
    ControlButton(label, size, player::togglePlayPause) {
        when {
            player.isStarting -> CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(iconSize * 0.75f),
            )
            player.isPlaying -> PauseIcon(iconSize)
            else -> PlayIcon(iconSize)
        }
    }
}

/** A round touch target with a drawn icon. */
@Composable
internal fun ControlButton(label: String, size: Dp, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon() }
}
