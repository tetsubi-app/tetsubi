package gg.dekiru.musicplayer.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.playback.effects.AudioEffects
import gg.dekiru.musicplayer.playback.effects.EffectsSettings
import gg.dekiru.musicplayer.playback.effects.Equalizer
import gg.dekiru.musicplayer.playback.effects.EqualizerPreset
import gg.dekiru.musicplayer.ui.TetsubiColors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** The equaliser screen's texts, worded as iOS `EqualizerView`. */
object EqualizerText {
    const val TITLE = "Equaliser"
    const val PRESET = "Preset"
    const val BANDS = "Bands (dB)"
    const val OFF = "Off"

    // iOS goes on: "While the equaliser is on, there's a short pause between tracks — turn it off for
    // gapless playback. The equaliser doesn't apply when playing over AirPlay." Neither is true here:
    // gapless stays on, and there's no AirPlay (Cast has its own line, shown while casting).
    const val FOOTER = "Changes apply straight away. When bands are boosted the overall level is lowered to avoid distortion."

    /** Shown while casting: the Cast device decodes the music itself. The settings stay as they are. */
    const val CASTING = "The equaliser doesn't apply while casting."

    /** A band's gain over its slider: "+3", "0", "-2". */
    fun gainLabel(gain: Float): String {
        // Halves round away from zero, as Swift's `rounded()`.
        val rounded = (if (gain < 0) -floor(-gain + 0.5f) else floor(gain + 0.5f)).toInt()
        return if (gain > 0) "+$rounded" else "$rounded"
    }
}

/**
 * The equaliser screen's state and actions (as iOS `EqualizerView`). Every
 * change is saved and goes straight into the sound through [effects]
 * (null in tests). Moving a slider switches the preset to Custom; choosing
 * a preset sets the sliders (Custom keeps them as they are).
 */
class EqualizerModel(private val settings: EffectsSettings, private val effects: AudioEffects?) {
    var enabled by mutableStateOf(settings.equalizerEnabled)
        private set
    var preset by mutableStateOf(settings.equalizerPreset)
        private set
    var gains by mutableStateOf(settings.equalizerGains)
        private set

    /** The Settings row's value: "Off" or the preset's name. */
    val summary: String get() = if (enabled) preset.displayName else EqualizerText.OFF

    fun changeEnabled(on: Boolean) {
        enabled = on
        apply()
    }

    fun choosePreset(choice: EqualizerPreset) {
        preset = choice
        settings.equalizerPreset = choice
        choice.gains?.let { gains = it }
        apply()
    }

    /** Sets band [band] to [gain] dB, in 0.5 dB steps within ±12 dB. */
    fun changeGain(band: Int, gain: Float) {
        val snapped = ((gain * 2).roundToInt() / 2f).coerceIn(Equalizer.gainRange.start, Equalizer.gainRange.endInclusive)
        if (band !in gains.indices || gains[band] == snapped) return
        gains = gains.toMutableList().also { it[band] = snapped }
        if (preset != EqualizerPreset.CUSTOM) {
            preset = EqualizerPreset.CUSTOM
            settings.equalizerPreset = EqualizerPreset.CUSTOM
        }
        apply()
    }

    private fun apply() {
        settings.equalizerEnabled = enabled
        settings.equalizerGains = gains
        effects?.setEqualizer(enabled, gains)
    }
}

private val dimText = Color.White.copy(alpha = 0.6f)

/**
 * Settings ▸ Equaliser (as iOS `EqualizerView`): the on/off switch, the
 * preset menu, and ten vertical sliders under "Bands (dB)". The sliders
 * and preset are dimmed and locked while the equaliser is off.
 */
@Composable
fun EqualizerScreen(model: EqualizerModel, onBack: () -> Unit, casting: Boolean = false) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                    Text("‹ Back", color = TetsubiColors.blue, fontSize = 16.sp)
                }
                Text(EqualizerText.TITLE, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(8.dp))
                Group {
                    ToggleRow(EqualizerText.TITLE, model.enabled, model::changeEnabled)
                    Divider()
                    PickerRow(
                        EqualizerText.PRESET,
                        value = model.preset.displayName,
                        options = EqualizerPreset.entries.map { it.displayName },
                        onSelect = { model.choosePreset(EqualizerPreset.entries[it]) },
                        enabled = model.enabled,
                    )
                }
                Footer(EqualizerText.FOOTER)
                if (casting) Footer(EqualizerText.CASTING)

                Header(EqualizerText.BANDS)
                Group {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp).alpha(if (model.enabled) 1f else 0.4f),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Equalizer.frequencies.forEachIndexed { i, hz ->
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val gain = model.gains[i]
                                Text(EqualizerText.gainLabel(gain), color = dimText, fontSize = 11.sp)
                                VerticalSlider(
                                    gain, onChange = { model.changeGain(i, it) }, enabled = model.enabled,
                                    description = "${Equalizer.label(hz)} hertz",
                                    modifier = Modifier.fillMaxWidth().height(180.dp),
                                )
                                Text(Equalizer.label(hz), color = dimText, fontSize = 11.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
            }
        }
    }
}

/**
 * A vertical slider for one band (as iOS `VerticalSlider`): the fill grows
 * up or down from the 0 dB line; touch or drag anywhere on it to set the
 * value. Screen readers adjust it in 1 dB steps.
 */
@Composable
private fun VerticalSlider(value: Float, onChange: (Float) -> Unit, enabled: Boolean, description: String, modifier: Modifier = Modifier) {
    val range = Equalizer.gainRange
    val span = range.endInclusive - range.start
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .semantics {
                contentDescription = description
                stateDescription = "${(value * 10).roundToInt() / 10.0} decibels"
                progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps = (span * 2).toInt() - 1)
                if (enabled) {
                    setProgress { target ->
                        change(target)
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun valueAt(y: Float): Float {
                    val fraction = 1 - (y / size.height).coerceIn(0f, 1f)
                    return range.start + fraction * span
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    change(valueAt(down.position.y))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                        change(valueAt(pointer.position.y))
                        pointer.consume()
                    }
                }
            },
    ) {
        val height = size.height
        val y = { v: Float -> (1 - (v - range.start) / span) * height }
        val track = 4.dp.toPx()
        val x = size.width / 2
        val radius = CornerRadius(track / 2)
        drawRoundRect(Color.White.copy(alpha = 0.15f), Offset(x - track / 2, 0f), Size(track, height), radius)
        // Fill from the 0 dB line to the value.
        val top = minOf(y(value), y(0f))
        drawRoundRect(TetsubiColors.pink, Offset(x - track / 2, top), Size(track, abs(y(value) - y(0f))), radius)
        drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(x, y(value)))
    }
}
