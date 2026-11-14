package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.ui.TetsubiColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.hypot

/** How long the pulses run before the outline goes still (a load that long is probably stuck). */
private const val ANIMATION_LIMIT_MILLIS = 20_000L

/** Seconds per lap, in milliseconds. */
private const val PERIOD_MILLIS = 2_000L

/**
 * A faint rounded outline with a pink and a blue pulse chasing each other
 * round it (as `LoadingOutline` on iOS). The phase comes from the frame
 * clock, so every outline on screen moves in step. Still after 20 s.
 */
@Composable
fun LoadingOutline(cornerRadius: Dp, modifier: Modifier = Modifier) {
    var frameMillis by remember { mutableLongStateOf(0L) }
    var expired by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (isActive) {
            val now = withFrameMillis { it }
            frameMillis = now
            if (now - start > ANIMATION_LIMIT_MILLIS) {
                expired = true
                break
            }
            delay(33) // ~30 fps is plenty for a loading hint
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val radius = cornerRadius.toPx()
        val line = 2.dp.toPx()
        val faint = 1.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.18f),
            topLeft = Offset(faint / 2, faint / 2),
            size = size.copy(size.width - faint, size.height - faint),
            cornerRadius = CornerRadius(radius),
            style = Stroke(faint),
        )
        if (expired) return@Canvas

        // The ring the pulses travel in: outer rounded rect minus inner.
        val ring = Path().apply {
            op(
                Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) },
                Path().apply {
                    addRoundRect(RoundRect(line, line, size.width - line, size.height - line, CornerRadius((radius - line).coerceAtLeast(0f))))
                },
                PathOperation.Difference,
            )
        }
        val angle = (frameMillis % PERIOD_MILLIS) / PERIOD_MILLIS.toFloat() * 360f
        clipPath(ring) {
            rotate(angle) {
                drawCircle(brush = pulses(center), radius = hypot(size.width, size.height), center = center)
            }
        }
    }
}

/** Two comets half a lap apart, each fading in from its tail to a bright head. */
private fun pulses(center: Offset) = Brush.sweepGradient(
    0.0f to Color.Transparent,
    0.25f to TetsubiColors.pink.copy(alpha = 0f),
    0.5f to TetsubiColors.pink,
    0.5001f to Color.Transparent,
    0.75f to TetsubiColors.blue.copy(alpha = 0f),
    1.0f to TetsubiColors.blue,
    center = center,
)
