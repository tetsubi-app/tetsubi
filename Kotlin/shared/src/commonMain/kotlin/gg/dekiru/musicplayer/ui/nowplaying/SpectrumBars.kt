package gg.dekiru.musicplayer.ui.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.playback.effects.AudioEffects
import gg.dekiru.musicplayer.playback.effects.SmoothedLevels
import gg.dekiru.musicplayer.playback.effects.SpectrumReader
import gg.dekiru.musicplayer.ui.TetsubiColors
import kotlinx.coroutines.delay

/**
 * The visualiser under the art on Now Playing (as iOS `SpectrumView`): 32
 * rounded bars, 3 dp apart, redrawn about 30 times a second while playing.
 * Bars jump up at once and fall back gently. Drawn in [tint] (the art's
 * accent colour, as the progress line), or a pink-to-blue gradient when
 * there is none. While on screen it switches the audio copying on; leaving
 * the screen (or turning the toggle off) switches it off again, and once
 * paused and the bars have settled, the redraws stop.
 */
@Composable
fun SpectrumBars(effects: AudioEffects, isPlaying: Boolean, tint: Color?, modifier: Modifier = Modifier) {
    val reader = remember(effects) { SpectrumReader(effects) }
    val levels = remember { SmoothedLevels() }
    // Bumped every frame; read while drawing, so only the drawing repeats.
    var frame by remember { mutableLongStateOf(0L) }

    DisposableEffect(effects) {
        effects.setVisualizerActive(true)
        onDispose { effects.setVisualizerActive(false) }
    }

    LaunchedEffect(isPlaying, reader) {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val seconds = if (last == 0L) 0f else (now - last) / 1e9f
            last = now
            levels.update(if (isPlaying) reader.read() else null, seconds)
            frame = now
            if (!isPlaying && levels.isSilent) break
            // About 30 frames a second.
            delay(20)
        }
    }

    Canvas(modifier.clearAndSetSemantics {}) {
        // Reading the frame count here redraws on every frame.
        val bars = levels.values.takeIf { frame >= 0 } ?: return@Canvas
        val gap = 3.dp.toPx()
        val width = (size.width - gap * (bars.size - 1)) / bars.size
        if (width <= 0f) return@Canvas
        val radius = CornerRadius(minOf(2.dp.toPx(), width / 2))
        val gradient = if (tint == null) Brush.horizontalGradient(listOf(TetsubiColors.pink, TetsubiColors.blue), endX = size.width) else null
        for ((i, level) in bars.withIndex()) {
            val h = maxOf(2.dp.toPx(), level * size.height)
            val topLeft = Offset(i * (width + gap), size.height - h)
            if (gradient != null) drawRoundRect(gradient, topLeft, Size(width, h), radius)
            else drawRoundRect(tint ?: TetsubiColors.blue, topLeft, Size(width, h), radius)
        }
    }
}

/** Vertical lines of different heights (as SF Symbols "waveform"): the Visualiser toggle. */
@Composable
internal fun WaveformIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val heights = floatArrayOf(0.3f, 0.6f, 0.85f, 0.5f, 0.7f, 0.35f, 0.2f)
        val step = s / (heights.size + 1)
        for ((i, h) in heights.withIndex()) {
            val x = step * (i + 1)
            drawLine(color, Offset(x, s * (0.5f - h / 2)), Offset(x, s * (0.5f + h / 2)), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
        }
    }
}
