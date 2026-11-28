package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.settings.Preferences

/**
 * What Android's player plays as the original file: FLAC, MP3, AAC, Opus and WAV (every phone and tablet
 * decodes them) and, since audioDEK (the app's own ALAC decoder, `:audiodek`), ALAC on every device,
 * whatever the chipset's MediaCodec offers. Until then ALAC was direct only where MediaCodec listed a
 * decoder (a Qualcomm phone's `c2.qti.alac.*` failed on real files; a MediaTek tablet has none) and was
 * otherwise converted by the server. Pure, so it is tested.
 */
object DirectPlayCapabilities {
    /** Formats every Android device decodes with MediaCodec. */
    val baseline: Set<AudioFormat> = setOf(AudioFormat.FLAC, AudioFormat.MP3, AudioFormat.AAC, AudioFormat.OPUS, AudioFormat.WAV)

    /** The baseline and ALAC: audioDEK is always there. */
    val android: Set<AudioFormat> = baseline + AudioFormat.ALAC

    /**
     * [android], and ALAC only if no direct ALAC stream has failed to decode on this device since the
     * app was last updated ([AlacDirectFailure], saved between launches).
     */
    fun forDevice(failure: AlacDirectFailure): Set<AudioFormat> = if (failure.failed) baseline else android
}

/**
 * Direct ALAC still switches itself off after a genuine decode error: audioDEK throws on a corrupt
 * frame (ERROR_CODE_DECODING_FAILED). After one such failure the app stops trying direct ALAC on this
 * device (every later ALAC track is converted from the start, no wasted start each session) until the
 * app's build changes. Saved as `playback.alacDirectFailed=<build number>`.
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
