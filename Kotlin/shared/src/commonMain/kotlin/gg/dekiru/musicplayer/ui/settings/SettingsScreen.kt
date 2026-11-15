package gg.dekiru.musicplayer.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.platformName
import gg.dekiru.musicplayer.resources.Res
import gg.dekiru.musicplayer.resources.disc
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.ChevronRightIcon
import gg.dekiru.musicplayer.ui.downloads.DownloadsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

private val readableWidth = 720.dp
private val secondary = Color.White.copy(alpha = 0.6f)
private val tertiary = Color.White.copy(alpha = 0.4f)
private val divider = Color.White.copy(alpha = 0.12f)

/**
 * The Settings tab (as iOS `SettingsView`), in the same order: Maniac
 * Mode, Server, Downloads, Playback, Storage, About. Grouped boxes over
 * the background picture, at a readable width on the Pad. Playback ▸
 * Equaliser opens [EqualizerScreen] in place (back returns here).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(session: AppSession, api: JellyfinApi) {
    val player by session.player.collectAsState()
    val downloads by session.downloads.collectAsState()
    val model = remember(session) {
        SettingsModel(session.playbackSettings, session.downloadSettings, { session.player.value }, { session.downloads.value })
    }
    // Maniac Mode can also change on Now Playing.
    val maniacMode = player?.maniacMode ?: model.maniacMode
    // The equaliser, where the player has one (not the desktop yet).
    val effects = session.environment.playerEngine.audioEffects
    val equalizer = remember(session) { EqualizerModel(session.effectsSettings, effects) }
    var showEqualizer by rememberSaveable { mutableStateOf(false) }
    if (showEqualizer && effects != null) {
        BackHandler { showEqualizer = false }
        val castName = session.environment.playerEngine.cast?.deviceName?.collectAsState()?.value
        EqualizerScreen(equalizer, onBack = { showEqualizer = false }, casting = castName != null)
        return
    }

    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = readableWidth).fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(16.dp))
            // Maniac Mode first: it changes how everything plays.
            Group {
                ToggleRow(SettingsText.MANIAC_TITLE, maniacMode, model::changeManiacMode, caption = SettingsText.MANIAC_CAPTION)
            }

            ServerSection(api, userName = session.authenticator.savedUserName, onSignOut = session::signOut)

            downloads?.let { manager ->
                val downloaded by manager.downloaded.collectAsState()
                Header(SettingsText.DOWNLOADS)
                Group {
                    PickerRow(
                        SettingsText.DEFAULT_FORMAT,
                        value = SettingsText.formatLabel(model.defaultFormat),
                        options = SettingsText.formatOptions.map { it.second },
                        onSelect = { model.changeDefaultFormat(SettingsText.formatOptions[it].first) },
                    )
                    Divider()
                    ToggleRow(SettingsText.DOWNLOAD_OVER_MOBILE, model.downloadOverCellular, model::changeDownloadOverCellular)
                    Divider()
                    ValueRow(SettingsText.DISK_USAGE, DownloadsText.formatBytes(downloaded.sumOf { it.sizeBytes }))
                }
            }

            Header(SettingsText.PLAYBACK)
            Group {
                PickerRow(
                    SettingsText.STREAMING_OVER_MOBILE,
                    value = model.cellularQuality.displayName,
                    options = SettingsText.qualityOptions.map { it.displayName },
                    onSelect = { model.changeCellularQuality(SettingsText.qualityOptions[it]) },
                )
                if (effects != null) {
                    Divider()
                    NavigationRow(SettingsText.EQUALISER, equalizer.summary, onClick = { showEqualizer = true })
                }
                Divider()
                ToggleRow(SettingsText.NORMALISE, model.normalizeVolume, model::changeNormalizeVolume)
            }
            Footer(SettingsText.PLAYBACK_FOOTER)

            StorageSection()

            AboutSection()

            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
}

@Composable
private fun ServerSection(api: JellyfinApi, userName: String?, onSignOut: () -> Unit) {
    Header(SettingsText.SERVER)
    Group {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectionContainer { Text(api.baseUrl, color = secondary, fontSize = 17.sp) }
            userName?.let { Text("Signed in as $it", color = secondary, fontSize = 15.sp) }
            Text(SettingsText.savedIn(platformName), color = tertiary, fontSize = 12.sp)
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onSignOut).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SignOutIcon(20.dp, TetsubiColors.red)
            Text(SettingsText.SIGN_OUT, color = TetsubiColors.red, fontSize = 17.sp)
        }
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

    Header(SettingsText.STORAGE)
    Group {
        ValueRow(SettingsText.ART_CACHE, DownloadsText.formatBytes(bytes))
        Divider()
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) {
                // Art already on screen stays until it's loaded again.
                loader.memoryCache?.clear()
                scope.launch {
                    bytes = withContext(Dispatchers.Default) {
                        loader.diskCache?.clear()
                        loader.diskCache?.size ?: 0L
                    }
                }
            }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(SettingsText.CLEAR_IMAGE_CACHE, color = TetsubiColors.red, fontSize = 17.sp) }
    }
}

@Composable
private fun AboutSection() {
    Header(SettingsText.ABOUT)
    Group {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
                .clearAndSetSemantics { contentDescription = "${SettingsText.APP_NAME}, ${SettingsText.TAGLINE}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(painterResource(Res.drawable.disc), contentDescription = null, modifier = Modifier.size(52.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(SettingsText.APP_NAME, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(SettingsText.TAGLINE, color = TetsubiColors.albumArtistText, fontSize = 15.sp)
            }
        }
        Divider()
        ValueRow(SettingsText.VERSION, SettingsText.version)
        Divider()
        ValueRow(SettingsText.ARTIST_INFORMATION, SettingsText.ARTIST_SOURCES)
    }
    Footer(buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(SettingsText.QA_TEAM) }
        append("\n" + SettingsText.QA_TEAM_NAMES.joinToString("\n") + "\n\n")
        append(SettingsText.ABOUT_FOOTER)
    })
}

@Composable
internal fun Header(title: String) {
    Text(
        title.uppercase(), color = secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 20.dp, top = 24.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
internal fun Footer(text: String) = Footer(AnnotatedString(text))

@Composable
internal fun Footer(text: AnnotatedString) {
    Text(text, color = secondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 6.dp))
}

/** Rows in one rounded box over the background picture, as an iOS grouped form. */
@Composable
internal fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(TetsubiColors.groupBackground), content = content)
}

@Composable
internal fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(divider))
}

@Composable
internal fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, caption: String? = null) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp)
            caption?.let { Text(it, color = secondary, fontSize = 12.sp) }
        }
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = TetsubiColors.green, checkedThumbColor = Color.White, checkedBorderColor = TetsubiColors.green),
        )
    }
}

@Composable
internal fun ValueRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(value, color = secondary, fontSize = 17.sp)
    }
}

/** A row that opens another page: its value, then "›" (as an iOS navigation link). */
@Composable
internal fun NavigationRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(value, color = secondary, fontSize = 17.sp)
        ChevronRightIcon(14.dp, tertiary)
    }
}

/** A row that opens a menu of choices (as an iOS menu picker). */
@Composable
internal fun PickerRow(title: String, value: String, options: List<String>, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = title) { open = true }
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, color = secondary, fontSize = 17.sp)
                ChevronUpDownIcon(14.dp, secondary)
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
