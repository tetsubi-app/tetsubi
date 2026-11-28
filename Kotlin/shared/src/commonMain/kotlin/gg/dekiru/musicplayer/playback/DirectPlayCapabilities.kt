package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.settings.Preferences

/**
 * What Android's player plays as the original file, which depends on the
 * device: every phone and tablet decodes FLAC, MP3, AAC, Opus and WAV, but
 * ALAC only where the chipset's MediaCodec has a decoder (a Qualcomm phone
 * has `c2.qti.alac.*`; a MediaTek tablet has none, and Media3 then plays an
 * ALAC file silently). A file the device can't decode is converted by the
 * server (FLAC through `/universal`), so ALAC is added only when the probe
 * (`AlacDecoderProbe`, androidMain) finds a decoder. Pure, so it is tested.
 */
object DirectPlayCapabilities {
    /** Formats every Android device decodes. */
    val baseline: Set<AudioFormat> = setOf(AudioFormat.FLAC, AudioFormat.MP3, AudioFormat.AAC, AudioFormat.OPUS, AudioFormat.WAV)

    /** One entry of the device's codec list: whether it encodes, and the MIME types it handles. */
    data class Codec(val isEncoder: Boolean, val mimeTypes: List<String>)

    /** Whether the codec list has a decoder for `mime` (case-insensitive: vendors differ). */
    fun hasDecoder(mime: String, codecs: Iterable<Codec>): Boolean =
        codecs.any { codec -> !codec.isEncoder && codec.mimeTypes.any { it.equals(mime, ignoreCase = true) } }

    fun forDevice(hasAlacDecoder: Boolean): Set<AudioFormat> =
        if (hasAlacDecoder) baseline + AudioFormat.ALAC else baseline

    /**
     * As above, and ALAC only if no direct ALAC stream has failed to decode on this device since the
     * app was last updated ([AlacDirectFailure], saved between launches).
     */
    fun forDevice(hasAlacDecoder: Boolean, failure: AlacDirectFailure): Set<AudioFormat> =
        forDevice(hasAlacDecoder && !failure.failed)
}

/**
 * Direct ALAC is best effort: a decoder can be listed (a Qualcomm phone lists `c2.qti.alac.sw.decoder`)
 * and still fail with ERROR_CODE_DECODING_FAILED on real files. After one such failure the app stops
 * trying direct ALAC on this device (every later ALAC track is converted from the start, no wasted
 * start each session) until the app's build changes, when the new version may have a working decoder.
 * Saved as `playback.alacDirectFailed=<build number>`.
 */
class AlacDirectFailure(private val preferences: Preferences, private val build: Int) {
    /** A direct ALAC stream failed to decode on this build. */
    val failed: Boolean get() = preferences.getString(KEY) == build.toString()

    fun record() = preferences.putString(KEY, build.toString())

    companion object {
        const val KEY = "playback.alacDirectFailed"
    }
}

/**
 * A direct stream that fails to decode (a decoder that is listed but won't
 * start, a format the extractor can't read) is played again as the converted
 * stream, once: the item then has no [EngineItem.directFallbackUrl], so a
 * second failure is reported as any other.
 */
object DirectPlayFallback {
    /**
     * Media3 `PlaybackException` codes for "this stream can't be read or
     * decoded here": container malformed (3001) or unsupported (3003), decoder
     * init failed (4001), query failed (4002), decoding failed (4003), format
     * exceeds capabilities (4004) and format unsupported (4005). Network
     * errors (2xxx) are not here: a dropped direct stream resumes by byte range.
     */
    val DECODE_ERROR_CODES: Set<Int> = setOf(3001, 3003, 4001, 4002, 4003, 4004, 4005)

    /** Whether `item`'s failure should be answered by playing its converted stream (never while casting: the receiver has its own stream). */
    fun shouldFallBack(errorCode: Int, item: EngineItem?, casting: Boolean): Boolean =
        !casting && item != null && item.directFallbackUrl != null && !item.seeksByRestart && errorCode in DECODE_ERROR_CODES

    /**
     * What to log about a failed decode, names only: the decoder (from a "Decoder failed: <name>" message
     * somewhere in the cause chain, or the codec info's own name) and the exception classes in the chain.
     */
    fun describeFailure(messages: List<String?>, classNames: List<String>, codecName: String? = null): String {
        val decoder = codecName ?: messages.firstNotNullOfOrNull { m -> m?.let { DECODER_NAME.find(it)?.groupValues?.get(1) } } ?: "unknown"
        val chain = classNames.distinct().joinToString(">").ifEmpty { "none" }
        return "decoder=$decoder causes=$chain"
    }

    private val DECODER_NAME = Regex("""Decoder failed: ([A-Za-z0-9._\-]+)""")

    /** `item` playing its converted stream (nothing left to fall back to). */
    fun converted(item: EngineItem): EngineItem = item.copy(url = item.directFallbackUrl ?: item.url, directFallbackUrl = null)
}
