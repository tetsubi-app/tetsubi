package gg.dekiru.musicplayer.ui.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.network.NetworkState
import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.BannerKind
import gg.dekiru.musicplayer.ui.components.EmptyState
import gg.dekiru.musicplayer.ui.components.LoadingSpinner
import gg.dekiru.musicplayer.ui.components.PageControls
import gg.dekiru.musicplayer.ui.components.PageHeader
import gg.dekiru.musicplayer.ui.components.PageTitle
import gg.dekiru.musicplayer.ui.components.PrimaryButton
import gg.dekiru.musicplayer.ui.components.SettingsRow
import gg.dekiru.musicplayer.ui.components.SettingsToggleRow
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded
import gg.dekiru.musicplayer.ui.home.OFFLINE_MESSAGE
import gg.dekiru.musicplayer.ui.home.StatusBanner
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.layout.WindowClass
import gg.dekiru.musicplayer.ui.layout.screenInsets
import gg.dekiru.musicplayer.ui.library.LibraryAlbumGrid
import gg.dekiru.musicplayer.ui.tetsubiType
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
 * Home > Recently played "See all" (as iOS `RecentlyPlayedView`): the app's album grid (card size
 * from the header's S / M / L; the export's size no longer sets the columns), pull down to reload,
 * and the export panel: a card after the last row, or on Expanded a column beside the grid. Offline: the downloaded albums among
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
    // The export's size and labels, remembered; the grid shows every album, the export takes the first n x n.
    val settings = remember(preferences) { ExportSettings(preferences) }
    val grid = rememberLazyGridState()
    val sideBySide = LocalWindowClass.current == WindowClass.Expanded

    Column(Modifier.fillMaxSize().screenInsets()) {
        PageHeader(null, onBack = onBack) { PageControls(density = true) }
        PageTitle(RECENTLY_PLAYED_TITLE)
        if (!online) StatusBanner(OFFLINE_MESSAGE, kind = BannerKind.Offline)
        PullToRefreshBox(
            isRefreshing = online && model.isLoading && model.albums.isNotEmpty(),
            onRefresh = { if (online) model.refresh() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                albums.isNotEmpty() -> {
                    val shown = ExportGrid.distinct(albums)
                    if (sideBySide) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                LibraryAlbumGrid(api, shown, grid, onOpen, isDownloaded, localArt = localCover)
                            }
                            // Sticky: the grid scrolls, this stays.
                            Column(
                                Modifier.width(EXPORT_PANEL_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState())
                                    .padding(top = 32.dp, end = 32.dp, bottom = 32.dp),
                            ) { ExportSection(api, albums, settings, localCover) }
                        }
                    } else {
                        LibraryAlbumGrid(
                            api, shown, grid, onOpen, isDownloaded, localArt = localCover,
                            footer = { ExportSection(api, albums, settings, localCover, Modifier.padding(top = 8.dp)) },
                        )
                    }
                }
                !online -> EmptyState(EMPTY)
                model.isLoading || !model.loaded && model.errorMessage == null -> LoadingSpinner()
                model.errorMessage != null -> StatusBanner("Can't reach your Jellyfin server. ${model.errorMessage}", "Retry", model::refresh)
                else -> EmptyState(EMPTY)
            }
        }
    }
}

private const val RECENTLY_PLAYED_TITLE = "Recently played"

/** The export panel's width when it sits beside the grid (the right margin is added around it). */
private val EXPORT_PANEL_WIDTH = 352.dp

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
    modifier: Modifier = Modifier,
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

    // One card: the title, the options, Copy and Save.
    val shape = RoundedCornerShape(Radii.panel)
    Column(
        modifier.fillMaxWidth().clip(shape).background(TetsubiColors.surface, shape).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Export grid", style = tetsubiType.sectionTitle, color = TetsubiColors.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        )
        if (size == null) {
            Text(TOO_FEW, style = tetsubiType.body, color = TetsubiColors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        } else {
            SettingsRow("Grid size") { SizePicker(selected = size, count = count) { settings.size = it } }
            SettingsToggleRow("Show album and artist", labels, onChange = { settings.showLabels = it }, divider = false)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton("Copy", { export(ExportAction.COPY) }, working = busy == ExportAction.COPY, enabled = busy == null)
                PrimaryButton("Save", { export(ExportAction.SAVE) }, working = busy == ExportAction.SAVE, enabled = busy == null)
                notice?.let {
                    Text(
                        it, style = tetsubiType.body, color = TetsubiColors.textSecondary,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/** 3x3 | 4x4 | 5x5 as one segmented control; a size with too few albums is greyed and can't be picked. */
@Composable
private fun SizePicker(selected: Int, count: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(Radii.FULL_PERCENT)
    Row(Modifier.clip(shape).background(TetsubiColors.surface, shape).padding(2.dp).selectableGroup()) {
        for (n in ExportGrid.SIZES) {
            val available = ExportGrid.isAvailable(n, count)
            val isSelected = n == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radii.FULL_PERCENT))
                    .background(if (isSelected) TetsubiColors.blueTint else Color.Transparent)
                    .selectable(selected = isSelected, enabled = available, role = Role.RadioButton) { onSelect(n) }
                    .alpha(if (available) 1f else 0.35f)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$n×$n", style = tetsubiType.control, color = if (isSelected) TetsubiColors.blue else TetsubiColors.textSecondary)
            }
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
