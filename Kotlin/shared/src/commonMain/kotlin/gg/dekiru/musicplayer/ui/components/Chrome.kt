package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.layout.Density
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.search.WifiSlashIcon
import gg.dekiru.musicplayer.ui.tetsubiType

// Page chrome shared by every screen (docs/redesign.md section 4).

private val hairlineWidth = 0.5.dp

/** A 40 dp round touch target for header icons. */
@Composable
fun HeaderIconButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(Radii.FULL_PERCENT))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/**
 * The top of a page: [title] (28 bold) on the left, [actions] on the right. With [onBack] (detail
 * pages) a back control comes first: chevron and "Back" from Medium up, the chevron alone on Compact;
 * the title is then usually null, because the big title sits in the content.
 */
@Composable
fun PageHeader(
    title: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val windowClass = LocalWindowClass.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = windowClass.pageMargin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            Row(
                Modifier.height(40.dp).clip(RoundedCornerShape(Radii.FULL_PERCENT))
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" }
                    .padding(end = if (windowClass.isWide) 12.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { ChevronLeftIcon(24.dp, TetsubiColors.blue) }
                if (windowClass.isWide) Text("Back", style = tetsubiType.rowTitle, color = TetsubiColors.blue)
            }
        }
        if (title != null) {
            Text(title, style = tetsubiType.pageTitle, color = TetsubiColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** A section's title (20 semibold, sentence case) with an optional blue "See all" on the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    horizontalPadding: Dp = LocalWindowClass.current.pageMargin,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = horizontalPadding).padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = tetsubiType.sectionTitle, color = TetsubiColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onSeeAll != null) {
            Row(
                Modifier.clip(RoundedCornerShape(Radii.FULL_PERCENT)).clickable(role = Role.Button, onClick = onSeeAll)
                    .semantics { contentDescription = "See all $title" }
                    .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("See all", style = tetsubiType.body, color = TetsubiColors.blue)
                ChevronRightIcon(18.dp, TetsubiColors.blue)
            }
        }
    }
}

/** Library-style tabs: 15 sp, selected white bold with a 3 dp pink underline, left aligned, hairline under the row. */
@Composable
fun TetsubiTabs(titles: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val margin = LocalWindowClass.current.pageMargin
    Row(
        modifier.fillMaxWidth()
            .drawBehind {
                drawLine(TetsubiColors.hairline, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
            }
            .padding(horizontal = margin)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        titles.forEachIndexed { index, title ->
            val selected = index == selectedIndex
            Column(
                Modifier.width(IntrinsicSize.Max).selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }).padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title, color = if (selected) TetsubiColors.textPrimary else TetsubiColors.textSecondary,
                    style = tetsubiType.rowTitle.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                    maxLines = 1,
                )
                Box(
                    Modifier.padding(top = 10.dp).height(3.dp).fillMaxWidth()
                        .background(if (selected) TetsubiColors.pink else Color.Transparent, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
                )
            }
        }
    }
}

/**
 * One list row: 56 high, an optional 40 dp [art] (radius 6) or [leadingNumber] (tracks), title 15
 * medium, subtitle 12 secondary, [trailingMeta] 13 secondary and a [trailing] slot (the ⋯), and a
 * hairline inset to the text. [current] tints the row pink (the playing track).
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingNumber: String? = null,
    art: (@Composable () -> Unit)? = null,
    trailingMeta: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    current: Boolean = false,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    /** A long press or right-click (a track's menu); with [onClick] null the row still opens it on a tap. */
    onMenu: (() -> Unit)? = null,
    /** Artists: the picture is a circle. */
    artCircle: Boolean = false,
    /** Side room; the page margin unless the row sits inside something that already has it. */
    horizontalPadding: Dp = LocalWindowClass.current.pageMargin,
) {
    // The pink current-track highlight is a rounded band 8 dp wider than the content, so the
    // text still lines up with the page margin and the band never touches the pane's edge.
    val outer = (horizontalPadding - 8.dp).coerceAtLeast(0.dp)
    val margin = horizontalPadding - outer
    // Where the text starts, for the hairline.
    val inset = margin + (if (art != null) 40.dp + 12.dp else if (leadingNumber != null) 28.dp + 12.dp else 0.dp)
    Box(
        modifier.fillMaxWidth().padding(horizontal = outer).heightIn(min = 56.dp)
            .clip(RoundedCornerShape(Radii.thumb))
            .background(if (current) TetsubiColors.pinkTint else Color.Transparent)
            .then(
                when {
                    onClick != null -> Modifier.menuClickable(onClick, onMenu)
                    onMenu != null -> Modifier.menuClickable({}, onMenu)
                    else -> Modifier
                },
            )
            .drawBehind {
                if (divider && !current) {
                    val y = size.height - 0.5f
                    drawLine(TetsubiColors.hairline, Offset(inset.toPx(), y), Offset(size.width - margin.toPx(), y), 1f)
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = margin, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (art != null) {
                Box(
                    Modifier.size(40.dp).clip(if (artCircle) RoundedCornerShape(Radii.FULL_PERCENT) else RoundedCornerShape(Radii.thumb))
                        .background(TetsubiColors.art),
                ) { art() }
            } else if (leadingNumber != null) {
                Text(leadingNumber, style = tetsubiType.body, color = TetsubiColors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.width(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = tetsubiType.rowTitle, color = TetsubiColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = tetsubiType.caption, color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailingMeta != null) {
                Text(trailingMeta, style = tetsubiType.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)), color = TetsubiColors.textSecondary, maxLines = 1)
            }
            trailing?.invoke()
        }
    }
}

/** A detail page's big title (28 bold) in the content, under the [PageHeader]'s back control. */
@Composable
fun PageTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = LocalWindowClass.current.pageMargin).padding(bottom = 4.dp)) {
        Text(title, style = tetsubiType.pageTitle, color = TetsubiColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = tetsubiType.body, color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The one spinner: 24, centred at the top of the space it fills. */
@Composable
fun LoadingSpinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.TopCenter) {
        CircularProgressIndicator(Modifier.size(24.dp), color = TetsubiColors.textSecondary, strokeWidth = 2.dp)
    }
}

/** The primary button: 40 high pill, blue fill, white label; [working] shows a spinner in place of the label's room. */
@Composable
fun PrimaryButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, working: Boolean = false, color: Color = TetsubiColors.blue) {
    val shape = RoundedCornerShape(Radii.FULL_PERCENT)
    Box(
        modifier.height(40.dp).clip(shape)
            .background(if (enabled) color else color.copy(alpha = 0.5f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The title keeps the button's width while the spinner shows.
        Text(title, style = tetsubiType.rowTitle.copy(fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = if (working) 0f else 1f), maxLines = 1)
        if (working) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
    }
}

/** What a [Banner] is about; picks the real icon. */
enum class BannerKind { Warning, Offline, Update }

/** The one banner style (update, offline, error): rounded 16 surface, icon, text, an optional outlined action chip. */
@Composable
fun Banner(
    message: String,
    modifier: Modifier = Modifier,
    kind: BannerKind = BannerKind.Warning,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
    /** Side room; the page margin unless the banner sits inside something that already has it. */
    horizontalPadding: Dp = LocalWindowClass.current.pageMargin,
    /** More to the right of the text (extra chips); the banner's own action goes first. */
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val margin = horizontalPadding
    val shape = RoundedCornerShape(Radii.panel)
    Column(
        modifier.fillMaxWidth().padding(horizontal = margin, vertical = 8.dp)
            .clip(shape).background(TetsubiColors.surface, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (kind) {
                BannerKind.Warning -> WarningIcon(22.dp, TetsubiColors.pink)
                BannerKind.Offline -> WifiSlashIcon(22.dp, TetsubiColors.pink)
                BannerKind.Update -> DownloadArrowIcon(22.dp, TetsubiColors.blue)
            }
            Text(message, style = tetsubiType.body, color = TetsubiColors.textPrimary, modifier = Modifier.weight(1f))
            if (actionTitle != null && onAction != null) ActionChip(actionTitle, onAction)
        }
        content?.invoke(this)
    }
}

/** A pill, 32 high, 13 medium: the secondary action. */
@Composable
fun ActionChip(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = TetsubiColors.textPrimary) {
    Box(
        modifier.height(32.dp).clip(RoundedCornerShape(Radii.FULL_PERCENT))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.4f)), RoundedCornerShape(Radii.FULL_PERCENT))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = color, style = tetsubiType.rowTitle.copy(fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)), maxLines = 1)
    }
}

/** The one empty state: 48 icon, title, message, optional action. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: @Composable () -> Unit = { MusicNoteIcon(48.dp, TetsubiColors.textTertiary) },
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
        Text(title, style = tetsubiType.sectionTitle, color = TetsubiColors.textPrimary, textAlign = TextAlign.Center)
        if (message != null) Text(message, style = tetsubiType.body, color = TetsubiColors.textSecondary, textAlign = TextAlign.Center)
        if (actionTitle != null && onAction != null) ActionChip(actionTitle, onAction, Modifier.padding(top = 8.dp))
    }
}

/** Segmented S / M / L for the card density: the selected segment is blue-tinted with blue text. */
@Composable
fun DensityControl(density: Density, onChange: (Density) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radii.FULL_PERCENT)
    Row(
        modifier.height(32.dp).clip(shape).background(TetsubiColors.surface, shape).padding(2.dp).selectableGroup(),
    ) {
        for (option in Density.entries) {
            val selected = option == density
            Box(
                Modifier.width(36.dp).height(28.dp).clip(RoundedCornerShape(Radii.FULL_PERCENT))
                    .background(if (selected) TetsubiColors.blueTint else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onChange(option) })
                    .semantics { contentDescription = "Card size ${option.label}" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label, style = tetsubiType.rowTitle.copy(fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = if (selected) TetsubiColors.blue else TetsubiColors.textSecondary,
                )
            }
        }
    }
}

/**
 * Compact pages put sort, view, density and filter in one sheet. This scaffold hosts whatever the
 * page passes in [content]; add [DensityControl] and the page's own controls there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortAndViewSheet(onDismiss: () -> Unit, title: String = "Sort & view", content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TetsubiColors.background,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = tetsubiType.sectionTitle, color = TetsubiColors.textPrimary)
            content()
        }
    }
}

/** The Compact header button that opens [SortAndViewSheet]. */
@Composable
fun SortAndViewButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    HeaderIconButton("Sort and view", onClick, modifier) { gg.dekiru.musicplayer.ui.library.SlidersIcon(22.dp, TetsubiColors.textPrimary) }
}

/** A settings-style group: [title] section header, a rounded (16) container of rows, an optional [footer] paragraph. */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    /** A footer with styled runs (About's QA team); wins over [footer]. */
    footerText: androidx.compose.ui.text.AnnotatedString? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val margin = LocalWindowClass.current.pageMargin
    Column(modifier.fillMaxWidth()) {
        if (title != null) SectionHeader(title)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = margin)
                .clip(RoundedCornerShape(Radii.panel)).background(TetsubiColors.surface),
            content = content,
        )
        val text = footerText ?: footer?.let { androidx.compose.ui.text.AnnotatedString(it) }
        if (text != null) {
            Text(text, style = tetsubiType.caption, color = TetsubiColors.textSecondary, modifier = Modifier.fillMaxWidth().padding(horizontal = margin + 16.dp, vertical = 8.dp))
        }
    }
}

/**
 * A 52 high settings row: [title] (with an optional [caption] under it) on the left, and
 * [trailing] (a value, a switch, a chevron) on the right. [divider] draws the hairline below
 * (leave it off on a group's last row).
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    titleColor: Color = TetsubiColors.textPrimary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .drawBehind {
                if (divider) drawLine(TetsubiColors.hairline, Offset(16.dp.toPx(), size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
            },
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints {
            // The label wraps to two lines and the value keeps its width (at most 60% of the row), with a 12 dp gap.
            val valueMax = maxWidth * 0.6f
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = tetsubiType.rowTitle.copy(fontWeight = FontWeight.Normal), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (caption != null) Text(caption, style = tetsubiType.caption, color = TetsubiColors.textSecondary)
                }
                if (trailing != null) Box(Modifier.widthIn(max = valueMax)) { trailing() }
            }
        }
    }
}

/** A [SettingsRow] with a switch (green when on). */
@Composable
fun SettingsToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, caption: String? = null, divider: Boolean = true) {
    SettingsRow(
        title, caption = caption, divider = divider,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = TetsubiColors.green, checkedThumbColor = Color.White, checkedBorderColor = TetsubiColors.green),
        )
    }
}

/** A value on the right of a [SettingsRow]. */
@Composable
fun SettingsValue(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = tetsubiType.body, color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Star button for favourites: outline when off, filled pink when on (replaces the heart). */
@Composable
fun FavouriteStar(favourite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    HeaderIconButton(if (favourite) "Remove from favourites" else "Add to favourites", onToggle, modifier) {
        StarIcon(size, filled = favourite, color = if (favourite) TetsubiColors.pink else TetsubiColors.textPrimary)
    }
}
