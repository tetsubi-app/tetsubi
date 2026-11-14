package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.models.JellyfinTrack

/** Audio formats for streaming and downloads (as `AudioFormat` on iOS). First = default. */
enum class AudioFormat(val displayName: String, val fileExtension: String, val qualityBadge: String) {
    FLAC("FLAC", "flac", "Lossless"),
    ALAC("ALAC", "m4a", "Lossless"),
    AAC("AAC", "m4a", "High Quality"),
    OPUS("Opus", "ogg", "Bandwidth Efficient"),
    MP3("MP3", "mp3", "Compatible"),
    WAV("WAV", "wav", "Uncompressed"),
    ;

    val isLossless: Boolean get() = this == FLAC || this == ALAC || this == WAV

    /** Lossless 3 > AAC/Opus 2 > MP3 1. */
    internal val qualityRank: Int
        get() = when (this) {
            FLAC, ALAC, WAV -> 3
            AAC, OPUS -> 2
            MP3 -> 1
        }

    /** Jellyfin's container name for this format (`Container=` on the universal endpoint). */
    val container: String
        get() = when (this) {
            FLAC -> "flac"
            ALAC, AAC -> "m4a"
            OPUS -> "ogg"
            MP3 -> "mp3"
            WAV -> "wav"
        }

    /** Jellyfin's codec name (`AudioCodec=`), none for WAV. */
    val codec: String?
        get() = when (this) {
            FLAC -> "flac"
            ALAC -> "alac"
            AAC -> "aac"
            OPUS -> "opus"
            MP3 -> "mp3"
            WAV -> null
        }

    companion object {
        /** The format a track is stored in on the server, or null if unrecognised. */
        fun source(track: JellyfinTrack): AudioFormat? {
            val source = track.mediaSource ?: return null
            val codec = source.codecs?.lowercase().orEmpty()
            val container = source.container?.lowercase().orEmpty()
            return when {
                "flac" in codec || container == "flac" -> FLAC
                "alac" in codec -> ALAC
                "opus" in codec -> OPUS
                "mp3" in codec || container == "mp3" -> MP3
                "aac" in codec -> AAC
                codec.startsWith("pcm") || container == "wav" -> WAV
                container == "ogg" -> OPUS
                container in setOf("m4a", "mp4", "m4b") || "mp4" in container -> if (codec.isEmpty()) ALAC else AAC
                else -> null
            }
        }

        /**
         * The format to download a track in. `preferred == null` means the
         * original. A preferred format is used unless the original is already
         * of lower or equal quality (an MP3 is never transcoded up to FLAC).
         */
        fun downloadFormat(track: JellyfinTrack, preferred: AudioFormat?): AudioFormat {
            val original = source(track)
            if (preferred == null) return original ?: FLAC
            if (original == null) return preferred
            if (original == preferred) return original
            return if (original.qualityRank <= preferred.qualityRank && !(original.isLossless && preferred.isLossless)) original else preferred
        }
    }
}
