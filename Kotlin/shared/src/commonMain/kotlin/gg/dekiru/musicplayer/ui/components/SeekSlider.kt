package gg.dekiru.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.ui.TetsubiColors

/**
 * The seek slider of the redesign: a thin 4 dp track (white 12%) with blue progress and a small
 * round thumb (12 dp, 16 dp while hovered, dragged, pressed or focused) instead of Material's tall bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeekSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.collectIsDraggedAsState().value || interaction.collectIsHoveredAsState().value ||
        interaction.collectIsPressedAsState().value || interaction.collectIsFocusedAsState().value
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        modifier = modifier,
        thumb = {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (active) 16.dp else 12.dp).clip(CircleShape).background(TetsubiColors.textPrimary))
            }
        },
        track = { state ->
            val fraction = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(TetsubiColors.hairline)) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(TetsubiColors.blue))
            }
        },
    )
}
