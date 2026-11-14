package gg.dekiru.musicplayer.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.AlbumRow
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.playback.formatAlbumDuration
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import gg.dekiru.musicplayer.downloads.DownloadAlbumState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadStatus
import gg.dekiru.musicplayer.ui.components.CheckCircleIcon
import gg.dekiru.musicplayer.ui.components.DownloadCircleIcon
import gg.dekiru.musicplayer.ui.components.PauseIcon
import gg.dekiru.musicplayer.ui.components.PlayIcon
import gg.dekiru.musicplayer.ui.components.ProgressRing
import gg.dekiru.musicplayer.ui.components.PulsingProgressBar
import gg.dekiru.musicplayer.ui.components.RetryIcon
import gg.dekiru.musicplayer.ui.downloads.collectActive
import gg.dekiru.musicplayer.ui.downloads.collectDownloaded
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded

/** Content width on wide screens (tablet landscape, desktop): as the Mac app's readable width. */
private val readableWidth = 720.dp

/**
 * An album (as `AlbumDetailView` on iOS): art, title, artist, year; Play,
 * heart, Download (then its progress, Pause/Resume/Retry, and Downloaded;
 * deleting is in the Downloads tab); tracks with disc headings, each disc of a set with
 * its own download control; More by the artist; About the artist.
 */
@Composable
fun AlbumScreen(
    api: JellyfinApi,
    model: AlbumModel,
    player: PlayerController?,
    onOpen: (JellyfinAlbum) -> Unit,
    onBack: () -> Unit,
    /** "Add to Playlist…" for these track IDs: a track's menu, a disc's, and the album's own (every track). */
    onAddToPlaylist: ((List<String>) -> Unit)? = null,
) {
    LaunchedEffect(model) { model.load() }
    val manager = model.downloadManager
    val active by manager.collectActive()
    val downloaded by manager.collectDownloaded()
    val dl = remember(model.discs, active, downloaded) {
        AlbumDownloads(model.discs, active, downloaded.map { it.albumId }.toSet())
    }
    val isDownloaded = rememberIsDownloaded(manager)
    // Scrolling content stops below the status bar; the bottom inset is padding inside the list.
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()

    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        val wide = maxWidth > 600.dp
        LazyColumn(
            state = rememberLazyListState(),
            contentPadding = insets,
            modifier = Modifier.widthIn(max = readableWidth).fillMaxSize(),
        ) {
            item {
                TopBar(
                    model.album.displayName, onBack,
                    onAddToPlaylist = onAddToPlaylist?.takeIf { model.tracks.isNotEmpty() }?.let { add -> { add(model.tracks.map { it.id }) } },
                )
            }
            item { Header(api, model, artSize = if (wide) 240.dp else 160.dp, localArt = manager.coverPath(model.album)) }
            item { Actions(model, player, manager, dl) }
            tracks(model, player, manager, dl, onAddToPlaylist)
            if (model.moreByArtist.isNotEmpty()) {
                item {
                    SectionTitle("More by ${model.album.artistName}", Modifier.padding(start = 16.dp, top = 28.dp, bottom = 8.dp))
                    AlbumRow(api, model.moreByArtist, onOpen, contentPadding = PaddingValues(horizontal = 10.dp), isDownloaded = isDownloaded)
                }
            }
            model.artistInfo?.let { info -> item { AboutArtist(model.album.artistName, info) } }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, onAddToPlaylist: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
            Text("‹ Back", color = TetsubiColors.blue, fontSize = 16.sp)
        }
        Text(
            title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 16.dp),
        )
        if (onAddToPlaylist != null) {
            Box(Modifier.padding(end = 8.dp)) { AddToPlaylistMenu("More", onAddToPlaylist) }
        }
    }
}

/** The ⋯ button with "Add to Playlist…" (a track's, a disc's, the album's). */
@Composable
private fun AddToPlaylistMenu(label: String, onAddToPlaylist: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(50))
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Text("⋯", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Add to Playlist…", color = Color.White) },
                onClick = {
                    open = false
                    onAddToPlaylist()
                },
            )
        }
    }
}

@Composable
private fun Header(api: JellyfinApi, model: AlbumModel, artSize: androidx.compose.ui.unit.Dp, localArt: String?) {
    val album = model.album
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
        AlbumArt(api, album.id, size = artSize, cornerRadius = 12.dp, maxWidth = 600, localPath = localArt)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(album.displayName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (album.artistName.isNotEmpty()) Text(album.artistName, color = TetsubiColors.albumArtistText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            album.productionYear?.let { Text(it.toString(), color = TetsubiColors.albumYearText, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Actions(model: AlbumModel, player: PlayerController?, manager: DownloadManager?, dl: AlbumDownloads) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // "Now playing" (this album already playing) just opens Now Playing.
            val nowPlaying = player?.isPlaying(model.album) == true
            Button(
                onClick = {
                    if (nowPlaying) player?.isNowPlayingOpen = true else player?.play(model.album)
                },
                enabled = player?.isSupported == true,
                colors = ButtonDefaults.buttonColors(containerColor = if (nowPlaying) TetsubiColors.pink else TetsubiColors.blue, contentColor = Color.White),
            ) { Text(if (nowPlaying) "Now playing" else "▶  Play") }
            OutlinedButton(
                onClick = model::toggleFavorite,
                modifier = Modifier.semantics { contentDescription = if (model.isFavorite) "Remove from favourites" else "Add to favourites" },
            ) {
                Text(if (model.isFavorite) "♥" else "♡", color = if (model.isFavorite) TetsubiColors.pink else Color.White, fontSize = 18.sp)
            }
            DownloadButton(manager, dl)
        }
        // A single-disc album's download under way (a set shows it per disc).
        dl.singleState?.let { state -> if (manager != null) DownloadProgress(manager, state) }
    }
}

/**
 * Download → Downloading (a progress ring; tap to pause or resume a single
 * album) → Downloaded ✓ (not tappable; delete in the Downloads tab).
 * Labels as iOS.
 */
@Composable
private fun DownloadButton(manager: DownloadManager?, dl: AlbumDownloads) {
    val single = dl.singleState
    val onClick: () -> Unit = when (dl.button) {
        DownloadButton.DOWNLOAD -> ({ manager?.let { m -> dl.toStart.forEach(m::download) } })
        DownloadButton.DOWNLOADING -> ({ if (manager != null && single != null) AlbumDownloads.toggle(manager, single) })
        DownloadButton.DOWNLOADED -> ({})
    }
    val enabled = manager != null && dl.button != DownloadButton.DOWNLOADED && (dl.button != DownloadButton.DOWNLOADING || single != null)
    val label = when (dl.button) {
        DownloadButton.DOWNLOADED -> "Downloaded"
        DownloadButton.DOWNLOADING -> if (single != null) "Downloading. ${AlbumDownloads.actionLabel(single)}" else "Downloading"
        DownloadButton.DOWNLOAD -> dl.buttonLabel
    }
    // Tighter sides than the default, so "Downloaded" fits on one line beside Play and the heart on a narrow phone.
    OutlinedButton(
        onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        val color = if (dl.button == DownloadButton.DOWNLOADED) TetsubiColors.green else Color.White
        when (dl.button) {
            DownloadButton.DOWNLOADED -> CheckCircleIcon(18.dp, TetsubiColors.green)
            DownloadButton.DOWNLOADING -> ProgressRing(single?.progress ?: dl.overallProgress, 18.dp)
            DownloadButton.DOWNLOAD -> DownloadCircleIcon(18.dp, Color.White)
        }
        Spacer(Modifier.width(8.dp))
        // Downloaded stays green, as iOS.
        val dimmed = !enabled && dl.button != DownloadButton.DOWNLOADED
        Text(dl.buttonLabel, color = if (dimmed) color.copy(alpha = 0.5f) else color, maxLines = 1, softWrap = false)
    }
}

/** Bar, "Downloading 3/12 tracks", Pause/Resume/Retry and any error (as iOS). */
@Composable
internal fun DownloadProgress(manager: DownloadManager, state: DownloadAlbumState) {
    val paused = state.status == DownloadStatus.PAUSED
    val failed = state.status == DownloadStatus.FAILED
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PulsingProgressBar(state.progress, isPaused = paused || failed)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(AlbumDownloads.statusText(state), color = Color.White, fontSize = 12.sp)
            Text(AlbumDownloads.tracksText(state), color = TetsubiColors.albumYearText, fontSize = 11.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { AlbumDownloads.toggle(manager, state) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text(AlbumDownloads.actionLabel(state), color = Color.White, fontSize = 12.sp)
            }
        }
        state.error?.takeIf { it.isNotEmpty() }?.let { Text(it, color = TetsubiColors.red, fontSize = 11.sp) }
    }
}

private fun LazyListScope.tracks(
    model: AlbumModel,
    player: PlayerController?,
    manager: DownloadManager?,
    dl: AlbumDownloads,
    onAddToPlaylist: ((List<String>) -> Unit)?,
) {
    val row: LazyListScope.(List<JellyfinTrack>) -> Unit = { tracks -> trackRows(tracks, model, player, onAddToPlaylist) }
    fun addDisc(tracks: List<JellyfinTrack>): (() -> Unit)? = onAddToPlaylist?.takeIf { tracks.isNotEmpty() }?.let { add -> { add(tracks.map { it.id }) } }
    item {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            SectionTitle("Tracks", Modifier.weight(1f))
            // The album's playing time, above the tracks' own times (which sit left of the ⋯ button when there is one).
            val seconds = model.tracks.sumOf { it.durationSeconds }
            if (!model.isLoadingTracks && seconds > 0) {
                val text = formatAlbumDuration(seconds.toDouble())
                Text(
                    text, color = TetsubiColors.albumArtistText, fontSize = 12.sp,
                    modifier = Modifier.padding(end = if (onAddToPlaylist != null) 56.dp else 24.dp).semantics { contentDescription = "Playing time $text" },
                )
            }
        }
    }
    when {
        model.isLoadingTracks -> item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = TetsubiColors.albumArtistText, strokeWidth = 2.dp)
                Text("Loading tracks…", color = TetsubiColors.albumArtistText, fontSize = 12.sp)
            }
        }
        model.errorMessage != null -> item {
            Text(model.errorMessage.orEmpty(), color = Color(1f, 0.55f, 0.55f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        model.tracks.isEmpty() -> item {
            Text("No tracks found.", color = TetsubiColors.albumArtistText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        model.isMultiDisc -> model.tracksByDisc.forEach { (disc, tracks) ->
            item(key = "disc-${disc.id}") {
                DiscHeading(model.discNumber(disc), DiscGrouping.subtitle(disc), addDisc(tracks)) { DiscDownload(manager, dl, disc) }
            }
            row(tracks)
        }
        model.tracksByDiscNumber != null -> model.tracksByDiscNumber.orEmpty().forEach { (number, tracks) ->
            item(key = "disctag-$number") { DiscHeading(number, null, addDisc(tracks)) }
            row(tracks)
        }
        else -> row(model.tracks)
    }
}

private fun LazyListScope.trackRows(
    tracks: List<JellyfinTrack>,
    model: AlbumModel,
    player: PlayerController?,
    onAddToPlaylist: ((List<String>) -> Unit)?,
) {
    items(tracks, key = { "track-${it.id}" }) { track ->
        val playingHere = player != null && player.currentTrack?.id == track.id && player.isPlaying(model.album)
        // Maniac Mode: albums start at track 1, so rows aren't buttons.
        val canTap = player?.isSupported == true && !player.maniacMode
        TrackRow(
            track,
            showAlbum = !model.allTracksFromThisAlbum,
            isPlaying = playingHere,
            onClick = if (canTap) ({ player?.play(model.album, startTrackId = track.id, openNowPlaying = false) }) else null,
            onAddToPlaylist = onAddToPlaylist?.let { add -> { add(listOf(track.id)) } },
        )
    }
}

/** "Disc 2", its subtitle, and on the right (for a disc of a set) its own download control, then its ⋯ menu. */
@Composable
private fun DiscHeading(number: Int, subtitle: String?, onAddToPlaylist: (() -> Unit)?, download: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics { heading() }) {
                Text("Disc $number", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                subtitle?.let { Text(it, color = TetsubiColors.albumArtistText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            download?.invoke()
            if (onAddToPlaylist != null) AddToPlaylistMenu("More for Disc $number", onAddToPlaylist)
        }
    }
}

/** A disc's download: "3/12" (or Paused, Failed) with Pause/Resume/Retry, Downloaded ✓, or Download disc (as iOS). */
@Composable
private fun DiscDownload(manager: DownloadManager?, dl: AlbumDownloads, disc: JellyfinAlbum) {
    if (manager == null) return
    val state = dl.state(disc)
    when {
        state != null -> Column(Modifier.width(180.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val failed = state.status == DownloadStatus.FAILED
            val paused = state.status == DownloadStatus.PAUSED
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AlbumDownloads.discText(state), color = if (failed) TetsubiColors.red else TetsubiColors.albumArtistText, fontSize = 12.sp)
                val label = when {
                    failed -> "Retry disc"
                    paused -> "Resume disc"
                    else -> "Pause disc"
                }
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(50))
                        .clickable(role = Role.Button) { AlbumDownloads.toggle(manager, state) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        failed -> RetryIcon(18.dp, TetsubiColors.blue)
                        paused -> PlayIcon(16.dp, TetsubiColors.blue)
                        else -> PauseIcon(16.dp, TetsubiColors.blue)
                    }
                }
            }
            PulsingProgressBar(state.progress, isPaused = paused || failed)
            state.error?.takeIf { it.isNotEmpty() }?.let { Text(it, color = TetsubiColors.red, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        dl.isDiscDownloaded(disc) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CheckCircleIcon(14.dp, TetsubiColors.green)
            Text("Downloaded", color = TetsubiColors.green, fontSize = 12.sp)
        }
        else -> OutlinedButton(onClick = { manager.download(disc) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            Text("Download disc", color = Color.White, fontSize = 12.sp)
        }
    }
}

/** One track: tap to play the album from here; its ⋯ menu has Add to Playlist…. The playing row is solid pink with white text. */
@Composable
private fun TrackRow(track: JellyfinTrack, showAlbum: Boolean, isPlaying: Boolean, onClick: (() -> Unit)?, onAddToPlaylist: (() -> Unit)?) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp)
            .clip(shape)
            .background(if (isPlaying) TetsubiColors.pink else Color.Gray.copy(alpha = 0.05f), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 8.dp, end = if (onAddToPlaylist != null) 0.dp else 8.dp, top = if (onAddToPlaylist != null) 2.dp else 8.dp, bottom = if (onAddToPlaylist != null) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            track.trackNumber.toString().padStart(2, '0'),
            color = TetsubiColors.albumArtistText, fontSize = 12.sp, modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(
                track.displayName, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (showAlbum && !track.album.isNullOrEmpty()) {
                Text(track.album, color = TetsubiColors.albumArtistText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(formatDuration(track.durationSeconds), color = TetsubiColors.albumArtistText, fontSize = 12.sp)
        if (onAddToPlaylist != null) AddToPlaylistMenu("More for ${track.displayName}", onAddToPlaylist)
    }
}

/** 257 → "4:17". */
internal fun formatDuration(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** MusicBrainz facts and genres, the Wikipedia introduction, links to both. */
@Composable
private fun AboutArtist(artistName: String, info: gg.dekiru.musicplayer.data.artist.ArtistInfo) {
    var showFullBio by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("About $artistName")
        info.facts?.let { Text(it, color = TetsubiColors.albumArtistText, fontSize = 15.sp) }
        if (info.genres.isNotEmpty()) Text(info.genres.joinToString(" · "), color = TetsubiColors.blue, fontSize = 12.sp)
        info.summary?.let { summary ->
            Text(summary, color = Color.White, fontSize = 15.sp, maxLines = if (showFullBio) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
            if (summary.length > 300) {
                Text(
                    if (showFullBio) "Less" else "More", color = TetsubiColors.blue, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(role = Role.Button) { showFullBio = !showFullBio },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Link("MusicBrainz") { uri.openUri(info.musicBrainzUrl) }
            info.wikipediaUrl?.let { wiki ->
                Text("·", color = TetsubiColors.albumYearText, fontSize = 12.sp)
                Link("Wikipedia") { uri.openUri(wiki) }
                Text("(CC BY-SA)", color = TetsubiColors.albumYearText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Text(text, color = TetsubiColors.albumArtistText, fontSize = 12.sp, modifier = Modifier.clickable(role = Role.Button, onClick = onClick))
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.semantics { heading() })
}
