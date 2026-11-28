package gg.dekiru.musicplayer.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One rule for every layout decision (docs/redesign.md section 1): the class comes from the
 * window width in dp, not the platform. Compact below 600, Medium 600 to 1023, Expanded from 1024.
 */
enum class WindowClass(val pageMargin: Dp, val gutter: Dp) {
    Compact(16.dp, 12.dp),
    Medium(24.dp, 16.dp),
    Expanded(32.dp, 20.dp);

    /** Everything above Compact: the rail or sidebar, header actions, two-column pages. */
    val isWide: Boolean get() = this != Compact

    companion object {
        const val MEDIUM_FROM = 600f
        const val EXPANDED_FROM = 1024f

        /** Content never grows wider than this; it is centred beyond it. */
        val MAX_CONTENT_WIDTH = 1600.dp

        fun fromWidth(widthDp: Float): WindowClass = when {
            widthDp >= EXPANDED_FROM -> Expanded
            widthDp >= MEDIUM_FROM -> Medium
            else -> Compact
        }
    }
}

/** The window's class, from its live width. Provided at the app root by [ProvideWindowClass]. */
val LocalWindowClass = staticCompositionLocalOf { WindowClass.Compact }

/** Provides [LocalWindowClass] from the window's width (follows a resized desktop window live). */
@Composable
fun ProvideWindowClass(content: @Composable () -> Unit) {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    CompositionLocalProvider(LocalWindowClass provides WindowClass.fromWidth(width.value), content = content)
}
