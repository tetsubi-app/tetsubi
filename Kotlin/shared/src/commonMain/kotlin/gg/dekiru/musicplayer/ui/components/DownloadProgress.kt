package gg.dekiru.musicplayer.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.ui.TetsubiColors

/**
 * Download progress (as iOS `PulsingProgressBar`): a capsule that pulses
 * between blue and pink; paused or failed, a still, dimmed grey.
 */
@Composable
fun PulsingProgressBar(progress: Double, isPaused: Boolean, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    val shown by animateFloatAsState(progress.toFloat().coerceIn(0f, 1f), tween(400))
    Canvas(
        modifier.fillMaxWidth().height(8.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.toFloat().coerceIn(0f, 1f), 0f..1f) },
    ) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.15f), cornerRadius = radius)
        val color = if (isPaused) Color.White.copy(alpha = 0.45f) else lerp(TetsubiColors.blue.copy(alpha = 0.7f), TetsubiColors.pink, pulse)
        val width = maxOf(8.dp.toPx(), size.width * shown)
        drawRoundRect(color, size = Size(width, size.height), cornerRadius = radius)
    }
}

/** A small progress ring: the album page's Download button while downloading. */
@Composable
fun ProgressRing(progress: Double, size: Dp, color: Color = TetsubiColors.blue, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(progress.toFloat().coerceIn(0f, 1f), tween(400))
    Canvas(modifier.size(size)) {
        val w = this.size.width * 0.14f
        val inset = w / 2
        val arcSize = Size(this.size.width - w, this.size.height - w)
        drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(width = w))
        drawArc(color, -90f, 360f * shown, false, Offset(inset, inset), arcSize, style = Stroke(width = w, cap = StrokeCap.Round))
    }
}
