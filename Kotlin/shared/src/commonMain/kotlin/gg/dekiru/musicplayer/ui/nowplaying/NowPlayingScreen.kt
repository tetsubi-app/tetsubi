package gg.dekiru.musicplayer.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.playback.CastRoute
import gg.dekiru.musicplayer.playback.PlaybackTrack
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.playback.RepeatMode
import gg.dekiru.musicplayer.playback.effects.AudioEffects
import gg.dekiru.musicplayer.playback.effects.EffectsSettings
import gg.dekiru.musicplayer.playback.formatAlbumDuration
import gg.dekiru.musicplayer.playback.formatTrackTime
import gg.dekiru.musicplayer.ui.AppBackground
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.AlbumBackdrop
import gg.dekiru.musicplayer.ui.components.ChevronDownIcon
import gg.dekiru.musicplayer.ui.components.PauseIcon
import gg.dekiru.musicplayer.ui.components.PlayIcon
import gg.dekiru.musicplayer.ui.components.SkipIcon
import gg.dekiru.musicplayer.ui.components.SpeakerIcon
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Full-screen Now Playing (as `NowPlayingView` on iOS): art, title / artist /
 * album, the album's length and when it finishes, the seek bar, previous /
 * play-pause / next, shuffle, repeat, the output picker, and Up next. In
 * Maniac Mode: no skips, shuffle, repeat or scrubbing. Back or ⌄ closes it.
 * Where the player has audio effects (Android), the Visualiser toggle shows
 * spectrum bars under the art; the choice is remembered. Where there's
 * Google Cast (Android), the Cast button sits next to it; while casting,
 * "Playing on <device>" shows under the album and the Visualiser is hidden.
 * The info line's quality text shows the sample rate and bit depth; a tap
 * switches it to the format and bitrate and back (remembered).
 * Wide (a tablet on its side, a Linux window; see [NowPlayingLayout]): two
 * columns as iOS, the art, titles and controls on the left and Up next on
 * the right, everything drawn bigger by [NowPlayingLayout.scale]. Phones keep
 * the single column at the normal size.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NowPlayingScreen(
    api: JellyfinApi,
    player: PlayerController,
    /** "Add to Playlist…" for the current song (its track ID). */
    onAddToPlaylist: ((String) -> Unit)? = null,
    /** The player's effects (null: no visualiser, as on the desktop). */
    effects: AudioEffects? = null,
    /** Where the Visualiser toggle is remembered. */
    effectsSettings: EffectsSettings? = null,
    /** Google Cast (null: no Cast button, as on the desktop). */
    cast: CastRoute? = null,
    /** Offline the Cast button is hidden: the TV streams from the server. */
    isOnline: Boolean = true,
    /** Where the quality text's choice (sample rate or format and bitrate) is remembered. */
    preferences: Preferences? = null,
) {
    BackHandler { player.isNowPlayingOpen = false }
    val accent = rememberArtAccent(api, player.currentTrack?.artItemId, player.currentTrack?.localArtPath)
    val visualiser = remember(effects, effectsSettings) {
        if (effects != null && effectsSettings != null) VisualiserToggle(effectsSettings) else null
    }
    val qualityDetail = remember(preferences) { QualityDetailSetting(preferences) }
    val castName by (cast?.deviceName ?: noCastDevice).collectAsState()
    val castDevices by (cast?.devicesAvailable ?: noCastDevices).collectAsState()
    val casting = castName != null
    val castButton = CastButton(
        visible = cast != null && CastRoute.showsButton(isOnline, castDevices, casting),
        casting = casting,
    )
    AppBackground {
        AlbumBackdrop(api, player.currentTrack?.artItemId, localPath = player.currentTrack?.localArtPath)
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Wide (a tablet on its side, a Linux window): two columns, everything
            // grown by the scale. Otherwise one column at the normal size.
            val wide = NowPlayingLayout.isWide(maxWidth.value, maxHeight.value)
            val scale = NowPlayingLayout.scale(maxWidth.value, maxHeight.value)
            val density = LocalDensity.current
            val showBars = effects != null && visualiser?.isOn == true && !casting
            // Art, titles, progress and buttons: the single column's top and the
            // wide layout's left column.
            val details: @Composable (Dp) -> Unit = { artSize ->
                val track = player.currentTrack
                if (track != null) {
                    AlbumArt(api, track.artItemId, size = artSize, cornerRadius = 16.dp, maxWidth = 600, localPath = track.localArtPath)
                    // While casting the audio isn't decoded here: no bars.
                    if (effects != null && showBars) {
                        SpectrumBars(effects, player.isPlaying, accent, Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(64.dp))
                    }
                    Titles(player, track, castName, accent, qualityDetail)
                    player.lastError?.let { ErrorText(it) }
                    Seek(player, accent)
                    Controls(player)
                    Toggles(player, visualiser.takeUnless { casting }, castButton, accent)
                } else {
                    Box(Modifier.size(artSize).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f)))
                    when {
                        player.isLoading -> {
                            CircularProgressIndicator(Modifier.size(24.dp), color = TetsubiColors.albumArtistText, strokeWidth = 2.dp)
                            Text("Loading…", color = TetsubiColors.albumArtistText, fontSize = 20.sp)
                        }
                        player.lastError != null -> ErrorText(player.lastError.orEmpty())
                        else -> Text("Nothing playing", color = TetsubiColors.albumArtistText, fontSize = 20.sp)
                    }
                }
            }
            // Drawn at the scale: dp and sp inside are `scale` times bigger, so
            // the sizes below are the normal ones (as iOS's `.scaled` values).
            CompositionLocalProvider(
                LocalDensity provides if (wide) Density(density.density * scale, density.fontScale) else density,
            ) {
                // The area's size in the scaled dp.
                val width = maxWidth / scale
                val height = maxHeight / scale
                if (wide) {
                    val left = NowPlayingLayout.leftColumnWidth(width.value)
                    val art = NowPlayingLayout.wideArtSize(left, height.value, showBars).dp
                    // Two columns, each scrolling on its own. The top padding keeps both
                    // clear of the More and close buttons in the corners.
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.width(left.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                                .padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) { details(art) }
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                                .padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 24.dp),
                        ) { if (player.currentTrack != null) UpNext(player) }
                    }
                } else {
                    val artSize = minOf(width - 40.dp, 340.dp, height * 0.42f)
                    Column(
                        Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Spacer(Modifier.height(28.dp))
                        details(artSize)
                        if (player.currentTrack != null) UpNext(player)
                    }
                }
                CornerButtons(player, onAddToPlaylist)
            }
        }
    }
}

/** ⋯ (Add to Playlist…) top left and ⌄ (close) top right. */
@Composable
private fun BoxScope.CornerButtons(player: PlayerController, onAddToPlaylist: ((String) -> Unit)?) {
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 12.dp, end = 16.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(role = Role.Button) { player.isNowPlayingOpen = false }
            .semantics { contentDescription = "Close Now Playing" },
        contentAlignment = Alignment.Center,
    ) { ChevronDownIcon(24.dp) }
    val current = player.currentTrack
    if (onAddToPlaylist != null && current != null) {
        var menuOpen by remember { mutableStateOf(false) }
        Box(Modifier.align(Alignment.TopStart).padding(top = 12.dp, start = 16.dp)) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(role = Role.Button) { menuOpen = true }
                    .semantics { contentDescription = "More" },
                contentAlignment = Alignment.Center,
            ) { Text("⋯", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Add to Playlist…", color = Color.White) },
                    onClick = {
                        menuOpen = false
                        onAddToPlaylist(current.id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun Titles(player: PlayerController, track: PlaybackTrack, castName: String?, accent: Color?, qualityDetail: QualityDetailSetting) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(track.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        Text(track.artist, color = TetsubiColors.albumArtistText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.album, color = TetsubiColors.albumYearText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (castName != null) {
            Text(
                CastRoute.playingOn(castName), color = accent ?: TetsubiColors.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (player.albumLength > 0) {
            // Re-worked out every 15 s so the finish time keeps up while paused.
            val now by produceState(Clock.System.now()) {
                while (true) {
                    delay(15_000)
                    value = Clock.System.now()
                }
            }
            val end = (now + player.albumRemaining.seconds).toLocalDateTime(TimeZone.currentSystemDefault())
            val finish = "${end.hour.toString().padStart(2, '0')}:${end.minute.toString().padStart(2, '0')}"
            val quality = QualityText.of(track, qualityDetail.showsDetail)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (quality != null) QualityLabel(quality, qualityDetail, accent)
                Text(
                    "${if (quality != null) "      " else ""}Duration: ${formatAlbumDuration(player.albumLength)}      Ends: $finish",
                    color = infoColor, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

private val infoColor = Color.White.copy(alpha = 0.5f)

/**
 * The quality part of the info line: the sample rate or the format and
 * bitrate; a tap switches between them (when there are both). Pressed or
 * under the pointer it takes the art's colour, as the toggles.
 */
@Composable
private fun QualityLabel(quality: QualityText, setting: QualityDetailSetting, accent: Color?) {
    if (!quality.switches) {
        Text(quality.text, color = infoColor, fontSize = 12.sp, maxLines = 1)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        quality.text,
        color = if (pressed || hovered) accent ?: TetsubiColors.blue else infoColor,
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = QualityText.hint(setting.showsDetail),
                onClick = setting::toggle,
            )
            .semantics { contentDescription = quality.text },
    )
}

/** A playback failure: what went wrong and what to do. */
@Composable
private fun ErrorText(message: String) {
    Text("⚠  $message", color = Color(1f, 0.55f, 0.55f), fontSize = 13.sp, textAlign = TextAlign.Center)
}

@Composable
private fun Seek(player: PlayerController, accent: Color?) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    // The progress colour from the art (as iOS), fading as the art changes; blue when it has none.
    val line by animateColorAsState(accent ?: TetsubiColors.blue, tween(300), label = "progress")
    val progress = if (player.duration > 0) (player.position / player.duration).toFloat().coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        if (player.maniacMode) {
            // No scrubbing: a plain progress bar.
            LinearProgressIndicator(
                progress = { progress }, color = line, trackColor = Color.White.copy(alpha = 0.2f),
                drawStopIndicator = {}, gapSize = 0.dp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).height(4.dp),
            )
        } else {
            Slider(
                value = dragging ?: progress,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekToProgress(it.toDouble()) }
                    dragging = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = line,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ),
                modifier = Modifier.semantics { contentDescription = "Position" },
            )
        }
        Row(Modifier.fillMaxWidth()) {
            val shown = dragging?.let { it * player.duration } ?: player.position
            Text(formatTrackTime(shown), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(formatTrackTime(player.duration), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun Controls(player: PlayerController) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!player.maniacMode) ControlButton("Previous", 56.dp, player::skipToPrevious) { SkipIcon(false, 32.dp) }
        ControlButton(if (player.isPlaying) "Pause" else "Play", 72.dp, player::togglePlayPause) {
            if (player.isPlaying) PauseIcon(44.dp) else PlayIcon(44.dp)
        }
        if (!player.maniacMode) ControlButton("Next", 56.dp, player::skipToNext) { SkipIcon(true, 32.dp) }
    }
}

/** The Visualiser toggle's state, saved as iOS `nowPlaying.showVisualiser`. */
private class VisualiserToggle(private val settings: EffectsSettings) {
    var isOn by mutableStateOf(settings.showVisualiser)
        private set

    fun toggle() {
        isOn = !isOn
        settings.showVisualiser = isOn
    }
}

/** The Cast toggle: shown or not, and whether it's casting. */
private class CastButton(val visible: Boolean, val casting: Boolean)

private val noCastDevice = MutableStateFlow<String?>(null)
private val noCastDevices = MutableStateFlow(false)

@Composable
private fun Toggles(player: PlayerController, visualiser: VisualiserToggle?, cast: CastButton, accent: Color?) {
    val output = rememberOutputSwitcher()
    val castPicker = rememberCastPicker(cast.casting)
    val dim = Color.White.copy(alpha = 0.6f)
    val activeTint = accent ?: TetsubiColors.blue
    val showCast = castPicker != null && cast.visible
    val count = (if (player.maniacMode) 0 else 2) + listOf(visualiser != null, showCast, output != null).count { it }
    // Equal columns, each label on one line: five fit a 360 dp phone ("Output"
    // wrapped letter by letter on a 394 dp one with fixed 32 dp gaps). Up to
    // 80 dp a column, so two or three stay together, centred.
    Row(Modifier.widthIn(max = 80.dp * count).fillMaxWidth()) {
        val column = Modifier.weight(1f)
        if (!player.maniacMode) {
            Toggle("⇄", "Shuffle", active = player.shuffle, onClick = player::toggleShuffle, tint = activeTint, modifier = column)
            Toggle(
                if (player.repeatMode == RepeatMode.ONE) "↻1" else "↻",
                when (player.repeatMode) {
                    RepeatMode.OFF -> "Off"
                    RepeatMode.ALL -> "All"
                    RepeatMode.ONE -> "1"
                },
                active = player.repeatMode != RepeatMode.OFF,
                onClick = player::cycleRepeatMode,
                description = "Repeat",
                tint = activeTint,
                modifier = column,
            )
        }
        // Stays in Maniac Mode (as iOS).
        if (visualiser != null) {
            Toggle("", "Visualiser", active = visualiser.isOn, onClick = visualiser::toggle, onOff = visualiser.isOn, modifier = column) {
                WaveformIcon(22.dp, if (visualiser.isOn) activeTint else Color.White.copy(alpha = 0.6f))
            }
        }
        // Next to the Visualiser (hidden while casting); in the art's colour while casting, as iOS's AirPlay icon.
        if (castPicker != null && showCast) {
            val description = if (cast.casting) "Casting. Change or stop" else "Play on a Cast device"
            Toggle("", "Cast", active = cast.casting, onClick = castPicker, description = description, modifier = column) {
                CastIcon(22.dp, if (cast.casting) activeTint else dim)
            }
        }
        if (output != null) {
            // In the art's colour while music goes to headphones, Bluetooth, USB…, as iOS's AirPlay icon.
            val external = rememberExternalOutput()
            Toggle("", "Output", active = external, onClick = output, description = "Choose speakers or headphones", modifier = column) {
                SpeakerIcon(22.dp, if (external) activeTint else dim)
            }
        }
    }
}

@Composable
private fun Toggle(
    symbol: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    description: String = label,
    /** For an on/off toggle named by its label: read out as "Visualiser, On". */
    onOff: Boolean? = null,
    /** The symbol's colour when on: the art's colour (blue when it has none). */
    tint: Color = TetsubiColors.blue,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 2.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                if (onOff != null) {
                    contentDescription = label
                    stateDescription = if (onOff) "On" else "Off"
                } else {
                    contentDescription = "$description: $label"
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(Modifier.height(27.dp), contentAlignment = Alignment.Center) { icon() }
        } else {
            Text(symbol, color = if (active) tint else Color.White.copy(alpha = 0.6f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun UpNext(player: PlayerController) {
    val upcoming = player.upNext
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text("Up next", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            when {
                player.repeatMode == RepeatMode.ONE -> Text("Repeating this track", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                player.repeatMode == RepeatMode.ALL && upcoming.isNotEmpty() -> Text("Then repeats", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
        }
        if (upcoming.isEmpty()) {
            Text("Last track", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        upcoming.forEachIndexed { i, (index, track) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !player.maniacMode, role = Role.Button) { player.skipTo(index) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Artist only when it differs (compilations).
                    if (track.artist != player.currentTrack?.artist && track.artist.isNotEmpty()) {
                        Text(track.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (track.durationSeconds > 0) Text(formatTrackTime(track.durationSeconds), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
            if (i != upcoming.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        }
    }
}
