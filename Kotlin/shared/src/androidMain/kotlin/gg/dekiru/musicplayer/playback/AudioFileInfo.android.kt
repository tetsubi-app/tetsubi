package gg.dekiru.musicplayer.playback

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build

/** Lossless formats, the only ones with a bit depth worth showing. */
private val LOSSLESS = setOf("audio/flac", "audio/alac", "audio/raw", "audio/x-wav", "audio/wav")

/**
 * The file's sample rate from its audio track ([MediaExtractor]); its bit
 * depth, for lossless files, from [MediaMetadataRetriever] (Android 12+), else
 * the track's own `bits-per-sample` where the extractor gives one.
 */
internal actual fun audioFileInfo(path: String): AudioFileInfo {
    val extractor = try {
        MediaExtractor()
    } catch (e: Throwable) {
        return AudioFileInfo(null, null) // host tests: no Android media classes
    }
    try {
        extractor.setDataSource(path)
        val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: return AudioFileInfo(null, null)
        val rate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE)?.takeIf { it > 0 }
        val mime = format.getString(MediaFormat.KEY_MIME)
        if (mime !in LOSSLESS) return AudioFileInfo(rate, null)
        val depth = retrieverBitDepth(path) ?: format.intOrNull("bits-per-sample")
        return AudioFileInfo(rate, depth?.takeIf { it > 0 })
    } catch (e: Throwable) {
        return AudioFileInfo(null, null)
    } finally {
        runCatching { extractor.release() }
    }
}

private fun retrieverBitDepth(path: String): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val retriever = try { MediaMetadataRetriever() } catch (e: Throwable) { return null }
    return try {
        retriever.setDataSource(path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
