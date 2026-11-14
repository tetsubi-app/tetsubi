package gg.dekiru.musicplayer.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

// Search's drawn icons (in the style of ui/components/Icons.kt).

/** A magnifying glass (as "magnifyingglass"): the search field, empty states, the Search tab. */
@Composable
fun MagnifyingGlassIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val w = s * 0.09f
        drawCircle(color, radius = s * 0.28f, center = Offset(s * 0.42f, s * 0.42f), style = Stroke(width = w))
        drawLine(color, Offset(s * 0.63f, s * 0.63f), Offset(s * 0.88f, s * 0.88f), strokeWidth = w * 1.3f, cap = StrokeCap.Round)
    }
}

/** A clock in an anticlockwise arrow (as "clock.arrow.circlepath"): a recent search. */
@Composable
internal fun ClockArrowIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val w = s * 0.08f
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // The circle, open at the left where the arrow head is.
        drawArc(color, 200f, 300f, false, Offset(s * 0.12f, s * 0.12f), Size(s * 0.76f, s * 0.76f), style = stroke)
        val head = Path().apply {
            moveTo(s * 0.04f, s * 0.42f)
            lineTo(s * 0.15f, s * 0.56f)
            lineTo(s * 0.29f, s * 0.45f)
        }
        drawPath(head, color, style = stroke)
        // The hands.
        val hands = Path().apply {
            moveTo(s * 0.5f, s * 0.3f)
            lineTo(s * 0.5f, s * 0.52f)
            lineTo(s * 0.64f, s * 0.62f)
        }
        drawPath(hands, color, style = stroke)
    }
}

/** Wi‑Fi waves struck through (as "wifi.slash"): searching downloads only. */
@Composable
internal fun WifiSlashIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val w = s * 0.08f
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        val center = Offset(s * 0.5f, s * 0.8f)
        for (r in listOf(0.62f, 0.42f, 0.22f)) {
            val radius = s * r
            drawArc(color, 225f, 90f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = stroke)
        }
        drawCircle(color, radius = w * 0.9f, center = center)
        drawLine(color, Offset(s * 0.12f, s * 0.1f), Offset(s * 0.88f, s * 0.9f), strokeWidth = w, cap = StrokeCap.Round)
    }
}
