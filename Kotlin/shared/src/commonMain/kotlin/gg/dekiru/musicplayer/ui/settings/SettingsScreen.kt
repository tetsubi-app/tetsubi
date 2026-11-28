package gg.dekiru.musicplayer.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.update.UpdateChecker
import gg.dekiru.musicplayer.platformName
import gg.dekiru.musicplayer.resources.Res
import gg.dekiru.musicplayer.resources.disc
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.ChevronRightIcon
import gg.dekiru.musicplayer.ui.components.PageHeader
import gg.dekiru.musicplayer.ui.components.SettingsGroup
import gg.dekiru.musicplayer.ui.components.SettingsRow
import gg.dekiru.musicplayer.ui.components.SettingsToggleRow
import gg.dekiru.musicplayer.ui.components.SettingsValue
import gg.dekiru.musicplayer.ui.downloads.DownloadsText
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.layout.WindowClass
import gg.dekiru.musicplayer.ui.layout.rememberBottomInset
import gg.dekiru.musicplayer.ui.layout.screenInsets
import gg.dekiru.musicplayer.ui.tetsubiType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

/** The Settings categories, in the order of the page (docs/redesign.md section 5). The Equaliser lives inside [PLAYBACK]. */
private enum class Category(val title: String) {
    MANIAC(SettingsText.MANIAC_TITLE),
    SERVER(SettingsText.SERVER),
    DOWNLOADS(SettingsText.DOWNLOADS),
    PLAYBACK(SettingsText.PLAYBACK),
    STORAGE(SettingsText.STORAGE),
    UPDATES(SettingsText.UPDATES),
    ABOUT(SettingsText.ABOUT),
}

/**
 * The Settings tab (as iOS `SettingsView`), in the same order: Maniac Mode, Server, Downloads,
 * Playback, Storage, Updates, About. Compact (and a narrow Medium window): one grouped list under
 * "Settings", with Playback ▸ Equaliser a pushed page ([onOpenEqualizer]). Expanded (and Medium from 900):
 * a split view, the categories (240) on the left and the selected one on the right, with the Equaliser
 * as a section of Playback; the selection is remembered.
 */
@Composable
fun SettingsScreen(
    session: AppSession,
    api: JellyfinApi,
    equalizer: EqualizerModel,
    onOpenEqualizer: () -> Unit,
) {
    val player by session.player.collectAsState()
    val downloads by session.downloads.collectAsState()
    val model = remember(session) {
        SettingsModel(session.playbackSettings, session.downloadSettings, { session.player.value }, { session.downloads.value })
    }
    // Maniac Mode can also change on Now Playing.
    val maniacMode = player?.maniacMode ?: model.maniacMode
    // The equaliser, where the player has one (not the desktop yet).
    val effects = session.environment.playerEngine.audioEffects
    val castName = session.environment.playerEngine.cast?.deviceName?.collectAsState()?.value
    val windowClass = LocalWindowClass.current
    val margin = windowClass.pageMargin
    val bottom = rememberBottomInset() + 32.dp

    val available = Category.entries.filter {
        when (it) {
            Category.DOWNLOADS -> downloads != null
            Category.UPDATES -> session.updates.enabled
            else -> true
        }
    }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val selected = Category.entries.getOrNull(selectedIndex)?.takeIf { it in available } ?: available.first()

    // One category's groups.
    val content: @Composable ColumnScope.(Category, Boolean) -> Unit = { category, split ->
        when (category) {
            Category.MANIAC -> SettingsGroup {
                SettingsToggleRow(SettingsText.MANIAC_TITLE, maniacMode, model::changeManiacMode, caption = SettingsText.MANIAC_CAPTION, divider = false)
            }
            Category.SERVER -> ServerSection(api, userName = session.authenticator.savedUserName, onSignOut = session::signOut)
            Category.DOWNLOADS -> downloads?.let { DownloadsSection(model, it) }
            Category.PLAYBACK -> {
                PlaybackSection(model, equalizer, showsEqualizerRow = effects != null && !split, onOpenEqualizer = onOpenEqualizer)
                if (effects != null && split) EqualizerContent(equalizer, casting = castName != null)
            }
            Category.STORAGE -> StorageSection()
            Category.UPDATES -> UpdatesSection(session.updates)
            Category.ABOUT -> AboutSection()
        }
    }

    Column(Modifier.fillMaxSize().screenInsets()) {
        PageHeader("Settings")
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val split = windowClass == WindowClass.Expanded || (windowClass == WindowClass.Medium && maxWidth >= 900.dp)
            if (split) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(240.dp + margin).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(start = margin, end = 0.dp, top = 8.dp, bottom = bottom),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (category in available) {
                            CategoryRow(category.title, category == selected) { selectedIndex = category.ordinal }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = bottom)) {
                        content(selected, true)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottom)) {
                    // Maniac Mode first: it changes how everything plays.
                    for (category in available) content(category, false)
                }
            }
        }
    }
}

/** A category in the split view's list: blue tint and blue text when selected. */
@Composable
private fun CategoryRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.card)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape)
            .background(if (selected) TetsubiColors.blueTint else Color.Transparent, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(title, style = tetsubiType.rowTitle, color = if (selected) TetsubiColors.blue else TetsubiColors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun ServerSection(api: JellyfinApi, userName: String?, onSignOut: () -> Unit) {
    SettingsGroup(title = SettingsText.SERVER) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectionContainer { Text(api.baseUrl, style = tetsubiType.rowTitle.copy(fontWeight = FontWeight.Normal), color = TetsubiColors.textSecondary) }
            userName?.let { Text("Signed in as $it", style = tetsubiType.body, color = TetsubiColors.textSecondary) }
            Text(SettingsText.savedIn(platformName), style = tetsubiType.caption, color = TetsubiColors.textTertiary)
        }
        GroupDivider()
        SettingsRow(SettingsText.SIGN_OUT, titleColor = TetsubiColors.red, divider = false, onClick = onSignOut) {
            SignOutIcon(20.dp, TetsubiColors.red)
        }
    }
}

@Composable
private fun DownloadsSection(model: SettingsModel, manager: gg.dekiru.musicplayer.downloads.DownloadManager) {
    val downloaded by manager.downloaded.collectAsState()
    // Android only: the desktop has no metered networks, so downloads always run there.
    val mobile = SettingsText.showsMobileData(platformName)
    SettingsGroup(title = SettingsText.DOWNLOADS, footer = SettingsText.DOWNLOADS_FOOTER.takeIf { mobile }) {
        SettingsPickerRow(
            SettingsText.DEFAULT_FORMAT,
            value = SettingsText.formatLabel(model.defaultFormat),
            options = SettingsText.formatOptions.map { it.second },
            onSelect = { model.changeDefaultFormat(SettingsText.formatOptions[it].first) },
        )
        if (mobile) SettingsToggleRow(SettingsText.DOWNLOAD_OVER_MOBILE, model.downloadOverCellular, model::changeDownloadOverCellular)
        SettingsRow(SettingsText.DISK_USAGE, divider = false) { SettingsValue(DownloadsText.formatBytes(downloaded.sumOf { it.sizeBytes })) }
    }
}

@Composable
private fun PlaybackSection(model: SettingsModel, equalizer: EqualizerModel, showsEqualizerRow: Boolean, onOpenEqualizer: () -> Unit) {
    SettingsGroup(title = SettingsText.PLAYBACK, footer = SettingsText.playbackFooter(platformName)) {
        if (SettingsText.showsMobileData(platformName)) {
            SettingsPickerRow(
                SettingsText.STREAMING_OVER_MOBILE,
                value = model.cellularQuality.displayName,
                options = SettingsText.qualityOptions.map { it.displayName },
                onSelect = { model.changeCellularQuality(SettingsText.qualityOptions[it]) },
            )
        }
        if (showsEqualizerRow) {
            SettingsRow(SettingsText.EQUALISER, onClick = onOpenEqualizer) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsValue(equalizer.summary)
                    ChevronRightIcon(14.dp, TetsubiColors.textTertiary)
                }
            }
        }
        SettingsToggleRow(SettingsText.NORMALISE, model.normalizeVolume, model::changeNormalizeVolume, divider = false)
    }
}

/** The album-art cache: Coil's files on disk (and decoded images in memory). */
@Composable
private fun StorageSection() {
    val context = LocalPlatformContext.current
    val loader = remember(context) { SingletonImageLoader.get(context) }
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(loader) { bytes = withContext(Dispatchers.Default) { loader.diskCache?.size ?: 0L } }

    SettingsGroup(title = SettingsText.STORAGE) {
        SettingsRow(SettingsText.ART_CACHE) { SettingsValue(DownloadsText.formatBytes(bytes)) }
        SettingsRow(
            SettingsText.CLEAR_IMAGE_CACHE, titleColor = TetsubiColors.red, divider = false,
            onClick = {
                // Art already on screen stays until it's loaded again.
                loader.memoryCache?.clear()
                scope.launch {
                    bytes = withContext(Dispatchers.Default) {
                        loader.diskCache?.clear()
                        loader.diskCache?.size ?: 0L
                    }
                }
            },
        )
    }
}

/** The GitHub update check (Android and Linux): the switch, Check now with what it found, and what is sent. */
@Composable
private fun UpdatesSection(updates: UpdateChecker) {
    SettingsGroup(title = SettingsText.UPDATES, footer = SettingsText.UPDATES_FOOTER) {
        SettingsToggleRow(SettingsText.CHECK_FOR_UPDATES, updates.checkForUpdates, updates::changeCheckForUpdates)
        SettingsRow(
            SettingsText.CHECK_NOW, caption = updates.status, divider = false, titleColor = if (updates.checking) TetsubiColors.textTertiary else TetsubiColors.blue,
            onClick = if (updates.checking) null else ({ updates.checkNow() }),
        ) { SettingsValue(SettingsText.version) }
    }
}

@Composable
private fun AboutSection() {
    SettingsGroup(
        title = SettingsText.ABOUT,
        footerText = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(SettingsText.QA_TEAM) }
            append("\n" + SettingsText.QA_TEAM_LINE + "\n\n")
            append(SettingsText.aboutFooter(platformName))
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
                .clearAndSetSemantics { contentDescription = "${SettingsText.APP_NAME}, ${SettingsText.TAGLINE}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(painterResource(Res.drawable.disc), contentDescription = null, modifier = Modifier.size(52.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(SettingsText.APP_NAME, style = tetsubiType.sectionTitle, color = TetsubiColors.textPrimary)
                Text(SettingsText.TAGLINE, style = tetsubiType.body, color = TetsubiColors.textSecondary)
            }
        }
        GroupDivider()
        SettingsRow(SettingsText.VERSION) { SettingsValue(SettingsText.version) }
        SettingsRow(SettingsText.ARTIST_INFORMATION, divider = false) { SettingsValue(SettingsText.ARTIST_SOURCES) }
    }
}

/** The hairline between a group's free-form block and its rows. */
@Composable
private fun GroupDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(TetsubiColors.hairline))
}

/** A row that opens a menu of choices (as an iOS menu picker). */
@Composable
internal fun SettingsPickerRow(title: String, value: String, options: List<String>, onSelect: (Int) -> Unit, enabled: Boolean = true, divider: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(
        title, divider = divider, onClick = if (enabled) ({ open = true }) else null,
        modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
    ) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsValue(value, Modifier.weight(1f, fill = false))
                ChevronUpDownIcon(14.dp, TetsubiColors.textSecondary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        leadingIcon = { Box(Modifier.size(18.dp)) { if (option == value) CheckmarkIcon(18.dp, Color.White) } },
                        onClick = { open = false; onSelect(i) },
                    )
                }
            }
        }
    }
}
