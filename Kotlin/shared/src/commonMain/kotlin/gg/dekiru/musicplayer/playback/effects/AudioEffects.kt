package gg.dekiru.musicplayer.playback.effects

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * The live equaliser and visualiser shared between the screens and the
 * audio thread (as iOS `AudioEffects`). The screens set the equaliser and
 * switch the visualiser on and off here; the platform's audio processor
 * reads them for every buffer and feeds [samples] while the visualiser is
 * on. Everything the audio thread reads is a single volatile value, so it
 * never waits on a lock and changes take effect from the next buffer.
 * Saving the settings is [EffectsSettings]'s job.
 */
class AudioEffects {

    /** The equaliser as the audio thread sees it. [version] goes up with every change. */
    class EqualizerState(val enabled: Boolean, val gains: List<Float>, val version: Int)

    /** The current equaliser (replaced whole on every change). */
    @Volatile
    var equalizer = EqualizerState(enabled = false, gains = Equalizer.flatGains, version = 0)
        private set

    /** Applies the equaliser from the next buffer. Main thread. */
    fun setEqualizer(enabled: Boolean, gains: List<Float>) {
        equalizer = EqualizerState(enabled, Equalizer.clamp(gains), equalizer.version + 1)
    }

    /** True while a visualiser is on screen: only then are samples copied for it. */
    @Volatile
    var visualizerActive = false
        private set

    /** Turns sample copying on while a visualiser is showing. Main thread. */
    fun setVisualizerActive(active: Boolean) {
        visualizerActive = active
    }

    /** The latest audio, for the visualiser. */
    val samples = SampleRing()

    // Where playback is: reported by the audio output, so the bars follow what's heard.

    private val origin = TimeSource.Monotonic.markNow()

    /** A steady clock in nanoseconds, the same for every thread. */
    fun nowNanos(): Long = origin.elapsedNow().inWholeNanoseconds

    @Volatile
    private var positionUs = Long.MIN_VALUE

    @Volatile
    private var positionAtNanos = 0L

    /** The audio output's position (presentation time, µs), as it reports it. Any thread. */
    fun reportPosition(us: Long) {
        positionAtNanos = nowNanos()
        positionUs = us
    }

    /**
     * The presentation time being heard now, carried forward from the last
     * report (at most 100 ms, so a pause doesn't run on). Null when unknown.
     */
    fun playingPositionUs(): Long? {
        val us = positionUs
        if (us == Long.MIN_VALUE) return null
        val sinceUs = ((nowNanos() - positionAtNanos) / 1_000).coerceIn(0, 100_000)
        return us + sinceUs
    }
}

/**
 * Reads bar levels for the visualiser from [AudioEffects.samples], at the
 * point being heard. One per visualiser on screen; call from its frame loop.
 */
class SpectrumReader(private val effects: AudioEffects) {
    private val analyser = SpectrumAnalyser()
    private val window = FloatArray(Spectrum.FFT_SIZE)
    private val bars = FloatArray(Spectrum.BAR_COUNT)

    /** The latest bar levels (0…1, lowest frequency first), or null before there's any audio. */
    fun read(): FloatArray? {
        val rate = effects.samples.sampleRate
        if (rate <= 0 || !effects.samples.read(window, effects.playingPositionUs())) return null
        analyser.analyse(window, rate.toFloat(), bars)
        return bars
    }
}
