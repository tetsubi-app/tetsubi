package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.PlaylistEntry

enum class RepeatMode { OFF, ALL, ONE }

/** What the Now Playing bar, screen, lock screen and Up next need about a queued track. */
data class PlaybackTrack(
    val id: String,
    val trackNumber: Int,
    val title: String,
    val artist: String,
    val album: String,
    /** The album shown (for a multi-disc album, the merged entry; in a playlist queue, the track's own album). */
    val albumId: String,
    val durationSeconds: Double,
    /** Loudness correction in dB ("Normalise volume"). */
    val normalizationGain: Double? = null,
    /** Format and bitrate for Now Playing, e.g. "ALAC 905 kbps"; null if unknown. */
    val qualityLabel: String? = null,
    /** The item whose picture is shown: the track's own when it differs from the album's, else the album. */
    val artItemId: String = albumId,
    /** A downloaded album's picture for this track (its own or `cover.jpg`); used instead of [artItemId] when set. */
    val localArtPath: String? = null,
    /** The file's sample rate in Hz (e.g. 44100); null if unknown (e.g. a download, which doesn't save it). */
    val sampleRate: Int? = null,
    /** The file's bit depth (e.g. 16); null for lossy files or if unknown. */
    val bitDepth: Int? = null,
) {
    /** "44.1kHz/16-bit", "44.1kHz" (no bit depth), or null (sample rate unknown). */
    val sampleRateLabel: String? get() = sampleRateLabel(sampleRate, bitDepth)

    companion object {
        fun from(track: JellyfinTrack, album: JellyfinAlbum, position: Int) = PlaybackTrack(
            id = track.id,
            // Some answers omit IndexNumber: fall back to the position in the queue.
            trackNumber = track.trackNumber.takeIf { it > 0 } ?: (position + 1),
            title = track.displayName,
            artist = track.albumArtists?.firstOrNull()?.name ?: album.artistName,
            album = album.displayName,
            albumId = album.id,
            durationSeconds = track.durationSeconds.toDouble(),
            normalizationGain = track.normalizationGain,
            qualityLabel = qualityLabel(AudioFormat.source(track), track.mediaSource?.bitrate),
            artItemId = if (track.hasOwnArt) track.id else album.id,
            sampleRate = track.mediaSource?.sampleRate,
            bitDepth = track.mediaSource?.bitDepth,
        )

        /**
         * A playlist's track: its own album, album ID and picture, and its
         * own track number; one without a number takes its place in the
         * queue as played (`queueIndex` + 1), as iOS.
         */
        fun from(entry: PlaylistEntry, queueIndex: Int) = PlaybackTrack(
            id = entry.track.id,
            trackNumber = entry.track.trackNumber.takeIf { it > 0 } ?: (queueIndex + 1),
            title = entry.track.displayName,
            artist = entry.albumArtist.orEmpty(),
            album = entry.albumName.orEmpty(),
            albumId = entry.albumId ?: entry.track.id,
            durationSeconds = entry.track.durationSeconds.toDouble(),
            normalizationGain = entry.track.normalizationGain,
            qualityLabel = qualityLabel(AudioFormat.source(entry.track), entry.track.mediaSource?.bitrate),
            artItemId = entry.artItemId,
            sampleRate = entry.track.mediaSource?.sampleRate,
            bitDepth = entry.track.mediaSource?.bitDepth,
        )

        /**
         * A stream the server converts to FLAC: "FLAC (from ALAC 1057 kbps)".
         * The bitrate is the original file's; the converted stream's isn't known.
         */
        fun convertedLabel(source: AudioFormat?, sourceBitrate: Int?): String =
            "${AudioFormat.FLAC.displayName} (from ${qualityLabel(source, sourceBitrate) ?: "original"})"

        /** "ALAC 905 kbps", "FLAC" (bitrate unknown), or null (format unknown). */
        fun qualityLabel(format: AudioFormat?, bitrate: Int?): String? {
            format ?: return null
            if (bitrate == null || bitrate <= 0) return format.displayName
            return "${format.displayName} ${kotlin.math.round(bitrate / 1000.0).toLong()} kbps"
        }

        /**
         * "44.1kHz/16-bit": the rate in kHz with up to two decimals, trailing
         * zeros trimmed (48000 → "48kHz", 22050 → "22.05kHz"); without a bit
         * depth (lossy) just "44.1kHz"; null without a sample rate.
         */
        fun sampleRateLabel(sampleRate: Int?, bitDepth: Int?): String? {
            if (sampleRate == null || sampleRate <= 0) return null
            val hundredths = (sampleRate + 5) / 10 // to the nearest 10 Hz, halves up
            val whole = hundredths / 100
            val fraction = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
            val khz = if (fraction.isEmpty()) "${whole}kHz" else "$whole.${fraction}kHz"
            return if (bitDepth != null && bitDepth > 0) "$khz/$bitDepth-bit" else khz
        }
    }
}

/**
 * Queue order and start for a "play" (as `planQueue` on iOS):
 * - shuffle off: fetched order, starting at `forcedStart` (0 when null), so "tap track N" plays N;
 * - shuffle on with a valid `forcedStart`: that track first, the rest shuffled;
 * - shuffle on otherwise: all shuffled. Shuffled queues start at 0.
 */
fun <T> planQueue(tracks: List<T>, forcedStart: Int?, shuffle: Boolean, random: kotlin.random.Random = kotlin.random.Random): Pair<List<T>, Int> {
    if (!shuffle) return tracks to (forcedStart ?: 0)
    if (forcedStart != null && forcedStart in tracks.indices) {
        val rest = tracks.toMutableList()
        val first = rest.removeAt(forcedStart)
        return (listOf(first) + rest.shuffled(random)) to 0
    }
    return tracks.shuffled(random) to 0
}

/** Indices that play after `current`. Repeat All wraps round; Repeat One still lists what follows (for skips). */
fun upNextIndices(count: Int, current: Int, repeatMode: RepeatMode): List<Int> {
    if (count <= 0 || current !in 0 until count) return emptyList()
    val after = (current + 1 until count).toList()
    return if (repeatMode == RepeatMode.ALL) after + (0 until current) else after
}

/**
 * Maniac Mode's queue order: everything up to and including `current` stays
 * (played or playing); the rest goes back in album (or playlist) order.
 * `places` is each queue entry's place in that order, by queue position, not
 * by track ID: a playlist may hold a song twice. Indices into `places`.
 */
fun albumOrderAfterCurrent(places: List<Int>, current: Int): List<Int> {
    if (current !in places.indices) return places.indices.toList()
    val rest = (current + 1 until places.size).sortedWith(compareBy<Int>({ places[it] }, { it }))
    return (0..current).toList() + rest
}

/** Album length as "47m 12s", or "1h 45m 05s" from an hour up. */
fun formatAlbumDuration(seconds: Double): String {
    // Halves round up, as Swift's rounded() (kotlin.math.round would round them to even).
    val total = maxOf(0L, kotlin.math.floor(seconds + 0.5).toLong())
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "${h}h ${two(m)}m ${two(s)}s" else "${m}m ${two(s)}s"
}

/** 257 → "4:17". */
fun formatTrackTime(seconds: Double): String {
    val total = maxOf(0, seconds.toLong())
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
