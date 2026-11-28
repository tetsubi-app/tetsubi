package gg.dekiru.musicplayer.ui.components

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
import gg.dekiru.musicplayer.ui.TetsubiColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Small drawn icons for the shared page chrome (no icon library in the project, so these are paths).

/** The favourite star: outline when off, filled (pink by default) when on. Replaces the heart. */
@Composable
fun StarIcon(size: Dp, filled: Boolean, color: Color = if (filled) TetsubiColors.pink else Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val center = Offset(s / 2, s * 0.53f)
        val outer = s * 0.46f
        val inner = outer * 0.42f
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = -PI / 2 + i * PI / 5
            val x = center.x + (radius * cos(angle)).toFloat()
            val y = center.y + (radius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (filled) {
            drawPath(path, color)
            drawPath(path, color, style = Stroke(width = s * 0.06f, join = StrokeJoin.Round))
        } else {
            drawPath(path, color, style = Stroke(width = s * 0.08f, join = StrokeJoin.Round))
        }
    }
}

/** "‹": back. */
@Composable
fun ChevronLeftIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val path = Path().apply {
            moveTo(s * 0.62f, s * 0.2f)
            lineTo(s * 0.34f, s * 0.5f)
            lineTo(s * 0.62f, s * 0.8f)
        }
        drawPath(path, color, style = Stroke(width = s * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A warning triangle with an exclamation mark: errors and notices. */
@Composable
fun WarningIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val w = s * 0.08f
        val triangle = Path().apply {
            moveTo(s * 0.5f, s * 0.12f)
            lineTo(s * 0.92f, s * 0.84f)
            lineTo(s * 0.08f, s * 0.84f)
            close()
        }
        drawPath(triangle, color, style = Stroke(width = w, join = StrokeJoin.Round))
        drawLine(color, Offset(s * 0.5f, s * 0.38f), Offset(s * 0.5f, s * 0.58f), w, StrokeCap.Round)
        drawCircle(color, w * 0.7f, Offset(s * 0.5f, s * 0.71f))
    }
}

/** An arrow into a tray: an update to download. */
@Composable
fun DownloadArrowIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val w = s * 0.09f
        drawLine(color, Offset(s * 0.5f, s * 0.14f), Offset(s * 0.5f, s * 0.62f), w, StrokeCap.Round)
        val head = Path().apply {
            moveTo(s * 0.3f, s * 0.44f)
            lineTo(s * 0.5f, s * 0.64f)
            lineTo(s * 0.7f, s * 0.44f)
        }
        drawPath(head, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val tray = Path().apply {
            moveTo(s * 0.16f, s * 0.7f)
            lineTo(s * 0.16f, s * 0.84f)
            lineTo(s * 0.84f, s * 0.84f)
            lineTo(s * 0.84f, s * 0.7f)
        }
        drawPath(tray, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Three lines with a bullet each: the queue, or a playlist. */
@Composable
fun QueueIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val w = s * 0.08f
        for (y in listOf(0.26f, 0.5f, 0.74f)) {
            drawCircle(color, w * 0.7f, Offset(s * 0.18f, s * y))
            drawLine(color, Offset(s * 0.34f, s * y), Offset(s * 0.86f, s * y), w, StrokeCap.Round)
        }
    }
}

/** A cross: close, dismiss, remove. */
@Composable
fun CloseIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val w = s * 0.09f
        drawLine(color, Offset(s * 0.24f, s * 0.24f), Offset(s * 0.76f, s * 0.76f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.76f, s * 0.24f), Offset(s * 0.24f, s * 0.76f), w, StrokeCap.Round)
    }
}

/** A musical note: placeholder art and empty states. */
@Composable
fun MusicNoteIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val w = s * 0.08f
        drawLine(color, Offset(s * 0.66f, s * 0.2f), Offset(s * 0.66f, s * 0.68f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.66f, s * 0.2f), Offset(s * 0.34f, s * 0.28f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.34f, s * 0.28f), Offset(s * 0.34f, s * 0.76f), w, StrokeCap.Round)
        drawCircle(color, s * 0.12f, Offset(s * 0.55f, s * 0.72f))
        drawCircle(color, s * 0.12f, Offset(s * 0.23f, s * 0.8f))
    }
}

/** A tick: the selected choice in a menu or sheet. */
@Composable
fun CheckIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val path = Path().apply {
            moveTo(s * 0.2f, s * 0.54f)
            lineTo(s * 0.42f, s * 0.74f)
            lineTo(s * 0.8f, s * 0.28f)
        }
        drawPath(path, color, style = Stroke(width = s * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Three dots in a row: the ⋯ menu button. */
@Composable
fun MoreIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        for (x in listOf(0.2f, 0.5f, 0.8f)) drawCircle(color, s * 0.07f, Offset(s * x, s * 0.5f))
    }
}

/** Two arrows chasing round a loop: Repeat; [one] puts a 1 in the middle (Repeat One). */
@Composable
fun RepeatIcon(size: Dp, color: Color = Color.White, one: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val w = s * 0.09f
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // The loop: top edge going right, bottom edge going left, rounded ends.
        val loop = Path().apply {
            moveTo(s * 0.2f, s * 0.5f)
            lineTo(s * 0.2f, s * 0.4f)
            quadraticTo(s * 0.2f, s * 0.28f, s * 0.32f, s * 0.28f)
            lineTo(s * 0.78f, s * 0.28f)
            moveTo(s * 0.8f, s * 0.5f)
            lineTo(s * 0.8f, s * 0.6f)
            quadraticTo(s * 0.8f, s * 0.72f, s * 0.68f, s * 0.72f)
            lineTo(s * 0.22f, s * 0.72f)
        }
        drawPath(loop, color, style = stroke)
        // Arrow heads.
        val headTop = Path().apply {
            moveTo(s * 0.68f, s * 0.16f); lineTo(s * 0.82f, s * 0.28f); lineTo(s * 0.68f, s * 0.4f)
        }
        val headBottom = Path().apply {
            moveTo(s * 0.32f, s * 0.6f); lineTo(s * 0.18f, s * 0.72f); lineTo(s * 0.32f, s * 0.84f)
        }
        drawPath(headTop, color, style = stroke)
        drawPath(headBottom, color, style = stroke)
        if (one) drawLine(color, Offset(s * 0.5f, s * 0.42f), Offset(s * 0.5f, s * 0.6f), w, StrokeCap.Round)
    }
}

/** Three bars: a drag handle. */
@Composable
fun GripIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        for (y in listOf(0.3f, 0.5f, 0.7f)) drawLine(color, Offset(s * 0.2f, s * y), Offset(s * 0.8f, s * y), s * 0.08f, StrokeCap.Round)
    }
}

/** A round minus: remove a row in edit mode. */
@Composable
fun MinusCircleIcon(size: Dp, color: Color = TetsubiColors.red, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        drawCircle(color, s * 0.46f, Offset(s / 2, s / 2))
        drawLine(Color.White, Offset(s * 0.28f, s * 0.5f), Offset(s * 0.72f, s * 0.5f), s * 0.1f, StrokeCap.Round)
    }
}
