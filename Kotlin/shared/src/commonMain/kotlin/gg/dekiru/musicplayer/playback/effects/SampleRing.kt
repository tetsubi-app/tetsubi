package gg.dekiru.musicplayer.playback.effects

import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/**
 * The most recent audio (one channel) for the visualiser, handed from the
 * audio thread to the screen without a lock: the audio thread never waits
 * for the screen. One writer (the audio thread) and any number of readers.
 *
 * The audio is processed a little before it's heard (the player keeps a
 * buffer of up to about ¾ s), so each written block is stamped with its
 * place in the stream ("presentation time"). The reader asks for the
 * samples at the time being heard, so the bars move with the music rather
 * than ahead of it.
 *
 * If the writer laps the reader mid-copy, that frame of bars is slightly
 * off; that's fine for a visualiser and keeps the audio thread free.
 */
class SampleRing(capacity: Int = 1 shl 17, private val markCount: Int = 512) {
    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) { "capacity must be a power of two" }
    }

    private val samples = FloatArray(capacity)
    private val mask = capacity - 1

    // Block stamps: where each block starts (in samples written), its time and sample rate.
    private val markStart = LongArray(markCount)
    private val markTimeUs = LongArray(markCount)
    private val markRate = IntArray(markCount)

    /** Samples written so far (published after the samples themselves). */
    @Volatile
    private var written = 0L

    /** Stamps written so far. */
    @Volatile
    private var marks = 0L

    /** The writer's position while a block is being written. */
    private var pending = 0L

    /** The sample rate of the latest block (0 before any). */
    @Volatile
    var sampleRate = 0
        private set

    // Writer (audio thread)

    /** Starts a block of audio at [timeUs] (presentation time; `null`: not known). */
    fun beginBlock(sampleRate: Int, timeUs: Long?) {
        pending = written
        if (sampleRate != this.sampleRate) this.sampleRate = sampleRate
        if (timeUs != null) {
            val k = (marks % markCount).toInt()
            markStart[k] = pending
            markTimeUs[k] = timeUs
            markRate[k] = sampleRate
            marks += 1
        }
    }

    /** Adds one sample to the block. */
    fun put(sample: Float) {
        samples[(pending and mask.toLong()).toInt()] = sample
        pending++
    }

    /** Makes the block visible to readers. */
    fun endBlock() {
        written = pending
    }

    // Reader (screen)

    /**
     * Copies the `out.size` samples that end at [atTimeUs] (the time being
     * heard) into [out]; with no time, or no stamp for it, the newest ones.
     * False until there are enough samples.
     */
    fun read(out: FloatArray, atTimeUs: Long? = null): Boolean {
        val n = out.size
        val w = written
        if (n > samples.size || w < n) return false
        val oldestKept = max(0L, w - samples.size + n)
        var end = w
        if (atTimeUs != null) {
            end = endAt(atTimeUs, w, oldestKept) ?: w
        }
        end = end.coerceIn(max(oldestKept, n.toLong()), w)
        val start = end - n
        for (i in 0 until n) out[i] = samples[((start + i) and mask.toLong()).toInt()]
        return true
    }

    /** Where the samples heard at [timeUs] end, from the newest stamp at or before it. */
    private fun endAt(timeUs: Long, w: Long, oldestKept: Long): Long? {
        val m = marks
        // The oldest slot may be being overwritten: skip it.
        val oldestMark = max(0L, m - markCount + 1)
        var nextStart = w
        var i = m - 1
        while (i >= oldestMark) {
            val k = (i % markCount).toInt()
            val start = markStart[k]
            if (start < oldestKept) return null
            val t = markTimeUs[k]
            if (t <= timeUs) {
                val into = (timeUs - t) * markRate[k] / 1_000_000
                return min(nextStart, start + into)
            }
            nextStart = start
            i--
        }
        // Everything stamped is still to come (just after a seek): the start of it.
        return if (m > 0) nextStart else null
    }
}
