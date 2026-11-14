package gg.dekiru.musicplayer.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

// The Library's small drawn icons (as the SF Symbols on iOS).

/** Three sliders ("slider.horizontal.3"): the View, Sort and Filter menu. */
@Composable
fun SlidersIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val line = s * 0.08f
        for ((i, knob) in listOf(0.68f, 0.32f, 0.55f).withIndex()) {
            val y = s * (0.22f + i * 0.28f)
            drawLine(color, Offset(s * 0.08f, y), Offset(s * 0.92f, y), line, StrokeCap.Round)
            drawCircle(color, s * 0.1f, Offset(s * knob, y))
        }
    }
}

/** Four squares ("square.grid.2x2"). */
@Composable
fun GridIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val cell = s * 0.38f
        for (x in listOf(0.08f, 0.54f)) for (y in listOf(0.08f, 0.54f)) {
            drawRoundRect(color, Offset(s * x, s * y), Size(cell, cell), CornerRadius(s * 0.08f), style = Stroke(s * 0.08f))
        }
    }
}

/** Dots and lines ("list.bullet"). */
@Composable
fun ListIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        for (i in 0..2) {
            val y = s * (0.22f + i * 0.28f)
            drawCircle(color, s * 0.07f, Offset(s * 0.12f, y))
            drawLine(color, Offset(s * 0.32f, y), Offset(s * 0.92f, y), s * 0.08f, StrokeCap.Round)
        }
    }
}

/** Two crossing arrows ("shuffle"). */
@Composable
fun ShuffleIcon(size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val stroke = Stroke(s * 0.08f, cap = StrokeCap.Round)
        val down = Path().apply {
            moveTo(s * 0.08f, s * 0.25f); cubicTo(s * 0.45f, s * 0.25f, s * 0.5f, s * 0.75f, s * 0.86f, s * 0.75f)
        }
        val up = Path().apply {
            moveTo(s * 0.08f, s * 0.75f); cubicTo(s * 0.45f, s * 0.75f, s * 0.5f, s * 0.25f, s * 0.86f, s * 0.25f)
        }
        drawPath(down, color, style = stroke)
        drawPath(up, color, style = stroke)
        for (y in listOf(0.25f, 0.75f)) {
            drawLine(color, Offset(s * 0.92f, s * y), Offset(s * 0.78f, s * (y - 0.12f)), s * 0.08f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.92f, s * y), Offset(s * 0.78f, s * (y + 0.12f)), s * 0.08f, StrokeCap.Round)
        }
    }
}
