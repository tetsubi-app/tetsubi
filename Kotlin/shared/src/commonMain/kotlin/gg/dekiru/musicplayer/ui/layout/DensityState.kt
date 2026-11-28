package gg.dekiru.musicplayer.ui.layout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.data.settings.Preferences

/** Card size for every grid and shelf (docs/redesign.md section 3): the smallest a card may be, in dp. */
enum class Density(val minCard: Float, val label: String) {
    S(104f, "S"),
    M(148f, "M"),
    L(200f, "L");

    companion object {
        val DEFAULT = M
        fun parse(value: String?): Density = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/**
 * The one remembered density, for every grid. Stored as "S", "M" or "L" under [KEY]. The old
 * `library.gridColumns` (GridZoom) value is not read: the old columns count has no equivalent.
 */
class DensityState(private val preferences: Preferences) {
    private var _density by mutableStateOf(Density.parse(preferences.getString(KEY)))

    var density: Density
        get() = _density
        set(value) {
            _density = value
            preferences.putString(KEY, value.name)
        }

    companion object {
        const val KEY = "ui.density"
    }
}

/** The app's [DensityState]; signed-in code provides it, everything else gets the default. */
val LocalDensityState = staticCompositionLocalOf { DensityState(InMemoryPreferences()) }
