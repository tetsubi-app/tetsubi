package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

// Small drawn icons for the player controls (plain triangles and bars, as on
// iOS). Unicode ⏮ ⏸ ⏭ show as emoji on Android, so they're drawn here.

@Composable
fun PlayIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawTriangle(color, left = size.toPx() * 0.18f, width = size.toPx() * 0.72f) }
}

@Composable
fun PauseIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val bar = s * 0.26f
        val top = s * 0.12f
        val height = s * 0.76f
        drawRoundRect(color, Offset(s * 0.17f, top), Size(bar, height), CornerRadius(bar * 0.2f))
        drawRoundRect(color, Offset(s * 0.57f, top), Size(bar, height), CornerRadius(bar * 0.2f))
    }
}

/** Two triangles and a bar: next (forward) or previous. */
@Composable
fun SkipIcon(forward: Boolean, size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val draw: DrawScope.() -> Unit = {
            drawTriangle(color, left = s * 0.04f, width = s * 0.42f, heightFraction = 0.6f)
            drawTriangle(color, left = s * 0.42f, width = s * 0.42f, heightFraction = 0.6f)
            drawRect(color, Offset(s * 0.84f, s * 0.2f), Size(s * 0.1f, s * 0.6f))
        }
        if (forward) draw() else scaleHorizontally(draw)
    }
}

/** "⌄": close Now Playing. */
@Composable
fun ChevronDownIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val path = Path().apply {
            moveTo(s * 0.2f, s * 0.38f)
            lineTo(s * 0.5f, s * 0.66f)
            lineTo(s * 0.8f, s * 0.38f)
        }
        drawPath(path, color, style = Stroke(width = s * 0.1f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawTriangle(color: Color, left: Float, width: Float, heightFraction: Float = 0.8f) {
    val h = size.height * heightFraction
    val top = (size.height - h) / 2
    val path = Path().apply {
        moveTo(left, top)
        lineTo(left + width, top + h / 2)
        lineTo(left, top + h)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.scaleHorizontally(block: DrawScope.() -> Unit) {
    drawContext.transform.scale(-1f, 1f, Offset(size.width / 2, size.height / 2))
    block()
    drawContext.transform.scale(-1f, 1f, Offset(size.width / 2, size.height / 2))
}


/** A speaker with sound waves: the output picker. */
@Composable
fun SpeakerIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val body = Path().apply {
            moveTo(s * 0.1f, s * 0.38f)
            lineTo(s * 0.28f, s * 0.38f)
            lineTo(s * 0.5f, s * 0.18f)
            lineTo(s * 0.5f, s * 0.82f)
            lineTo(s * 0.28f, s * 0.62f)
            lineTo(s * 0.1f, s * 0.62f)
            close()
        }
        drawPath(body, color)
        val stroke = Stroke(width = s * 0.07f, cap = StrokeCap.Round)
        drawArc(color, -45f, 90f, false, Offset(s * 0.42f, s * 0.32f), Size(s * 0.3f, s * 0.36f), style = stroke)
        drawArc(color, -50f, 100f, false, Offset(s * 0.4f, s * 0.16f), Size(s * 0.5f, s * 0.68f), style = stroke)
    }
}

/** "›": open the full list. */
@Composable
fun ChevronRightIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val path = Path().apply {
            moveTo(s * 0.38f, s * 0.2f)
            lineTo(s * 0.66f, s * 0.5f)
            lineTo(s * 0.38f, s * 0.8f)
        }
        drawPath(path, color, style = Stroke(width = s * 0.11f, cap = StrokeCap.Round))
    }
}

/** A house outline: the Home tab (as SF Symbols "house"). */
@Composable
fun HouseIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val stroke = Stroke(width = s * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Roof, then the walls with a door.
        val roof = Path().apply {
            moveTo(s * 0.08f, s * 0.48f)
            lineTo(s * 0.5f, s * 0.1f)
            lineTo(s * 0.92f, s * 0.48f)
        }
        drawPath(roof, color, style = stroke)
        val walls = Path().apply {
            moveTo(s * 0.2f, s * 0.4f)
            lineTo(s * 0.2f, s * 0.88f)
            lineTo(s * 0.4f, s * 0.88f)
            lineTo(s * 0.4f, s * 0.64f)
            lineTo(s * 0.6f, s * 0.64f)
            lineTo(s * 0.6f, s * 0.88f)
            lineTo(s * 0.8f, s * 0.88f)
            lineTo(s * 0.8f, s * 0.4f)
        }
        drawPath(walls, color, style = stroke)
    }
}

/** An arrow down in a circle: the Downloads tab and Download (as "arrow.down.circle"). */
@Composable
fun DownloadCircleIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val stroke = Stroke(width = s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color, radius = s * 0.44f, style = stroke)
        drawLine(color, Offset(s * 0.5f, s * 0.27f), Offset(s * 0.5f, s * 0.7f), strokeWidth = s * 0.075f, cap = StrokeCap.Round)
        val head = Path().apply {
            moveTo(s * 0.33f, s * 0.54f)
            lineTo(s * 0.5f, s * 0.71f)
            lineTo(s * 0.67f, s * 0.54f)
        }
        drawPath(head, color, style = stroke)
    }
}

/** A filled circle with the tick cut out (as "checkmark.circle.fill"): downloaded. */
@Composable
fun CheckCircleIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    // Drawn in its own layer so the tick clears the circle and shows what's behind.
    Canvas(modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val s = this.size.width
        drawCircle(color, radius = s * 0.48f)
        val tick = Path().apply {
            moveTo(s * 0.29f, s * 0.52f)
            lineTo(s * 0.44f, s * 0.67f)
            lineTo(s * 0.72f, s * 0.36f)
        }
        drawPath(tick, Color.Black, style = Stroke(width = s * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Clear)
    }
}

/** A circular arrow (as "arrow.clockwise"): Retry. */
@Composable
fun RetryIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val w = s * 0.09f
        drawArc(color, -60f, 300f, false, Offset(s * 0.17f, s * 0.17f), Size(s * 0.66f, s * 0.66f), style = Stroke(width = w, cap = StrokeCap.Round))
        // The arrow head at the arc's open end (top right).
        val head = Path().apply {
            moveTo(s * 0.52f, s * 0.08f)
            lineTo(s * 0.71f, s * 0.21f)
            lineTo(s * 0.55f, s * 0.36f)
        }
        drawPath(head, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A filled circle with an × cut out (as "xmark.circle.fill"): Cancel. */
@Composable
fun XCircleIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val s = this.size.width
        drawCircle(color, radius = s * 0.48f)
        val w = s * 0.1f
        drawLine(Color.Black, Offset(s * 0.34f, s * 0.34f), Offset(s * 0.66f, s * 0.66f), strokeWidth = w, cap = StrokeCap.Round, blendMode = BlendMode.Clear)
        drawLine(Color.Black, Offset(s * 0.66f, s * 0.34f), Offset(s * 0.34f, s * 0.66f), strokeWidth = w, cap = StrokeCap.Round, blendMode = BlendMode.Clear)
    }
}

/** An empty circle: not selected (edit mode). */
@Composable
fun CircleIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        drawCircle(color, radius = this.size.width * 0.44f, style = Stroke(width = this.size.width * 0.07f))
    }
}

/** Four rounded squares (as "square.grid.2x2"): the Library tab. */
@Composable
fun GridTabIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val stroke = Stroke(width = s * 0.075f)
        val box = s * 0.34f
        for (x in listOf(s * 0.1f, s * 0.56f)) for (y in listOf(s * 0.1f, s * 0.56f)) {
            drawRoundRect(color, Offset(x, y), Size(box, box), CornerRadius(s * 0.07f), style = stroke)
        }
    }
}

/** A gear (as "gearshape"): the Settings tab. */
@Composable
fun GearIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val c = Offset(s / 2, s / 2)
        val teeth = 8
        val outer = s * 0.45f
        val inner = s * 0.34f
        val gear = Path()
        for (i in 0 until teeth * 4) {
            // Each tooth: up, across the top, down, across the gap.
            val r = if (i % 4 == 1 || i % 4 == 2) outer else inner
            val a = (i / (teeth * 4f)) * 2 * kotlin.math.PI.toFloat() + kotlin.math.PI.toFloat() / (teeth * 4)
            val p = Offset(c.x + r * kotlin.math.cos(a), c.y + r * kotlin.math.sin(a))
            if (i == 0) gear.moveTo(p.x, p.y) else gear.lineTo(p.x, p.y)
        }
        gear.close()
        drawPath(gear, color, style = Stroke(width = s * 0.07f, join = StrokeJoin.Round))
        drawCircle(color, radius = s * 0.13f, center = c, style = Stroke(width = s * 0.07f))
    }
}
