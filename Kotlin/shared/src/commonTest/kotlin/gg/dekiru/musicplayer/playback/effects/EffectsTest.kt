package gg.dekiru.musicplayer.playback.effects

import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.ui.settings.EqualizerModel
import gg.dekiru.musicplayer.ui.settings.EqualizerText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqualizerTest {

    @Test
    fun presetsMatchIosInNameGainsAndOrder() {
        assertEquals(
            listOf("Flat", "Rock", "Bass Boost", "Treble Boost", "Vocal", "Loudness", "Custom"),
            EqualizerPreset.entries.map { it.displayName },
        )
        assertEquals(
            listOf("flat", "rock", "bassBoost", "trebleBoost", "vocal", "loudness", "custom"),
            EqualizerPreset.entries.map { it.key },
        )
        assertEquals(List(10) { 0f }, EqualizerPreset.FLAT.gains)
        assertEquals(listOf(5f, 4f, 3f, 0f, -2f, -2f, 0f, 3f, 4f, 5f), EqualizerPreset.ROCK.gains)
        assertEquals(listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f), EqualizerPreset.BASS_BOOST.gains)
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f), EqualizerPreset.TREBLE_BOOST.gains)
        assertEquals(listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f), EqualizerPreset.VOCAL.gains)
        assertEquals(listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f), EqualizerPreset.LOUDNESS.gains)
        assertNull(EqualizerPreset.CUSTOM.gains)
    }

    @Test
    fun bandsAsIos() {
        assertEquals(listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f), Equalizer.frequencies)
        assertEquals(listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"), Equalizer.frequencies.map(Equalizer::label))
        assertEquals(-12f..12f, Equalizer.gainRange)
    }

    @Test
    fun zeroDecibelsPassesThrough() {
        val filter = Biquad.peaking(1000f, 0f, Equalizer.BAND_Q, 44_100f)
        assertTrue(filter.isPassThrough)
        assertEquals(1f, filter.magnitude(1000f, 44_100f), 1e-6f)
        // A band too high for the sample rate is left out too.
        assertTrue(Biquad.peaking(16_000f, 6f, Equalizer.BAND_Q, 32_000f).isPassThrough)
    }

    @Test
    fun magnitudeAtTheCentreIsTheGain() {
        for (rate in listOf(44_100f, 48_000f)) {
            for (hz in Equalizer.frequencies) {
                for (gain in listOf(-12f, -3f, 6f, 12f)) {
                    val filter = Biquad.peaking(hz, gain, Equalizer.BAND_Q, rate)
                    val expected = 10f.pow(gain / 20)
                    assertEquals(expected, filter.magnitude(hz, rate), expected * 0.01f, "$hz Hz at $gain dB, $rate Hz")
                }
            }
        }
        // Far from the centre, a narrow band does little.
        assertEquals(1f, Biquad.peaking(1000f, 12f, Equalizer.BAND_Q, 44_100f).magnitude(31f, 44_100f), 0.05f)
    }

    @Test
    fun flatChainLeavesSamplesAlone() {
        val chain = EqualizerChain().apply { configure(Equalizer.flatGains, 44_100, 2) }
        assertTrue(chain.isFlat)
        val samples = FloatArray(512) { sin(it * 0.1f) * 0.5f }
        val before = samples.copyOf()
        chain.process(samples, 256)
        assertContentEquals(before, samples)
    }

    /** Peak of a sine at [hz] after the chain, once it has settled. */
    private fun peakAfter(chain: EqualizerChain, hz: Float, rate: Int = 44_100): Float {
        val frames = rate / 2
        val samples = FloatArray(frames * 2) { i -> 0.25f * sin(2 * PI * hz * (i / 2) / rate).toFloat() }
        chain.process(samples, frames)
        return (frames / 2 until frames).maxOf { abs(samples[it * 2]) }
    }

    @Test
    fun boostIsHeardAndPeaksAreLowered() {
        val gains = Equalizer.flatGains.toMutableList().also { it[5] = 6f } // +6 dB at 1 kHz
        val chain = EqualizerChain().apply { configure(gains, 44_100, 2) }
        assertEquals(10f.pow(-6f / 20), chain.preGain, 1e-4f)
        // At 1 kHz the boost and the lowered level cancel out; at 125 Hz only the lowering is left.
        assertEquals(0.25f, peakAfter(chain, 1000f), 0.01f)
        chain.reset()
        assertEquals(0.25f * 10f.pow(-6f / 20), peakAfter(chain, 125f), 0.01f)
    }

    @Test
    fun sixteenBitAudioIsFilteredAndClipped() {
        val gains = Equalizer.flatGains.toMutableList().also { it[5] = -12f }
        val chain = EqualizerChain().apply { configure(gains, 44_100, 1) }
        val frames = 22_050
        val samples = ShortArray(frames) { i -> (16_000 * sin(2 * PI * 1000 * i / 44_100)).toInt().toShort() }
        chain.process16(samples, frames)
        val peak = (frames / 2 until frames).maxOf { abs(samples[it].toInt()) }
        assertEquals(16_000 * 10f.pow(-12f / 20), peak.toFloat(), 300f)
        assertEquals(32_767, EqualizerChain.toPcm16(2f).toInt())
        assertEquals(-32_768, EqualizerChain.toPcm16(-2f).toInt())
    }
}

class SpectrumTest {

    private fun sine(hz: Float, rate: Float, amplitude: Float = 1f) =
        FloatArray(Spectrum.FFT_SIZE) { amplitude * sin(2 * PI * hz * it / rate).toFloat() }

    /** The bar whose band holds [hz]. */
    private fun barFor(hz: Float) = (0 until Spectrum.BAR_COUNT).first { Spectrum.edge(it + 1, Spectrum.BAR_COUNT) > hz }

    @Test
    fun aSineShowsInItsBar() {
        val analyser = SpectrumAnalyser()
        val bars = FloatArray(Spectrum.BAR_COUNT)
        for (hz in listOf(200f, 1000f, 5000f)) {
            // Quiet enough that neighbouring bars don't all reach the top.
            analyser.analyse(sine(hz, 44_100f, amplitude = 0.05f), 44_100f, bars)
            val peak = bars.indices.maxBy { bars[it] }
            assertEquals(barFor(hz), peak, "$hz Hz")
            // Bars well away from it stay low.
            assertTrue(bars[0] < 0.6f * bars[peak] && bars[Spectrum.BAR_COUNT - 1] < 0.6f * bars[peak])
            // A full-scale sine (nearly) fills its bar; wide high bars average it with quieter bins.
            analyser.analyse(sine(hz, 44_100f), 44_100f, bars)
            assertTrue(bars[barFor(hz)] > 0.8f, "$hz Hz full scale")
        }
    }

    @Test
    fun barsMatchTheIphone() {
        // Worked out with the iOS code (Accelerate) for a 1 kHz sine at 0.05 of full scale, 44.1 kHz.
        val ios = FloatArray(Spectrum.BAR_COUNT).also { it[16] = 0.3626f; it[17] = 0.5816f }
        val bars = FloatArray(Spectrum.BAR_COUNT)
        SpectrumAnalyser().analyse(sine(1000f, 44_100f, amplitude = 0.05f), 44_100f, bars)
        for (i in bars.indices) assertEquals(ios[i], bars[i], 0.002f, "bar $i")
    }

    @Test
    fun silenceGivesNoBars() {
        val bars = FloatArray(Spectrum.BAR_COUNT) { 1f }
        SpectrumAnalyser().analyse(FloatArray(Spectrum.FFT_SIZE), 48_000f, bars)
        assertTrue(bars.all { it == 0f })
    }

    @Test
    fun barsAreLogSpacedFrom40HzTo16kHz() {
        assertEquals(40f, Spectrum.edge(0, 32), 0.01f)
        assertEquals(16_000f, Spectrum.edge(32, 32), 1f)
        assertEquals(sqrtOf(40f * 16_000f), Spectrum.edge(16, 32), 1f)
    }

    private fun sqrtOf(x: Float) = kotlin.math.sqrt(x)

    @Test
    fun barsRiseAtOnceAndFallGently() {
        val levels = SmoothedLevels(2)
        levels.update(floatArrayOf(1f, 0.5f), 0f)
        assertContentEquals(floatArrayOf(1f, 0.5f), levels.values)
        levels.update(floatArrayOf(0f, 0f), 0.1f)
        assertEquals(0.85f, levels.values[0], 1e-5f)
        assertEquals(0.35f, levels.values[1], 1e-5f)
        // A long gap counts as 0.1 s at most.
        levels.update(null, 5f)
        assertEquals(0.7f, levels.values[0], 1e-5f)
        assertFalse(levels.isSilent)
        repeat(10) { levels.update(null, 0.1f) }
        assertTrue(levels.isSilent)
    }
}

class SampleRingTest {

    private fun SampleRing.block(rate: Int, timeUs: Long?, values: List<Float>) {
        beginBlock(rate, timeUs)
        values.forEach(::put)
        endBlock()
    }

    @Test
    fun nothingToReadUntilThereAreEnoughSamples() {
        val ring = SampleRing(capacity = 16, markCount = 8)
        val out = FloatArray(4)
        assertFalse(ring.read(out))
        ring.block(1000, null, listOf(1f, 2f, 3f))
        assertFalse(ring.read(out))
        ring.block(1000, null, listOf(4f))
        assertTrue(ring.read(out))
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f), out)
    }

    @Test
    fun readsTheSamplesBeingHeardNotTheNewest() {
        // 1000 samples a second: a block of 10 samples lasts 10 ms.
        val ring = SampleRing(capacity = 64, markCount = 8)
        ring.block(1000, 0, List(10) { it.toFloat() })
        ring.block(1000, 10_000, List(10) { 10f + it })
        ring.block(1000, 20_000, List(10) { 20f + it })
        val out = FloatArray(4)
        // At 15 ms, the samples heard are 11…14.
        assertTrue(ring.read(out, atTimeUs = 15_000))
        assertContentEquals(floatArrayOf(11f, 12f, 13f, 14f), out)
        // No time: the newest.
        ring.read(out)
        assertContentEquals(floatArrayOf(26f, 27f, 28f, 29f), out)
        // Past what's been written: the newest.
        ring.read(out, atTimeUs = 90_000)
        assertContentEquals(floatArrayOf(26f, 27f, 28f, 29f), out)
        assertEquals(1000, ring.sampleRate)
    }

    @Test
    fun wrapsRoundWithoutLosingOrder() {
        val ring = SampleRing(capacity = 8, markCount = 4)
        repeat(5) { b -> ring.block(1000, null, List(3) { (b * 3 + it).toFloat() }) }
        val out = FloatArray(4)
        ring.read(out)
        assertContentEquals(floatArrayOf(11f, 12f, 13f, 14f), out)
    }
}

class EffectsSettingsTest {

    @Test
    fun defaultsAreOff() {
        val settings = EffectsSettings(InMemoryPreferences())
        assertFalse(settings.equalizerEnabled)
        assertEquals(Equalizer.flatGains, settings.equalizerGains)
        assertEquals(EqualizerPreset.FLAT, settings.equalizerPreset)
        assertFalse(settings.showVisualiser)
        assertEquals("Off", settings.equalizerSummary)
    }

    @Test
    fun settingsPersistUnderTheIosKeys() {
        val prefs = InMemoryPreferences()
        EffectsSettings(prefs).apply {
            equalizerEnabled = true
            equalizerGains = listOf(1.5f, -2f, 0f, 0f, 0f, 0f, 0f, 0f, 3f, 20f)
            equalizerPreset = EqualizerPreset.BASS_BOOST
            showVisualiser = true
        }
        val again = EffectsSettings(prefs)
        assertTrue(again.equalizerEnabled)
        assertEquals(listOf(1.5f, -2f, 0f, 0f, 0f, 0f, 0f, 0f, 3f, 12f), again.equalizerGains)
        assertEquals(EqualizerPreset.BASS_BOOST, again.equalizerPreset)
        assertTrue(again.showVisualiser)
        assertEquals("Bass Boost", again.equalizerSummary)
        assertEquals("true", prefs.getString("playback.eqEnabled"))
        assertEquals("bassBoost", prefs.getString("playback.eqPreset"))
        assertEquals("true", prefs.getString("nowPlaying.showVisualiser"))
    }

    @Test
    fun badSavedValuesFallBackToFlat() {
        val prefs = InMemoryPreferences()
        prefs.putString("playback.eqGains", "1,2,3")
        prefs.putString("playback.eqPreset", "jazz")
        val settings = EffectsSettings(prefs)
        assertEquals(Equalizer.flatGains, settings.equalizerGains)
        assertEquals(EqualizerPreset.FLAT, settings.equalizerPreset)
    }

    @Test
    fun savedEqualizerGoesIntoTheSound() {
        val prefs = InMemoryPreferences()
        EffectsSettings(prefs).apply {
            equalizerEnabled = true
            equalizerGains = EqualizerPreset.ROCK.gains!!
        }
        val effects = AudioEffects()
        EffectsSettings(prefs).applyTo(effects)
        assertTrue(effects.equalizer.enabled)
        assertEquals(EqualizerPreset.ROCK.gains, effects.equalizer.gains)
    }
}

class EqualizerModelTest {

    @Test
    fun choicesApplyStraightAwayAndAreSaved() {
        val prefs = InMemoryPreferences()
        val effects = AudioEffects()
        val model = EqualizerModel(EffectsSettings(prefs), effects)
        assertEquals("Off", model.summary)

        model.changeEnabled(true)
        model.choosePreset(EqualizerPreset.VOCAL)
        assertEquals(EqualizerPreset.VOCAL.gains, model.gains)
        assertEquals("Vocal", model.summary)
        assertTrue(effects.equalizer.enabled)
        assertEquals(EqualizerPreset.VOCAL.gains, effects.equalizer.gains)

        // Moving a slider switches to Custom, in 0.5 dB steps within ±12 dB.
        val version = effects.equalizer.version
        model.changeGain(0, 3.3f)
        assertEquals(EqualizerPreset.CUSTOM, model.preset)
        assertEquals(3.5f, model.gains[0])
        assertTrue(effects.equalizer.version > version)
        model.changeGain(1, 40f)
        assertEquals(12f, model.gains[1])

        // Custom keeps the sliders.
        model.choosePreset(EqualizerPreset.CUSTOM)
        assertEquals(3.5f, model.gains[0])

        val saved = EffectsSettings(prefs)
        assertTrue(saved.equalizerEnabled)
        assertEquals(EqualizerPreset.CUSTOM, saved.equalizerPreset)
        assertEquals(model.gains, saved.equalizerGains)

        model.changeEnabled(false)
        assertFalse(effects.equalizer.enabled)
        assertEquals("Off", model.summary)
    }

    @Test
    fun gainLabelsAsIos() {
        assertEquals("+3", EqualizerText.gainLabel(3f))
        assertEquals("0", EqualizerText.gainLabel(0f))
        assertEquals("-2", EqualizerText.gainLabel(-2f))
    }
}
