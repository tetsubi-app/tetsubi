package gg.dekiru.musicplayer.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

// Settings' own drawn icons (as the SF Symbols on iOS).

/** "arrow.right.circle": Sign Out. */
@Composable
internal fun SignOutIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val line = s * 0.08f
        drawCircle(color, radius = s * 0.44f, style = Stroke(width = line))
        drawLine(color, Offset(s * 0.28f, s * 0.5f), Offset(s * 0.7f, s * 0.5f), strokeWidth = line, cap = StrokeCap.Round)
        val head = Path().apply {
            moveTo(s * 0.52f, s * 0.32f)
            lineTo(s * 0.7f, s * 0.5f)
            lineTo(s * 0.52f, s * 0.68f)
        }
        drawPath(head, color, style = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** "chevron.up.chevron.down": a menu picker's value. */
@Composable
internal fun ChevronUpDownIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val stroke = Stroke(width = s * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply { moveTo(s * 0.3f, s * 0.38f); lineTo(s * 0.5f, s * 0.18f); lineTo(s * 0.7f, s * 0.38f) }, color, style = stroke)
        drawPath(Path().apply { moveTo(s * 0.3f, s * 0.62f); lineTo(s * 0.5f, s * 0.82f); lineTo(s * 0.7f, s * 0.62f) }, color, style = stroke)
    }
}

/** "checkmark": the chosen item in a menu. */
@Composable
internal fun CheckmarkIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val path = Path().apply { moveTo(s * 0.18f, s * 0.52f); lineTo(s * 0.42f, s * 0.76f); lineTo(s * 0.84f, s * 0.26f) }
        drawPath(path, color, style = Stroke(width = s * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
