package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.layout.LocalDensityState
import gg.dekiru.musicplayer.ui.library.GridIcon
import gg.dekiru.musicplayer.ui.library.LibraryDisplayMode
import gg.dekiru.musicplayer.ui.library.ListIcon
import gg.dekiru.musicplayer.ui.library.ShuffleIcon
import gg.dekiru.musicplayer.ui.tetsubiType

// The header controls of a grid or list page (docs/redesign.md sections 3 and 4): Sort, Grid / List,
// density S / M / L and Filter. Wide windows show them inline in the page header; narrow ones collapse
// them into one "Sort & view" button and sheet with the same options.

/** One option of a [ControlMenu]. */
class ControlChoice(val title: String, val selected: Boolean, val onSelect: () -> Unit)

/** A menu of exclusive options: [title] ("Sort", "Filter"), the current [value], and the [choices]. */
class ControlMenu(val title: String, val value: String, val choices: List<ControlChoice>)

/** A [ControlMenu] over [options], with [selected] ticked. */
fun <T> controlMenu(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit): ControlMenu =
    ControlMenu(title, label(selected), options.map { ControlChoice(label(it), it == selected) { onSelect(it) } })

/** Windows at least this wide (dp) show the controls inline; narrower ones use the sheet. */
const val INLINE_CONTROLS_FROM = 720f

/** Whether a window [widthDp] dp wide has room for the inline controls next to a title. */
fun showsInlineControls(widthDp: Float): Boolean = widthDp >= INLINE_CONTROLS_FROM

/**
 * The controls a page offers (pass only what it has): [sort], [filter], a Grid / List choice
 * ([view], with [onView]), the global card size ([density]) and a "Shuffle again" action. Emits into
 * the [PageHeader]'s actions: the inline controls, or on a narrow window one Sort and view button.
 */
@Composable
fun PageControls(
    sort: ControlMenu? = null,
    filter: ControlMenu? = null,
    view: LibraryDisplayMode? = null,
    onView: (LibraryDisplayMode) -> Unit = {},
    density: Boolean = false,
    shuffle: (() -> Unit)? = null,
) {
    if (sort == null && filter == null && view == null && !density && shuffle == null) return
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val densityState = LocalDensityState.current
    if (showsInlineControls(width.value)) {
        if (shuffle != null) ActionChip("Shuffle again", shuffle)
        if (sort != null) MenuPill(sort)
        if (filter != null) MenuPill(filter)
        if (view != null) ViewControl(view, onView)
        if (density) DensityControl(densityState.density, onChange = { densityState.density = it })
    } else {
        var open by remember { mutableStateOf(false) }
        SortAndViewButton({ open = true })
        if (open) {
            SortAndViewSheet(onDismiss = { open = false }) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (view != null) SheetSection("View") { ViewControl(view, onView, Modifier.fillMaxWidth(), stretch = true) }
                    if (sort != null) SheetSection(sort.title) { SheetChoices(sort) { open = false } }
                    if (filter != null) SheetSection(filter.title) { SheetChoices(filter) { open = false } }
                    if (density) SheetSection("Card size") { DensityControl(densityState.density, onChange = { densityState.density = it }) }
                    if (shuffle != null) ActionChip("Shuffle again", { shuffle(); open = false })
                }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = tetsubiType.rowTitle, color = TetsubiColors.textSecondary)
        content()
    }
}

@Composable
private fun SheetChoices(menu: ControlMenu, close: () -> Unit) {
    val shape = RoundedCornerShape(Radii.panel)
    Column(Modifier.fillMaxWidth().clip(shape).background(TetsubiColors.surface, shape)) {
        for (choice in menu.choices) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = choice.selected, role = Role.RadioButton) { choice.onSelect(); close() }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(choice.title, style = tetsubiType.rowTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = TetsubiColors.textPrimary, modifier = Modifier.weight(1f))
                if (choice.selected) CheckIcon(20.dp, TetsubiColors.blue)
            }
        }
    }
}

/** A pill showing "Sort Name" that opens the menu of options. */
@Composable
private fun MenuPill(menu: ControlMenu) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.FULL_PERCENT)
    Box {
        Row(
            Modifier.height(32.dp).clip(shape).background(TetsubiColors.surface, shape)
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = "${menu.title}, ${menu.value}" }
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(menu.title, style = tetsubiType.control, color = TetsubiColors.textSecondary, maxLines = 1)
            Text(menu.value, style = tetsubiType.control, color = TetsubiColors.textPrimary, maxLines = 1)
            ChevronDownIcon(16.dp, TetsubiColors.textSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in menu.choices) {
                DropdownMenuItem(
                    text = { Text(choice.title, color = TetsubiColors.textPrimary) },
                    trailingIcon = { if (choice.selected) CheckIcon(18.dp, TetsubiColors.blue) },
                    onClick = { choice.onSelect(); open = false },
                    modifier = Modifier.semantics { if (choice.selected) contentDescription = "${choice.title}, selected" },
                )
            }
        }
    }
}

/** Grid | List, segmented like [DensityControl]; [stretch] fills the width (the sheet). */
@Composable
private fun ViewControl(view: LibraryDisplayMode, onView: (LibraryDisplayMode) -> Unit, modifier: Modifier = Modifier, stretch: Boolean = false) {
    val shape = RoundedCornerShape(Radii.FULL_PERCENT)
    Row(
        modifier.height(32.dp).clip(shape).background(TetsubiColors.surface, shape).padding(2.dp).selectableGroup(),
    ) {
        for (mode in LibraryDisplayMode.entries) {
            val selected = mode == view
            val tint = if (selected) TetsubiColors.blue else TetsubiColors.textSecondary
            Row(
                (if (stretch) Modifier.weight(1f) else Modifier.width(44.dp)).height(28.dp).clip(RoundedCornerShape(Radii.FULL_PERCENT))
                    .background(if (selected) TetsubiColors.blueTint else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onView(mode) })
                    .semantics { contentDescription = mode.title },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                if (mode == LibraryDisplayMode.GRID) GridIcon(18.dp, tint) else ListIcon(18.dp, tint)
                if (stretch) Text(mode.title, style = tetsubiType.control, color = tint)
            }
        }
    }
}

/** Filter chips in a row (Search: All, Albums, Artists, Playlists): the selected one is blue-tinted. */
@Composable
fun <T> FilterChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in options) {
            val isSelected = option == selected
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(Radii.FULL_PERCENT))
                    .background(if (isSelected) TetsubiColors.blueTint else TetsubiColors.surface)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = tetsubiType.control, color = if (isSelected) TetsubiColors.blue else TetsubiColors.textPrimary, maxLines = 1)
            }
        }
    }
}
