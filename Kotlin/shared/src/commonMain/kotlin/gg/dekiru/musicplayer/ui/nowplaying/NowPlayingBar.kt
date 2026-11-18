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
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.PauseIcon
import gg.dekiru.musicplayer.ui.components.PlayIcon
import gg.dekiru.musicplayer.ui.components.SkipIcon

private val lozenge = RoundedCornerShape(28.dp)

/**
 * The floating now-playing lozenge (as `NowPlayingBarView` on iOS): art,
 * title, previous / play-pause / next (no skips in Maniac Mode) and a thin
 * progress line. Tapping the art or title opens Now Playing.
 */
@Composable
fun NowPlayingBar(api: JellyfinApi, player: PlayerController, modifier: Modifier = Modifier) {
    val track = player.currentTrack ?: return
    // The art's key colour, as Now Playing's progress line (blue when it has none).
    val accent = rememberArtAccent(api, track.artItemId, track.localArtPath)
    val line by animateColorAsState(accent ?: TetsubiColors.blue, tween(300), label = "progress")
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .clip(lozenge)
                .background(Color(0xE6101A33), lozenge)
                .border(1.dp, Color.White.copy(alpha = 0.12f), lozenge),
        ) {
            // The row has the same space above and below it, so the art, title and buttons sit in
            // the middle of the lozenge; the progress line runs along the bottom, in that space.
            Box {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(role = Role.Button) { player.isNowPlayingOpen = true }
                        .semantics { contentDescription = "Now playing: ${track.title}. Open Now Playing" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AlbumArt(api, track.artItemId, size = 36.dp, cornerRadius = 6.dp, maxWidth = 100, localPath = track.localArtPath)
                    Column {
                        Text(track.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (player.trackFailed) {
                            Text("Couldn't play — press Play to try again", color = Color(1f, 0.55f, 0.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (!player.maniacMode) ControlButton("Previous", 44.dp, player::skipToPrevious) { SkipIcon(forward = false, size = 22.dp) }
                ControlButton(if (player.isPlaying) "Pause" else "Play", 48.dp, player::togglePlayPause) {
                    if (player.isPlaying) PauseIcon(26.dp) else PlayIcon(26.dp)
                }
                if (!player.maniacMode) ControlButton("Next", 44.dp, player::skipToNext) { SkipIcon(forward = true, size = 22.dp) }
            }
            if (player.duration > 0) {
                LinearProgressIndicator(
                    progress = { (player.position / player.duration).toFloat().coerceIn(0f, 1f) },
                    color = line,
                    trackColor = Color.White.copy(alpha = 0.15f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 7.dp).height(3.dp),
                )
            }
            }
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
