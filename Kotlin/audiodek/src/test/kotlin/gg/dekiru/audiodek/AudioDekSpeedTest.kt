package gg.dekiru.audiodek

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Decoding 60 s of 44.1 kHz / 16-bit stereo must take far less than real time. */
class AudioDekSpeedTest {

    private fun speed(name: String): Double {
        val mp4 = Mp4AlacSamples(javaClass.getResourceAsStream("/alac/$name")!!.use { it.readBytes() })
        val config = AudioDekAlacConfig.parse(mp4.cookie)
        val decoder = AudioDekAlacDecoder(config)
        val out = IntArray(decoder.maxSamples)
        val frames = (0 until mp4.samples.size).map { mp4.frame(it) }
        val target = 44100L * 60
        fun run(): Pair<Long, Int> {
            var decoded = 0L
            var checksum = 0
            val t0 = System.nanoTime()
            while (decoded < target) for (f in frames) {
                val n = decoder.decode(f, 0, f.size, out)
                decoded += n
                checksum += out[n - 1]
            }
            return (System.nanoTime() - t0) to checksum
        }
        run() // warm up the JIT
        val (nanos, _) = run()
        val seconds = nanos / 1e9
        val times = 60.0 / seconds
        println("audioDEK speed: $name decoded 60 s of audio in ${"%.3f".format(seconds)} s (${"%.0f".format(times)}x real time)")
        return times
    }

    @Test
    fun sixtySecondsOfMusicDecodeFarFasterThanRealTime() {
        // At least 20x real time (a phone CPU is slower than the build machine, but far above 1x).
        assertTrue(speed("music_16_st_44k.afc.m4a") > 20)
        assertTrue(speed("music_16_st_44k.ff.m4a") > 20)
    }

    @Test
    fun sixtySecondsOfNoiseDecodeFarFasterThanRealTime() {
        assertTrue(speed("noise_16_st_44k.afc.m4a") > 20)
    }

    @Test
    fun decodingTwiceGivesTheSameSamples() {
        val mp4 = Mp4AlacSamples(javaClass.getResourceAsStream("/alac/music_16_st_44k.afc.m4a")!!.use { it.readBytes() })
        val decoder = AudioDekAlacDecoder(AudioDekAlacConfig.parse(mp4.cookie))
        val a = IntArray(decoder.maxSamples)
        val b = IntArray(decoder.maxSamples)
        decoder.decode(mp4.frame(0), 0, mp4.samples[0].second, a)
        decoder.decode(mp4.frame(1), 0, mp4.samples[1].second, b)
        decoder.reset()
        val c = IntArray(decoder.maxSamples)
        decoder.decode(mp4.frame(0), 0, mp4.samples[0].second, c)
        assertContentEquals(a, c)
    }
}
