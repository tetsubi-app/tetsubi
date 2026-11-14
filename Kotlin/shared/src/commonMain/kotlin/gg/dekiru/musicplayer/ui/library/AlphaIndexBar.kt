package gg.dekiru.musicplayer.ui.library

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.ui.TetsubiColors

/** The strip's width; the list next to it leaves this much room. */
val AlphaIndexBarWidth = 22.dp

/**
 * A vertical A–Z strip (as `AlphaIndexBar` on iOS). Tap or drag to jump;
 * letters with nothing under them are dimmed.
 */
@Composable
fun AlphaIndexBar(available: Set<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val select by rememberUpdatedState(onSelect)
    BoxWithConstraints(
        modifier.width(AlphaIndexBarWidth).fillMaxHeight().padding(vertical = 8.dp)
            .semantics { contentDescription = "Alphabetical index" },
    ) {
        val labels = AlphaIndex.labels
        val cell = maxHeight / labels.size
        val heightPx = constraints.maxHeight.toFloat()
        Column(
            Modifier.fillMaxHeight().fillMaxWidth().pointerInput(heightPx) {
                // Each label once while the finger stays on it.
                fun labelAt(y: Float) = labels[(y / (heightPx / labels.size)).toInt().coerceIn(0, labels.size - 1)]
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var last = labelAt(down.position.y)
                    select(last)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val label = labelAt(change.position.y)
                        if (label != last) {
                            last = label
                            select(label)
                        }
                    }
                }
            },
        ) {
            val fontSize = (cell.value * 0.7f).coerceIn(7f, 11f).sp
            for (label in labels) {
                Text(
                    label,
                    color = if (label in available) TetsubiColors.blue else Color.White.copy(alpha = 0.25f),
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().height(cell).wrapContentHeight(),
                )
            }
        }
    }
}
