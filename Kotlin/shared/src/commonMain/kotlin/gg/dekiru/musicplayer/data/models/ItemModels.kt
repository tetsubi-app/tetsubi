package gg.dekiru.musicplayer.data.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Jellyfin items (`BaseItemDto`), cut down to the fields Tetsubi uses, as in
// the iOS JellyfinModels.swift. Keys are Jellyfin's PascalCase names.

/** `{ "Items": [...], "TotalRecordCount": n }`. `Items` may be missing. */
@Serializable
data class ItemsResponse<T>(
    @SerialName("Items") val items: List<T> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
)

/** An artist as listed on an album or track (`AlbumArtists`). */
@Serializable
data class JellyfinPerson(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String = "",
)

/** An album artist from `/Artists/AlbumArtists`. */
@Serializable
data class JellyfinArtist(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
)

/**
 * Per-user data for an item (requested with `Fields=UserData`).
 * `DatePlayed` is an ISO 8601 string; it's parsed where it's used.
 */
@Serializable
data class UserData(
    @SerialName("PlayCount") val playCount: Int? = null,
    @SerialName("IsPlayed") val isPlayed: Boolean? = null,
    @SerialName("DatePlayed") val datePlayed: String? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean? = null,
)

/** The original file of a track: decides direct play vs transcode. */
@Serializable(with = MediaSource.Serializer::class)
data class MediaSource(
    val id: String? = null,
    /** "flac", "mp3", "m4a", "ogg", … */
    val container: String? = null,
    val size: Long? = null,
    val name: String? = null,
    /** Lower-case codec: "flac", "aac", "opus", "mp3", … */
    val codecs: String? = null,
    /** Bits per second (`Bitrate`, else the audio stream's `BitRate`), for Now Playing. */
    val bitrate: Int? = null,
    /** The audio stream's `SampleRate` in Hz (e.g. 44100), for Now Playing; null if unknown. */
    val sampleRate: Int? = null,
    /** The audio stream's `BitDepth` (e.g. 16); null for lossy files or if unknown. */
    val bitDepth: Int? = null,
) {
    @Serializable
    private data class Stream(
        @SerialName("Codec") val codec: String? = null,
        @SerialName("Type") val type: String? = null,
        @SerialName("BitRate") val bitRate: Int? = null,
        @SerialName("SampleRate") val sampleRate: Int? = null,
        @SerialName("BitDepth") val bitDepth: Int? = null,
    )

    @Serializable
    private data class Raw(
        @SerialName("Id") val id: String? = null,
        @SerialName("Container") val container: String? = null,
        @SerialName("Size") val size: Long? = null,
        @SerialName("Name") val name: String? = null,
        @SerialName("Codecs") val codecs: String? = null,
        @SerialName("Bitrate") val bitrate: Int? = null,
        @SerialName("MediaStreams") val mediaStreams: List<Stream>? = null,
    )

    /**
     * Jellyfin reports codecs per stream (`MediaStreams`), not in a top-level
     * `Codecs` field, so this falls back to the first audio stream's codec.
     * The sample rate and bit depth come from that stream too.
     */
    internal object Serializer : KSerializer<MediaSource> {
        private val raw = Raw.serializer()
        override val descriptor = raw.descriptor
        override fun deserialize(decoder: Decoder): MediaSource {
            val r = raw.deserialize(decoder)
            val audio = r.mediaStreams?.firstOrNull { it.type == "Audio" }
            val codec = r.codecs ?: audio?.codec
            return MediaSource(
                r.id, r.container, r.size, r.name, codec?.lowercase(), r.bitrate ?: audio?.bitRate,
                sampleRate = audio?.sampleRate?.takeIf { it > 0 },
                bitDepth = audio?.bitDepth?.takeIf { it > 0 },
            )
        }
        override fun serialize(encoder: Encoder, value: MediaSource) {
            // Sample rate and bit depth go back as one audio stream, so they survive a round trip.
            val streams = if (value.sampleRate != null || value.bitDepth != null) {
                listOf(Stream(type = "Audio", sampleRate = value.sampleRate, bitDepth = value.bitDepth))
            } else {
                null
            }
            raw.serialize(encoder, Raw(value.id, value.container, value.size, value.name, value.codecs, value.bitrate, streams))
        }
    }
}

/**
 * A `MusicAlbum` item. Some queries (Recently Played) return `Audio` tracks
 * standing in for albums; those are turned into their parent album (its ID
 * and name), so the album page fetches the album, not the track.
 *
 * Two albums are equal when their IDs are (as on iOS).
 */
@Serializable(with = JellyfinAlbum.Serializer::class)
data class JellyfinAlbum(
    val id: String,
    val name: String? = null,
    val serverId: String? = null,
    /** e.g. "1994-10-31T00:00:00.0000000Z". */
    val premiereDate: String? = null,
    val productionYear: Int? = null,
    val albumArtists: List<JellyfinPerson>? = null,
    val album: String? = null,
    val imageTags: Map<String, String>? = null,
    /** 100 ns ticks. */
    val runTimeTicks: Long? = null,
    val type: String? = null,
    val userData: UserData? = null,
    /** For a multi-disc album shown as one: each disc's album, in order. Set by disc grouping (A2). */
    val discAlbums: List<JellyfinAlbum> = emptyList(),
) {
    val displayName: String get() = name ?: album ?: "Unknown"
    val artistName: String get() = albumArtists?.firstOrNull()?.name.orEmpty()
    val artistId: String? get() = albumArtists?.firstOrNull()?.id
    val playCount: Int get() = userData?.playCount ?: 0

    /** Jellyfin often leaves `IsPlayed` unset with `PlayCount > 0`, so the count decides. */
    val hasBeenPlayed: Boolean get() = playCount > 0 || userData?.isPlayed == true
    val durationSeconds: Long get() = (runTimeTicks ?: 0) / 10_000_000

    override fun equals(other: Any?): Boolean = other is JellyfinAlbum && other.id == id
    override fun hashCode(): Int = id.hashCode()

    @Serializable
    private data class Raw(
        @SerialName("Id") val id: String,
        @SerialName("Name") val name: String? = null,
        @SerialName("ServerId") val serverId: String? = null,
        @SerialName("PremiereDate") val premiereDate: String? = null,
        @SerialName("ProductionYear") val productionYear: Int? = null,
        @SerialName("AlbumArtists") val albumArtists: List<JellyfinPerson>? = null,
        @SerialName("Album") val album: String? = null,
        @SerialName("AlbumId") val albumId: String? = null,
        @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
        @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
        @SerialName("Type") val type: String? = null,
        @SerialName("UserData") val userData: UserData? = null,
    )

    internal object Serializer : KSerializer<JellyfinAlbum> {
        private val raw = Raw.serializer()
        override val descriptor = raw.descriptor
        override fun deserialize(decoder: Decoder): JellyfinAlbum {
            val r = raw.deserialize(decoder)
            val isTrack = r.type == "Audio"
            return JellyfinAlbum(
                id = if (isTrack) r.albumId ?: r.id else r.id,
                name = if (isTrack) r.album ?: r.name else r.name,
                serverId = r.serverId,
                premiereDate = r.premiereDate,
                productionYear = r.productionYear,
                albumArtists = r.albumArtists,
                album = r.album,
                imageTags = r.imageTags,
                runTimeTicks = r.runTimeTicks,
                type = r.type,
                userData = r.userData,
            )
        }
        override fun serialize(encoder: Encoder, value: JellyfinAlbum) = raw.serialize(
            encoder,
            Raw(
                value.id, value.name, value.serverId, value.premiereDate, value.productionYear,
                value.albumArtists, value.album, null, value.imageTags, value.runTimeTicks, value.type, value.userData,
            ),
        )
    }
}

/** An `Audio` item. The server sends `MediaSources` (a list); the first is kept. */
@Serializable(with = JellyfinTrack.Serializer::class)
data class JellyfinTrack(
    val id: String,
    val name: String? = null,
    val serverId: String? = null,
    /** Track number on its disc. */
    val indexNumber: Int? = null,
    val runTimeTicks: Long? = null,
    val album: String? = null,
    val albumId: String? = null,
    val albumArtists: List<JellyfinPerson>? = null,
    val imageTags: Map<String, String>? = null,
    val mediaSource: MediaSource? = null,
    /** Loudness correction in dB (`Fields=NormalizationGain`). */
    val normalizationGain: Double? = null,
    /** `ParentIndexNumber`: the disc, for albums tagged disc 1, disc 2… */
    val discNumber: Int? = null,
    /**
     * The track has its own picture, different from the album's (each song
     * of The Slip; loose singles). Every track has its own image tag, so the
     * pictures are compared by blur hash (`Fields=ImageBlurHashes`).
     */
    val hasOwnArt: Boolean = false,
) {
    /** Title for display: untagged files come through as "01 - Artist - Song"; this shows "Song". */
    val displayName: String
        get() = name?.takeIf { it.isNotEmpty() }
            ?.let { TrackTitle.clean(it, albumArtists.orEmpty().map(JellyfinPerson::name)).first }
            ?: "Unknown"

    /** Track number from the tag, else from a "01 - " file-name prefix. */
    val trackNumber: Int get() = indexNumber ?: name?.let { TrackTitle.clean(it, emptyList()).second } ?: 0
    val durationSeconds: Long get() = (runTimeTicks ?: 0) / 10_000_000

    /** File number for downloads: the track number on disc 1, disc × 100 + track after (disc 2 track 1 → 201). */
    val fileNumber: Int get() = discNumber?.takeIf { it > 1 }?.let { it * 100 + trackNumber } ?: trackNumber

    @Serializable
    private data class Raw(
        @SerialName("Id") val id: String,
        @SerialName("Name") val name: String? = null,
        @SerialName("ServerId") val serverId: String? = null,
        @SerialName("IndexNumber") val indexNumber: Int? = null,
        @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
        @SerialName("Album") val album: String? = null,
        @SerialName("AlbumId") val albumId: String? = null,
        @SerialName("AlbumArtists") val albumArtists: List<JellyfinPerson>? = null,
        @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
        @SerialName("MediaSources") val mediaSources: List<MediaSource>? = null,
        @SerialName("NormalizationGain") val normalizationGain: Double? = null,
        @SerialName("ParentIndexNumber") val discNumber: Int? = null,
        @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
        @SerialName("ImageBlurHashes") val imageBlurHashes: Map<String, Map<String, String>>? = null,
    )

    companion object {
        /**
         * Download numbers for an album's tracks, in the order given (play
         * order): each track's [fileNumber], or 1, 2, 3… when those aren't all
         * different (untagged tracks all have 0). As iOS.
         */
        fun fileNumbers(tracks: List<JellyfinTrack>): List<Int> {
            val numbers = tracks.map { it.fileNumber }
            return if (numbers.toSet().size == numbers.size) numbers else tracks.indices.map { it + 1 }
        }

        /**
         * Whether a track's picture differs from its album's: by blur hash,
         * since tags differ even for the same picture. Unknown hashes count
         * as the same (the album picture is the safe choice). As iOS.
         */
        fun ownArt(ownTag: String?, albumTag: String?, blurHashes: Map<String, String>): Boolean {
            if (ownTag == null) return false
            if (albumTag == null) return true
            val own = blurHashes[ownTag] ?: return false
            val album = blurHashes[albumTag] ?: return false
            return own != album
        }
    }

    internal object Serializer : KSerializer<JellyfinTrack> {
        private val raw = Raw.serializer()
        override val descriptor = raw.descriptor
        override fun deserialize(decoder: Decoder): JellyfinTrack = raw.deserialize(decoder).run {
            JellyfinTrack(
                id, name, serverId, indexNumber, runTimeTicks, album, albumId, albumArtists, imageTags,
                mediaSources?.firstOrNull(), normalizationGain, discNumber,
                hasOwnArt = ownArt(imageTags?.get("Primary"), albumPrimaryImageTag, imageBlurHashes?.get("Primary").orEmpty()),
            )
        }
        override fun serialize(encoder: Encoder, value: JellyfinTrack) = raw.serialize(
            encoder,
            value.run {
                Raw(
                    id, name, serverId, indexNumber, runTimeTicks, album, albumId, albumArtists, imageTags,
                    mediaSource?.let(::listOf), normalizationGain, discNumber,
                )
            },
        )
    }
}
