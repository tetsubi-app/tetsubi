package gg.dekiru.musicplayer.ui.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * Opens Google Cast's device list, or while casting the device's own
 * controls (with Stop casting). Null where there's no Cast (the desktop).
 */
@Composable
expect fun rememberCastPicker(casting: Boolean): (() -> Unit)?

/** The Cast icon: a screen with the three waves in its corner. */
@Composable
internal fun CastIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val stroke = Stroke(width = s * 0.08f, cap = StrokeCap.Round)
        // The screen's top and right edges, leaving its bottom-left corner open for the waves.
        drawLine(color, Offset(s * 0.1f, s * 0.38f), Offset(s * 0.1f, s * 0.2f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(s * 0.1f, s * 0.2f), Offset(s * 0.9f, s * 0.2f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(s * 0.9f, s * 0.2f), Offset(s * 0.9f, s * 0.82f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(s * 0.9f, s * 0.82f), Offset(s * 0.58f, s * 0.82f), stroke.width, StrokeCap.Round)
        // Waves from the bottom-left corner.
        val corner = Offset(s * 0.1f, s * 0.82f)
        drawCircle(color, s * 0.06f, Offset(corner.x + s * 0.03f, corner.y - s * 0.03f))
        for (r in listOf(0.2f, 0.36f)) {
            val radius = s * r
            drawArc(color, -90f, 90f, false, Offset(corner.x - radius, corner.y - radius), Size(radius * 2, radius * 2), style = stroke)
        }
    }
}
