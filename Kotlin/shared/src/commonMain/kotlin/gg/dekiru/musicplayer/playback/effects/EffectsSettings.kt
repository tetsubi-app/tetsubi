package gg.dekiru.musicplayer.playback.effects

import gg.dekiru.musicplayer.data.settings.Preferences

/**
 * The saved equaliser and visualiser settings, with the same keys and
 * defaults as iOS (`PlaybackSettings` and Now Playing's
 * `nowPlaying.showVisualiser`). Changing them here only saves them;
 * [applyTo] (or [AudioEffects.setEqualizer]) puts them into the sound.
 */
class EffectsSettings(private val preferences: Preferences) {

    /** Equaliser on/off. Default off. */
    var equalizerEnabled: Boolean
        get() = preferences.getString(EQ_ENABLED)?.toBooleanStrictOrNull() ?: false
        set(value) = preferences.putString(EQ_ENABLED, value.toString())

    /** Ten band gains in dB (31 Hz … 16 kHz). Default flat. Saved as "0,1.5,…". */
    var equalizerGains: List<Float>
        get() {
            val stored = preferences.getString(EQ_GAINS)?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }.orEmpty()
            return if (stored.size == Equalizer.frequencies.size) Equalizer.clamp(stored) else Equalizer.flatGains
        }
        set(value) = preferences.putString(EQ_GAINS, Equalizer.clamp(value).joinToString(","))

    /** The preset last chosen on the equaliser screen. Default Flat. */
    var equalizerPreset: EqualizerPreset
        get() = EqualizerPreset.fromKey(preferences.getString(EQ_PRESET)) ?: EqualizerPreset.FLAT
        set(value) = preferences.putString(EQ_PRESET, value.key)

    /** Show the spectrum bars under the art on Now Playing. Default off. */
    var showVisualiser: Boolean
        get() = preferences.getString(SHOW_VISUALISER)?.toBooleanStrictOrNull() ?: false
        set(value) = preferences.putString(SHOW_VISUALISER, value.toString())

    /** The Settings row's value: the preset's name, or "Off" (as iOS). */
    val equalizerSummary: String get() = if (equalizerEnabled) equalizerPreset.displayName else "Off"

    /** Puts the saved equaliser into the sound (at launch). */
    fun applyTo(effects: AudioEffects) = effects.setEqualizer(equalizerEnabled, equalizerGains)

    private companion object {
        const val EQ_ENABLED = "playback.eqEnabled"
        const val EQ_GAINS = "playback.eqGains"
        const val EQ_PRESET = "playback.eqPreset"
        const val SHOW_VISUALISER = "nowPlaying.showVisualiser"
    }
}
