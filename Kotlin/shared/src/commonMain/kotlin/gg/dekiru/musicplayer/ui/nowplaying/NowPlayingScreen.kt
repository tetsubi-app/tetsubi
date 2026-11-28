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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import gg.dekiru.musicplayer.ui.components.SeekSlider
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
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.ArtistLink
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.ui.components.artistLink
import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.playback.CastRoute
import gg.dekiru.musicplayer.playback.EndTimes
import gg.dekiru.musicplayer.playback.showsQueueEnd
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
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.components.MoreIcon
import gg.dekiru.musicplayer.ui.components.RepeatIcon
import gg.dekiru.musicplayer.ui.components.RoundIconButton
import gg.dekiru.musicplayer.ui.components.WarningIcon
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.library.ShuffleIcon
import gg.dekiru.musicplayer.ui.tetsubiType
import androidx.compose.foundation.layout.fillMaxHeight
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
 * Now Playing (as `NowPlayingView` on iOS): art, title / artist / album, the album's length and
 * when it finishes, the seek bar, previous / play-pause / next, then a row of round icon buttons
 * (Shuffle, Repeat, Visualiser, Cast, Output; blue when on) and the queue ([QueueSection]: reorder,
 * remove, clear). In Maniac Mode: no skips, shuffle, repeat or scrubbing. Back or ⌄ closes it.
 * Where the player has audio effects (Android), the Visualiser toggle shows spectrum bars under the
 * art; the choice is remembered. Where there's Google Cast (Android), the Cast button sits next to
 * it; while casting, "Playing on <device>" shows under the album and the Visualiser is hidden. The
 * info line's quality text shows the sample rate and bit depth; a tap switches it to the format and
 * bitrate and back (remembered).
 *
 * The window class picks the layout ([NowPlayingLayout]): Compact is one column, a full-screen sheet
 * with the queue below the controls; Medium and Expanded are two, the player on the left and the
 * queue in a panel on the right, each scrolling on its own.
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
    /** The artist line: close Now Playing and show that artist's albums. Null: plain text. */
    onOpenArtist: ((JellyfinArtist) -> Unit)? = null,
) {
    BackHandler { player.isNowPlayingOpen = false }
    val artAccent = rememberArtAccent(api, player.currentTrack?.artItemId, player.currentTrack?.localArtPath)
    val accent = rememberAnimatedAccent(artAccent)
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
            val twoColumns = NowPlayingLayout.usesTwoColumns(LocalWindowClass.current)
            val showBars = effects != null && visualiser?.isOn == true && !casting
            // Art, titles, progress and buttons: the single column's top and the left column.
            val details: @Composable (Dp) -> Unit = { artSize ->
                val track = player.currentTrack
                if (track != null) {
                    AlbumArt(api, track.artItemId, size = artSize, cornerRadius = Radii.panel, maxWidth = 800, localPath = track.localArtPath)
                    // While casting the audio isn't decoded here: no bars.
                    if (effects != null && showBars) {
                        SpectrumBars(effects, player.isPlaying, artAccent?.let { accent }, Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(64.dp))
                    }
                    Titles(player, track, castName, accent, qualityDetail, rememberArtistTap(api, track, isOnline, onOpenArtist))
                    player.lastError?.let { ErrorText(it) }
                    Seek(player, accent)
                    Controls(player)
                    Toggles(player, visualiser.takeUnless { casting }, castButton, accent)
                } else {
                    Box(Modifier.size(artSize).clip(RoundedCornerShape(Radii.panel)).background(TetsubiColors.surface))
                    when {
                        player.isLoading -> {
                            CircularProgressIndicator(Modifier.size(24.dp), color = TetsubiColors.textSecondary, strokeWidth = 2.dp)
                            Text("Loading…", style = tetsubiType.sectionTitle, color = TetsubiColors.textSecondary)
                        }
                        player.lastError != null -> ErrorText(player.lastError.orEmpty())
                        else -> Text("Nothing playing", style = tetsubiType.sectionTitle, color = TetsubiColors.textSecondary)
                    }
                }
            }
            if (twoColumns) {
                val left = NowPlayingLayout.leftColumnWidth(maxWidth.value)
                val art = NowPlayingLayout.wideArtSize(left, maxHeight.value, showBars).dp
                // Two columns, each scrolling on its own.
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(left.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        TopBar(player, onAddToPlaylist)
                        details(art)
                    }
                    if (player.currentTrack != null) {
                        val shape = RoundedCornerShape(Radii.panel)
                        Box(Modifier.weight(1f).fillMaxHeight().padding(top = 12.dp, end = 24.dp, bottom = 16.dp)) {
                            Column(
                                Modifier.fillMaxSize().clip(shape).background(TetsubiColors.surface, shape)
                                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                            ) { QueueSection(api, player) }
                        }
                    } else {
                        Box(Modifier.weight(1f))
                    }
                }
            } else {
                val art = NowPlayingLayout.compactArtSize(maxWidth.value, maxHeight.value).dp
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowPlayingLayout.COMPACT_MARGIN.dp).padding(top = 8.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TopBar(player, onAddToPlaylist)
                    details(art)
                    if (player.currentTrack != null) QueueSection(api, player)
                }
            }
        }
    }
}

/** ⌄ (close) on the left and ⋯ (Add to Playlist…) on the right. */
@Composable
private fun TopBar(player: PlayerController, onAddToPlaylist: ((String) -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton("Close Now Playing", onClick = { player.isNowPlayingOpen = false }, size = 40.dp) { ChevronDownIcon(24.dp, it) }
        Box(Modifier.weight(1f))
        val current = player.currentTrack
        if (onAddToPlaylist != null && current != null) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                RoundIconButton("More", onClick = { menuOpen = true }, size = 40.dp) { MoreIcon(22.dp, it) }
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
}

/**
 * What a tap on the artist line does (as iOS): the track's album artist by
 * ID, or found by name on the server (online); null when there's none, or
 * for "Various Artists" ([ArtistLink]).
 */
@Composable
private fun rememberArtistTap(api: JellyfinApi, track: PlaybackTrack, isOnline: Boolean, onOpenArtist: ((JellyfinArtist) -> Unit)?): (() -> Unit)? {
    val resolved = remember(track.artist, track.albumArtistId) { ArtistLink.resolve(track.artist, track.albumArtistId) }
    val found by produceState<JellyfinArtist?>(null, resolved, isOnline, onOpenArtist != null) {
        value = null
        if (resolved is ArtistLink.Lookup && onOpenArtist != null) {
            value = ArtistLink.find(resolved.name, null, isOnline) { term ->
                try {
                    api.searchArtists(term, limit = 10)
                } catch (e: JellyfinError) {
                    emptyList()
                }
            }
        }
    }
    val artist = (resolved as? ArtistLink.Artist)?.artist ?: found.takeIf { resolved is ArtistLink.Lookup }
    return if (artist != null && onOpenArtist != null) ({ onOpenArtist(artist) }) else null
}

@OptIn(ExperimentalTime::class)
@Composable
private fun Titles(
    player: PlayerController, track: PlaybackTrack, castName: String?, accent: Color, qualityDetail: QualityDetailSetting,
    onArtist: (() -> Unit)?,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(track.title, style = tetsubiType.nowPlayingTitle, color = TetsubiColors.textPrimary, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        Text(
            track.artist, style = tetsubiType.nowPlayingArtist, color = if (onArtist != null) accent else TetsubiColors.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.artistLink(track.artist, onArtist),
        )
        Text(track.album, style = tetsubiType.body.copy(fontSize = 15.sp), color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (castName != null) {
            Text(
                CastRoute.playingOn(castName), style = tetsubiType.control, color = accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (player.queueLength > 0) {
            // Re-worked out every 15 s so the finish time keeps up while paused.
            val now by produceState(Clock.System.now()) {
                while (true) {
                    delay(15_000)
                    value = Clock.System.now()
                }
            }
            fun clock(seconds: Double): String {
                val end = (now + seconds.seconds).toLocalDateTime(TimeZone.currentSystemDefault())
                return EndTimes.clock(end.hour, end.minute)
            }
            // "Album ends 13:02 · Queue ends 15:40" when more than this album is queued, else "Ends: 13:02".
            val showsQueue = player.playQueue.showsQueueEnd
            val groupEnd = clock(if (showsQueue) player.currentGroupRemaining else player.queueRemaining)
            val queueEnd = if (showsQueue) clock(player.queueRemaining) else null
            val label = player.playQueue.currentEntry?.let { cur ->
                EndTimes.groupLabel(cur.group.kind, player.playQueue.entries.count { it.batchId == cur.batchId })
            } ?: "Album ends"
            val quality = QualityText.of(track, qualityDetail.showsDetail)
            // One line: when both don't fit, "Album ends" goes first.
            var short by remember(showsQueue, quality?.text) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (quality != null) QualityLabel(quality, qualityDetail, accent)
                Text(
                    "${if (quality != null) "      " else ""}Duration: ${formatAlbumDuration(player.queueLength)}      ${EndTimes.text(groupEnd, queueEnd, short, label)}",
                    style = tetsubiType.caption, color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    onTextLayout = { if (queueEnd != null && !short && it.hasVisualOverflow) short = true },
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

private val infoColor = TetsubiColors.textSecondary

/**
 * The quality part of the info line: the sample rate or the format and
 * bitrate; a tap switches between them (when there are both). Pressed or
 * under the pointer it takes the art's colour, as the toggles.
 */
@Composable
private fun QualityLabel(quality: QualityText, setting: QualityDetailSetting, accent: Color) {
    if (!quality.switches) {
        Text(quality.text, style = tetsubiType.caption, color = infoColor, maxLines = 1)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        quality.text,
        style = tetsubiType.caption,
        color = if (pressed || hovered) accent else infoColor,
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        WarningIcon(18.dp, TetsubiColors.red)
        Text(message, style = tetsubiType.control.copy(fontWeight = FontWeight.Normal), color = TetsubiColors.red, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun Seek(player: PlayerController, accent: Color) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val line = accent
    val progress = if (player.duration > 0) (player.position / player.duration).toFloat().coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        if (player.maniacMode) {
            // No scrubbing: a plain progress bar.
            LinearProgressIndicator(
                progress = { progress }, color = line, trackColor = TetsubiColors.art,
                drawStopIndicator = {}, gapSize = 0.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 18.dp).height(4.dp),
            )
        } else {
            SeekSlider(
                value = dragging ?: progress,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekToProgress(it.toDouble()) }
                    dragging = null
                },
                accent = accent,
                modifier = Modifier.semantics { contentDescription = "Position" },
            )
        }
        // 8 dp in: lines the times up with the ends of the track (the thumb sticks out 8 dp).
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val shown = dragging?.let { it * player.duration } ?: player.position
            Text(formatTrackTime(shown), style = tetsubiType.navLabel, color = TetsubiColors.textSecondary, modifier = Modifier.weight(1f))
            Text(formatTrackTime(player.duration), style = tetsubiType.navLabel, color = TetsubiColors.textSecondary)
        }
    }
}

@Composable
private fun Controls(player: PlayerController) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!player.maniacMode) ControlButton("Previous", 56.dp, player::skipToPrevious) { SkipIcon(false, 32.dp) }
        PlayPauseButton(player, 72.dp, 44.dp)
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
private fun Toggles(player: PlayerController, visualiser: VisualiserToggle?, cast: CastButton, accent: Color) {
    val output = rememberOutputSwitcher()
    val castPicker = rememberCastPicker(cast.casting)
    val showCast = castPicker != null && cast.visible
    // Round 44 dp buttons: tint and icon in the art's accent when on. Shuffle and Repeat are hidden in Maniac Mode;
    // the rest are hidden where the platform has none (the desktop has no Visualiser, Cast or Output).
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
        if (!player.maniacMode) {
            RoundIconButton(
                "Shuffle: ${if (player.shuffle) "On" else "Off"}", onClick = player::toggleShuffle, on = player.shuffle, accent = accent,
            ) { ShuffleIcon(22.dp, it) }
            RoundIconButton(
                "Repeat: ${when (player.repeatMode) { RepeatMode.OFF -> "Off"; RepeatMode.ALL -> "All"; RepeatMode.ONE -> "1" }}",
                onClick = player::cycleRepeatMode, on = player.repeatMode != RepeatMode.OFF, accent = accent,
            ) { RepeatIcon(22.dp, it, one = player.repeatMode == RepeatMode.ONE) }
        }
        // Stays in Maniac Mode (as iOS).
        if (visualiser != null) {
            RoundIconButton(
                if (visualiser.isOn) "Visualiser, on" else "Visualiser, off", onClick = visualiser::toggle, on = visualiser.isOn, accent = accent,
            ) { WaveformIcon(22.dp, it) }
        }
        // Next to the Visualiser (hidden while casting).
        if (castPicker != null && showCast) {
            RoundIconButton(
                if (cast.casting) "Casting. Change or stop" else "Play on a Cast device", onClick = castPicker, on = cast.casting, accent = accent,
            ) { CastIcon(22.dp, it) }
        }
        if (output != null) {
            // On while music goes to headphones, Bluetooth, USB…, as iOS's AirPlay icon.
            val external = rememberExternalOutput()
            RoundIconButton("Choose speakers or headphones", onClick = output, on = external, accent = accent) { SpeakerIcon(22.dp, it) }
        }
    }
}
