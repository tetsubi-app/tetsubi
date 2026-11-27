package gg.dekiru.musicplayer.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.data.settings.Preferences

/**
 * Cards per row on wide layouts (as iOS `gridColumns`): one remembered
 * choice for every album and artist grid; null is automatic. The grid on
 * screen reports what it shows, so the first press starts from there.
 */
class GridZoom(private val preferences: Preferences) {
    private var _columns by mutableStateOf(
        // A stored value outside 3…10 counts as automatic (as iOS).
        preferences.getString(LibraryModel.GRID_COLUMNS_KEY)?.toIntOrNull()
            ?.takeIf { it in LibraryModel.MIN_GRID_COLUMNS..LibraryModel.MAX_GRID_COLUMNS },
    )
    var columns: Int?
        get() = _columns
        set(value) {
            _columns = value
            preferences.putString(LibraryModel.GRID_COLUMNS_KEY, value?.toString())
        }

    /** Columns the visible wide grid shows now; null until one has been drawn. */
    var shown by mutableStateOf<Int?>(null)

    private val current: Int? get() = columns ?: shown

    val canZoomIn: Boolean get() = current?.let(LibraryModel::canZoomIn) ?: false
    val canZoomOut: Boolean get() = current?.let(LibraryModel::canZoomOut) ?: false

    /** Bigger cards: one fewer column. */
    fun zoomIn() {
        current?.let { columns = LibraryModel.zoomed(it, zoomIn = true) }
    }

    /** Smaller cards: one more column. */
    fun zoomOut() {
        current?.let { columns = LibraryModel.zoomed(it, zoomIn = false) }
    }
}

/** Wide layouts (zoom buttons, cards that fill their columns): from 600 dp of window width, on every platform (follows a resized desktop window live). */
@Composable
fun isWideLayout(): Boolean {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return LibraryModel.isWide(width.value)
}

/** Zoom out (−) then in (+), dimmed at the ends (as iOS's two toolbar buttons). */
@Composable
fun GridZoomButtons(zoom: GridZoom, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ZoomButton(plus = false, enabled = zoom.canZoomOut, label = "Smaller album cards", onClick = zoom::zoomOut)
        ZoomButton(plus = true, enabled = zoom.canZoomIn, label = "Bigger album cards", onClick = zoom::zoomIn)
    }
}

@Composable
private fun ZoomButton(plus: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp, 32.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { ZoomIcon(20.dp, plus, modifier = Modifier.alpha(if (enabled) 1f else 0.3f)) }
}
