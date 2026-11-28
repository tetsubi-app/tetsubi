package gg.dekiru.musicplayer.ui.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.downloads.DownloadAlbumState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadStatus
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.playback.formatAlbumDuration
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.ActionChip
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.AlbumShelf
import gg.dekiru.musicplayer.ui.components.ArtistAbout
import gg.dekiru.musicplayer.ui.components.ArtistLine
import gg.dekiru.musicplayer.ui.components.CheckCircleIcon
import gg.dekiru.musicplayer.ui.components.DetailMetrics
import gg.dekiru.musicplayer.ui.components.DetailPage
import gg.dekiru.musicplayer.ui.components.DetailTitle
import gg.dekiru.musicplayer.ui.components.DownloadCircleIcon
import gg.dekiru.musicplayer.ui.components.ListMessage
import gg.dekiru.musicplayer.ui.components.ListRow
import gg.dekiru.musicplayer.ui.components.LoadingSpinner
import gg.dekiru.musicplayer.ui.components.LocalPlayPrompt
import gg.dekiru.musicplayer.ui.components.MenuAction
import gg.dekiru.musicplayer.ui.components.MoreMenuButton
import gg.dekiru.musicplayer.ui.components.PageHeader
import gg.dekiru.musicplayer.ui.components.PauseIcon
import gg.dekiru.musicplayer.ui.components.PillButton
import gg.dekiru.musicplayer.ui.components.PlayChoice
import gg.dekiru.musicplayer.ui.components.PlayIcon
import gg.dekiru.musicplayer.ui.components.PlayPrompt
import gg.dekiru.musicplayer.ui.components.PrimaryButton
import gg.dekiru.musicplayer.ui.components.ProgressRing
import gg.dekiru.musicplayer.ui.components.PulsingProgressBar
import gg.dekiru.musicplayer.ui.components.RetryIcon
import gg.dekiru.musicplayer.ui.components.RoundIconButton
import gg.dekiru.musicplayer.ui.components.SectionHeader
import gg.dekiru.musicplayer.ui.components.StarIcon
import gg.dekiru.musicplayer.ui.components.WithContextMenu
import gg.dekiru.musicplayer.ui.components.queueActions
import gg.dekiru.musicplayer.ui.downloads.collectActive
import gg.dekiru.musicplayer.ui.downloads.collectDownloaded
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.layout.screenInsets
import gg.dekiru.musicplayer.ui.tetsubiType

/**
 * An album (as `AlbumDetailView` on iOS), laid out as [DetailPage]: art, title, artist, year and
 * length; Play, star and Download (then its progress, Pause/Resume/Retry, and Downloaded; deleting
 * is in the Downloads tab); tracks with disc headings, each disc of a set with its own download
 * control; More by the artist; About the artist. Two panes from Expanded.
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
    /** The artist line: that artist's albums, on this tab. */
    onOpenArtist: ((JellyfinArtist) -> Unit)? = null,
    /** Offline, the ⋯ menu's "Add to Playlist…" is greyed out (Play Next and Add to Queue still work). */
    isOnline: Boolean = true,
) {
    LaunchedEffect(model) { model.load() }
    val manager = model.downloadManager
    val active by manager.collectActive()
    val downloaded by manager.collectDownloaded()
    val dl = remember(model.discs, active, downloaded) {
        AlbumDownloads(model.discs, active, downloaded.map { it.albumId }.toSet())
    }
    val isDownloaded = rememberIsDownloaded(manager)
    val prompt = LocalPlayPrompt.current
    val album = model.album
    val aboutInfo = model.artistInfo

    Column(Modifier.fillMaxSize().screenInsets()) {
        PageHeader(null, onBack = onBack) {
            // The listening queue (the player confirms with "Playing next" / "Added to queue"), then the playlist.
            val queue = player?.takeIf { it.isSupported }?.let { p -> queueActions({ p.playNext(album) }, { p.addToQueue(album) }) }.orEmpty()
            val add = onAddToPlaylist?.takeIf { model.tracks.isNotEmpty() }?.let { add ->
                MenuAction("Add to Playlist…", enabled = isOnline) { add(model.tracks.map { it.id }) }
            }
            MoreMenuButton("More", queue + listOfNotNull(add))
        }
        DetailPage(
            modifier = Modifier.weight(1f),
            info = { m ->
                AlbumArt(api, album.id, size = m.artSize, cornerRadius = Radii.card, maxWidth = 800, localPath = manager.coverPath(album))
                val artist = model.artistLink
                DetailTitle(
                    m, album.displayName, meta = albumMeta(model),
                    artist = if (album.artistName.isNotEmpty()) ({
                        // Opens the artist's albums when it can (ArtistLink).
                        ArtistLine(album.artistName, m, if (artist != null && onOpenArtist != null) ({ onOpenArtist(artist) }) else null)
                    }) else null,
                )
                Actions(m, model, player, manager, dl, prompt)
            },
            after = if (aboutInfo != null) ({ ArtistAbout(album.artistName, aboutInfo) }) else null,
            list = { tracks(model, player, manager, dl, onAddToPlaylist, isOnline, prompt) ; moreBy(api, model, onOpen, isDownloaded) },
        )
    }
}

/** "1991 · 12 tracks · 48 min": whatever is known. */
private fun albumMeta(model: AlbumModel): String? {
    val seconds = model.tracks.sumOf { it.durationSeconds }
    return listOfNotNull(
        model.album.productionYear?.toString(),
        model.tracks.size.takeIf { it > 0 && !model.isLoadingTracks }?.let { if (it == 1) "1 track" else "$it tracks" },
        seconds.takeIf { it > 0 && !model.isLoadingTracks }?.let { formatAlbumDuration(it.toDouble()) },
    ).joinToString(" · ").ifEmpty { null }
}

private fun LazyListScope.moreBy(api: JellyfinApi, model: AlbumModel, onOpen: (JellyfinAlbum) -> Unit, isDownloaded: (JellyfinAlbum) -> Boolean) {
    if (model.moreByArtist.isEmpty()) return
    item(key = "more-by") {
        Column(Modifier.padding(top = 16.dp)) {
            SectionHeader("More by ${model.album.artistName}")
            AlbumShelf(api, model.moreByArtist, onOpen, isDownloaded = isDownloaded)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(m: DetailMetrics, model: AlbumModel, player: PlayerController?, manager: DownloadManager?, dl: AlbumDownloads, prompt: PlayPrompt) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, if (m.twoPane) Alignment.Start else Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // "Now playing" (this album already playing) just opens Now Playing.
            val nowPlaying = player?.isPlaying(model.album) == true
            PrimaryButton(
                if (nowPlaying) "Now playing" else "Play",
                onClick = {
                    when {
                        player == null -> {}
                        nowPlaying -> player.isNowPlayingOpen = true
                        // More than the playing album queued: Replace queue, Play Next or Add to Queue.
                        else -> prompt.play(player, albumChoice(player, model, track = null))
                    }
                },
                enabled = player?.isSupported == true,
                color = if (nowPlaying) TetsubiColors.pink else TetsubiColors.blue,
            )
            RoundIconButton(
                if (model.isFavorite) "Remove from favourites" else "Add to favourites", onClick = model::toggleFavorite, size = 40.dp,
            ) { StarIcon(22.dp, filled = model.isFavorite, color = if (model.isFavorite) TetsubiColors.pink else it) }
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
    PillButton(
        dl.buttonLabel, onClick, enabled = enabled || dl.button == DownloadButton.DOWNLOADED,
        // Downloaded stays green, as iOS; a tap does nothing then.
        color = if (dl.button == DownloadButton.DOWNLOADED) TetsubiColors.green else TetsubiColors.textPrimary,
        description = label,
        icon = {
            when (dl.button) {
                DownloadButton.DOWNLOADED -> CheckCircleIcon(18.dp, TetsubiColors.green)
                DownloadButton.DOWNLOADING -> ProgressRing(single?.progress ?: dl.overallProgress, 18.dp)
                DownloadButton.DOWNLOAD -> DownloadCircleIcon(18.dp, if (enabled) TetsubiColors.textPrimary else TetsubiColors.textTertiary)
            }
        },
    )
}

/** Bar, "Downloading 3/12 tracks", Pause/Resume/Retry and any error (as iOS). */
@Composable
internal fun DownloadProgress(manager: DownloadManager, state: DownloadAlbumState) {
    val paused = state.status == DownloadStatus.PAUSED
    val failed = state.status == DownloadStatus.FAILED
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PulsingProgressBar(state.progress, isPaused = paused || failed)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(AlbumDownloads.statusText(state), style = tetsubiType.caption, color = TetsubiColors.textPrimary)
            Text(AlbumDownloads.tracksText(state), style = tetsubiType.caption, color = TetsubiColors.textTertiary, modifier = Modifier.weight(1f))
            ActionChip(AlbumDownloads.actionLabel(state), { AlbumDownloads.toggle(manager, state) })
        }
        state.error?.takeIf { it.isNotEmpty() }?.let { Text(it, style = tetsubiType.caption, color = TetsubiColors.red) }
    }
}

private fun LazyListScope.tracks(
    model: AlbumModel,
    player: PlayerController?,
    manager: DownloadManager?,
    dl: AlbumDownloads,
    onAddToPlaylist: ((List<String>) -> Unit)?,
    isOnline: Boolean,
    prompt: PlayPrompt,
) {
    val row: LazyListScope.(List<JellyfinTrack>) -> Unit = { tracks -> trackRows(tracks, model, player, onAddToPlaylist, isOnline, prompt) }
    fun addDisc(tracks: List<JellyfinTrack>): (() -> Unit)? = onAddToPlaylist?.takeIf { tracks.isNotEmpty() }?.let { add -> { add(tracks.map { it.id }) } }
    item(key = "tracks-title") { SectionHeader("Tracks") }
    when {
        model.isLoadingTracks -> item(key = "tracks-loading") { LoadingSpinner() }
        model.errorMessage != null -> item(key = "tracks-error") { ListMessage(model.errorMessage.orEmpty(), color = TetsubiColors.red) }
        model.tracks.isEmpty() -> item(key = "tracks-empty") { ListMessage("No tracks found.") }
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
    isOnline: Boolean,
    prompt: PlayPrompt,
) {
    items(tracks, key = { "track-${it.id}" }) { track ->
        val playingHere = player != null && player.currentTrack?.id == track.id && player.isPlaying(model.album)
        // Maniac Mode: albums start at track 1, so rows aren't buttons.
        val canTap = player?.isSupported == true && !player.maniacMode
        // The song's menu (⋯, long press, right-click): Play Next, Add to Queue (in Maniac Mode too), Add to Playlist….
        val queue = player?.takeIf { it.isSupported }?.let { p ->
            queueActions({ p.playNext(model.album, listOf(track)) }, { p.addToQueue(model.album, listOf(track)) })
        }.orEmpty()
        val menu = queue + listOfNotNull(onAddToPlaylist?.let { add -> MenuAction("Add to Playlist…", enabled = isOnline) { add(listOf(track.id)) } })
        WithContextMenu(menu) { openMenu ->
            ListRow(
                title = track.displayName,
                subtitle = track.album?.takeIf { !model.allTracksFromThisAlbum && it.isNotEmpty() },
                leadingNumber = track.trackNumber.toString().padStart(2, '0'),
                trailingMeta = formatDuration(track.durationSeconds),
                trailing = if (menu.isNotEmpty()) ({ MoreMenuButton("More for ${track.displayName}", menu) }) else null,
                current = playingHere,
                onClick = if (canTap && player != null) ({ prompt.play(player, albumChoice(player, model, track = track)) }) else null,
                onMenu = openMenu,
            )
        }
    }
}

/** Play the album (from the tapped `track`), or, from the prompt, queue it instead (a tapped track: just that track). */
private fun albumChoice(player: PlayerController, model: AlbumModel, track: JellyfinTrack?) = PlayChoice(
    title = track?.displayName ?: model.album.displayName,
    replace = { player.play(model.album, startTrackId = track?.id, openNowPlaying = track == null) },
    playNext = { if (track != null) player.playNext(model.album, listOf(track)) else player.playNext(model.album) },
    addToQueue = { if (track != null) player.addToQueue(model.album, listOf(track)) else player.addToQueue(model.album) },
)

/** "Disc 2", its subtitle, and on the right (for a disc of a set) its own download control, then its ⋯ menu. */
@Composable
private fun DiscHeading(number: Int, subtitle: String?, onAddToPlaylist: (() -> Unit)?, download: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LocalWindowClass.current.pageMargin).padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).semantics { heading() }) {
            Text("Disc $number", style = tetsubiType.rowTitle.copy(fontWeight = FontWeight.Bold), color = TetsubiColors.textPrimary)
            subtitle?.let { Text(it, style = tetsubiType.caption, color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        download?.invoke()
        if (onAddToPlaylist != null) {
            MoreMenuButton("More for Disc $number", listOf(MenuAction("Add to Playlist…", action = onAddToPlaylist)))
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
                Text(AlbumDownloads.discText(state), style = tetsubiType.caption, color = if (failed) TetsubiColors.red else TetsubiColors.textSecondary)
                val label = when {
                    failed -> "Retry disc"
                    paused -> "Resume disc"
                    else -> "Pause disc"
                }
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(Radii.FULL_PERCENT))
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
            state.error?.takeIf { it.isNotEmpty() }?.let { Text(it, style = tetsubiType.caption, color = TetsubiColors.red, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        dl.isDiscDownloaded(disc) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CheckCircleIcon(14.dp, TetsubiColors.green)
            Text("Downloaded", style = tetsubiType.caption, color = TetsubiColors.green)
        }
        else -> ActionChip("Download disc", { manager.download(disc) })
    }
}

/** 257 → "4:17". */
internal fun formatDuration(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
