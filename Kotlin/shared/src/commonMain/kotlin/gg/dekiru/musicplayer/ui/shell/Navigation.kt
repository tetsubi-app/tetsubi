package gg.dekiru.musicplayer.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.resources.Res
import gg.dekiru.musicplayer.resources.disc
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.DownloadCircleIcon
import gg.dekiru.musicplayer.ui.components.GearIcon
import gg.dekiru.musicplayer.ui.components.GridTabIcon
import gg.dekiru.musicplayer.ui.components.HouseIcon
import gg.dekiru.musicplayer.ui.components.QueueIcon
import gg.dekiru.musicplayer.ui.search.MagnifyingGlassIcon
import gg.dekiru.musicplayer.ui.tetsubiType
import org.jetbrains.compose.resources.painterResource

/** The five destinations, in the order of iOS `AppRootView` (docs/redesign.md section 2). */
internal enum class Tab(val title: String, val icon: @Composable (Color) -> Unit) {
    HOME("Home", { HouseIcon(24.dp, it) }),
    LIBRARY("Library", { GridTabIcon(24.dp, it) }),
    SEARCH("Search", { MagnifyingGlassIcon(24.dp, it) }),
    DOWNLOADS("Downloads", { DownloadCircleIcon(24.dp, it) }),
    SETTINGS("Settings", { GearIcon(24.dp, it) }),
}

/** Width of the rail (Medium) and the sidebar (Expanded). */
val RailWidth = 80.dp
val SidebarWidth = 240.dp

/** Compact: icons over 11 sp labels, equal items across the whole width, the open tab in blue, hairline above. */
@Composable
internal fun BottomTabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(TetsubiColors.background.copy(alpha = 0.85f))) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(TetsubiColors.hairline))
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .height(56.dp).selectableGroup(),
        ) {
            for (t in Tab.entries) {
                val isSelected = t == selected
                val color = if (isSelected) TetsubiColors.blue else TetsubiColors.textSecondary
                Column(
                    Modifier.weight(1f).fillMaxHeight().selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(t) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    t.icon(color)
                    Text(t.title, style = tetsubiType.navLabel, color = color, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** Medium: an 80 dp rail down the left, icon over label, the open one on a blue tint pill. */
@Composable
internal fun NavRail(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.width(RailWidth).fillMaxHeight().background(TetsubiColors.surface)
            .drawBehind { drawLine(TetsubiColors.hairline, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Top + WindowInsetsSides.Bottom))
            .selectableGroup()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(painterResource(Res.drawable.disc), contentDescription = null, modifier = Modifier.padding(bottom = 12.dp).size(32.dp).clip(CircleShape))
        for (t in Tab.entries) {
            val isSelected = t == selected
            val color = if (isSelected) TetsubiColors.blue else TetsubiColors.textSecondary
            Column(
                Modifier.width(64.dp).height(56.dp)
                    .clip(RoundedCornerShape(Radii.card))
                    .background(if (isSelected) TetsubiColors.blueTint else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(t) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                t.icon(color)
                Text(t.title, style = tetsubiType.navLabel, color = color, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** How many playlists the sidebar lists before the rest (Library > Playlists). */
const val SIDEBAR_PLAYLISTS = 6

/**
 * Expanded: the 240 dp sidebar. Brand row, the five destinations (the open one blue-tinted with
 * blue text), the first few playlists (tap opens one), and a connection row at the bottom.
 */
@Composable
internal fun Sidebar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    playlists: List<JellyfinPlaylist>,
    onOpenPlaylist: (JellyfinPlaylist) -> Unit,
    serverName: String,
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(SidebarWidth).fillMaxHeight().background(TetsubiColors.surface)
            .drawBehind { drawLine(TetsubiColors.hairline, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Top + WindowInsetsSides.Bottom))
            .padding(12.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(Res.drawable.disc), contentDescription = null, modifier = Modifier.size(32.dp).clip(CircleShape))
            Text("Tetsubi", style = tetsubiType.sectionTitle, color = TetsubiColors.textPrimary)
        }
        Column(Modifier.padding(top = 12.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (t in Tab.entries) {
                val isSelected = t == selected
                val color = if (isSelected) TetsubiColors.blue else TetsubiColors.textPrimary
                Row(
                    Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(Radii.card))
                        .background(if (isSelected) TetsubiColors.blueTint else Color.Transparent)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(t) })
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    t.icon(color)
                    Text(t.title, style = tetsubiType.rowTitle, color = color, maxLines = 1)
                }
            }
        }
        Text("Playlists", style = tetsubiType.caption, color = TetsubiColors.textSecondary, modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 4.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            for (playlist in playlists.take(SIDEBAR_PLAYLISTS)) {
                Row(
                    Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(Radii.thumb))
                        .clickable(role = Role.Button) { onOpenPlaylist(playlist) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    QueueIcon(18.dp, TetsubiColors.textSecondary)
                    Text(playlist.displayName, style = tetsubiType.body, color = TetsubiColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Connection status: green dot and the server's address, or red and "Offline".
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = if (online) "Connected to $serverName" else "Offline" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) TetsubiColors.green else TetsubiColors.red))
            Text(
                if (online) serverName else "Offline", style = tetsubiType.caption.copy(fontWeight = FontWeight.Normal),
                color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
