package gg.dekiru.musicplayer.ui.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.network.NetworkState
import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded
import gg.dekiru.musicplayer.ui.home.OFFLINE_MESSAGE
import gg.dekiru.musicplayer.ui.home.StatusBanner
import gg.dekiru.musicplayer.ui.library.LibraryAlbumGrid
import gg.dekiru.musicplayer.ui.library.LibraryNote
import gg.dekiru.musicplayer.ui.library.LibrarySpinner
import gg.dekiru.musicplayer.ui.settings.Divider
import gg.dekiru.musicplayer.ui.settings.ToggleRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Home ▸ Recently Played › (as iOS `RecentlyPlayedView`): the Library's
 * album cards, as many across as the export's size (3, 4 or 5) on every
 * device, so no zoom buttons here; pull down to reload, and
 * "Export grid" after the last row. Offline: the downloaded albums among
 * the list last loaded this run (else Home's row, [homeRow]), under the
 * offline banner. Nothing is kept on disk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyPlayedScreen(
    api: JellyfinApi,
    model: RecentlyPlayedModel,
    preferences: Preferences,
    onOpen: (JellyfinAlbum) -> Unit,
    onBack: () -> Unit,
    network: StateFlow<NetworkState> = MutableStateFlow(NetworkState()),
    downloads: DownloadManager? = null,
    homeRow: List<JellyfinAlbum> = emptyList(),
) {
    val online = network.collectAsState().value.online
    LaunchedEffect(model, online) { if (online) model.loadIfNeeded() }
    val isDownloaded = rememberIsDownloaded(downloads)
    val albums = if (online) model.albums else (if (model.loaded) model.albums else homeRow).filter(isDownloaded)
    val localCover: (JellyfinAlbum) -> String? = { album -> if (isDownloaded(album)) downloads.coverPath(album) else null }
    // One source of truth for the grid size: the grid shows the albums the
    // export takes, and follows the Export section's choice at once.
    val settings = remember(preferences) { ExportSettings(preferences) }
    val shown = ExportGrid.shownAlbums(albums, settings.size)
    // As many across as the export's size; below nine (no export) the
    // Library's usual columns, without its zoom.
    val columns = ExportGrid.gridColumns(albums, settings.size)
    val grid = rememberLazyGridState()

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                    Text("‹ Back", color = TetsubiColors.blue, fontSize = 16.sp)
                }
                Text(
                    RECENTLY_PLAYED_TITLE, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            if (!online) StatusBanner(OFFLINE_MESSAGE, icon = "⊘")
            PullToRefreshBox(
                isRefreshing = online && model.isLoading && model.albums.isNotEmpty(),
                onRefresh = { if (online) model.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    albums.isNotEmpty() -> LibraryAlbumGrid(
                        api, shown, grid, onOpen, isDownloaded, localArt = localCover,
                        footer = { ExportSection(api, albums, settings, localCover) },
                        fixedColumns = columns, widensWithoutZoom = true,
                    )
                    !online -> LibraryNote(EMPTY)
                    model.isLoading || !model.loaded && model.errorMessage == null -> LibrarySpinner()
                    model.errorMessage != null -> StatusBanner("Can't reach your Jellyfin server. ${model.errorMessage}", "Retry", model::refresh)
                    else -> LibraryNote(EMPTY)
                }
            }
        }
    }
}

private const val RECENTLY_PLAYED_TITLE = "Recently Played"

private enum class ExportAction { COPY, SAVE }

/**
 * "Export grid": the size (3 × 3, 4 × 4, 5 × 5; one with too few albums
 * greyed out), "Show album and artist", then Copy and Save, each with a
 * spinner while the picture is made. Fewer than nine albums: one line.
 * [albums] is the whole list (for which sizes can be filled), not just the
 * ones the grid shows.
 */
@Composable
private fun ExportSection(
    api: JellyfinApi,
    albums: List<JellyfinAlbum>,
    settings: ExportSettings,
    localCover: (JellyfinAlbum) -> String?,
) {
    var busy by remember { mutableStateOf<ExportAction?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val exporter = rememberGridExporter()
    val scope = rememberCoroutineScope()
    val count = ExportGrid.distinct(albums).size
    val size = ExportGrid.resolvedSize(settings.size, count)
    val labels = settings.showLabels
    val saveOutcome by exporter.saveOutcome.collectAsState()

    // The save dialog's answer (on Android it can come after the screen was rebuilt).
    LaunchedEffect(saveOutcome) {
        val outcome = saveOutcome ?: return@LaunchedEffect
        exporter.saveOutcomeShown()
        notice = when (outcome) {
            SaveOutcome.Saved -> SAVED
            SaveOutcome.Cancelled -> null
            is SaveOutcome.Failed -> SAVE_FAILED
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2500)
            notice = null
        }
    }

    fun export(action: ExportAction) {
        val n = size ?: return
        val shown = ExportGrid.albumsFor(albums, n) ?: return
        busy = action
        notice = null
        scope.launch {
            try {
                val tiles = loadExportTiles(api, shown, localCover)
                val png = exporter.render(tiles, n, labels, loadExportFonts())
                when (action) {
                    ExportAction.COPY -> {
                        exporter.copy(png)
                        notice = COPIED
                    }
                    ExportAction.SAVE -> exporter.save(png, ExportGrid.fileName(now()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = MAKE_FAILED
            } finally {
                busy = null
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Export grid", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        if (size == null) {
            Text(TOO_FEW, color = TetsubiColors.albumYearText, fontSize = 13.sp)
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TetsubiColors.groupBackground)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Grid size", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    SizePicker(selected = size, count = count) { settings.size = it }
                }
                Divider()
                ToggleRow("Show album and artist", labels, onChange = { settings.showLabels = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ExportButton("Copy", busy == ExportAction.COPY, enabled = busy == null) { export(ExportAction.COPY) }
                ExportButton("Save", busy == ExportAction.SAVE, enabled = busy == null) { export(ExportAction.SAVE) }
                notice?.let {
                    Text(
                        it, color = TetsubiColors.albumArtistText, fontSize = 13.sp,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/** 3×3 | 4×4 | 5×5 as one segmented control; a size with too few albums is greyed and can't be picked. */
@Composable
private fun SizePicker(selected: Int, count: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f)).padding(2.dp).selectableGroup(),
    ) {
        for (n in ExportGrid.SIZES) {
            val available = ExportGrid.isAvailable(n, count)
            val isSelected = n == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) Color.White.copy(alpha = 0.28f) else Color.Transparent)
                    .selectable(selected = isSelected, enabled = available, role = Role.RadioButton) { onSelect(n) }
                    .alpha(if (available) 1f else 0.35f)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$n×$n", color = Color.White, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun ExportButton(title: String, working: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // Filled, as the album page's Play button: outlined blue text was hard to read over light album art.
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = TetsubiColors.blue, contentColor = Color.White,
            disabledContainerColor = TetsubiColors.blue.copy(alpha = 0.5f), disabledContentColor = Color.White.copy(alpha = 0.7f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // The title keeps the button's width while the spinner shows.
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.alpha(if (working) 0f else 1f))
            if (working) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
        }
    }
}

private const val TOO_FEW = "Play at least 9 albums to export a grid."
private const val EMPTY = "No recently played music yet."
private const val MAKE_FAILED = "Couldn't make the image."
private const val SAVE_FAILED = "Couldn't save the image."
private const val COPIED = "Copied"
private const val SAVED = "Saved"

@OptIn(ExperimentalTime::class)
private fun now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
