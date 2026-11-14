package gg.dekiru.musicplayer.data.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A music `Playlist` item, as listed. The song count and running time are
 * the server's (`ChildCount`, `CumulativeRunTimeTicks`); they lag after an
 * edit, so the playlist page counts its own loaded tracks.
 */
@Serializable(with = JellyfinPlaylist.Serializer::class)
data class JellyfinPlaylist(
    val id: String,
    val name: String = "",
    val songCount: Int = 0,
    /** 100 ns ticks. */
    val runTimeTicks: Long = 0,
    /** e.g. "2026-11-11T23:28:15.0000000Z". */
    val dateCreated: String? = null,
    /** The server has a picture for it (`ImageTags.Primary`); a new or empty playlist has none. */
    val hasArt: Boolean = false,
) {
    val displayName: String get() = name.ifEmpty { "Unknown" }
    val durationSeconds: Long get() = runTimeTicks / 10_000_000

    @Serializable
    private data class Raw(
        @SerialName("Id") val id: String,
        @SerialName("Name") val name: String? = null,
        @SerialName("ChildCount") val childCount: Int? = null,
        @SerialName("CumulativeRunTimeTicks") val cumulativeRunTimeTicks: Long? = null,
        @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
        @SerialName("DateCreated") val dateCreated: String? = null,
        @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    )

    internal object Serializer : KSerializer<JellyfinPlaylist> {
        private val raw = Raw.serializer()
        override val descriptor = raw.descriptor
        override fun deserialize(decoder: Decoder): JellyfinPlaylist = raw.deserialize(decoder).run {
            JellyfinPlaylist(
                id = id,
                name = name.orEmpty(),
                songCount = childCount ?: 0,
                runTimeTicks = cumulativeRunTimeTicks ?: runTimeTicks ?: 0,
                dateCreated = dateCreated,
                hasArt = imageTags?.get("Primary") != null,
            )
        }
        override fun serialize(encoder: Encoder, value: JellyfinPlaylist) = raw.serialize(
            encoder,
            value.run {
                Raw(
                    id, name, songCount, runTimeTicks, runTimeTicks, dateCreated,
                    if (hasArt) mapOf("Primary" to "") else emptyMap(),
                )
            },
        )
    }
}

/**
 * One row of a playlist: a track at a position, with the album it comes
 * from. A song may be in a playlist twice, and then both rows have the same
 * track ID and the same [entryId], so rows are told apart by [position].
 */
@Serializable(with = PlaylistEntry.Serializer::class)
data class PlaylistEntry(
    /** Place in the playlist, from 0. Set by `playlistTracks`. */
    val position: Int = 0,
    /** `PlaylistItemId`: what the server's remove and move calls take. */
    val entryId: String,
    val track: JellyfinTrack,
    val albumName: String? = null,
    val albumId: String? = null,
    val albumArtist: String? = null,
) {
    /** The item whose picture stands for this row: the track's own when it differs, else its album's. */
    val artItemId: String get() = if (track.hasOwnArt) track.id else albumId ?: track.id

    @Serializable
    private data class Raw(
        @SerialName("PlaylistItemId") val playlistItemId: String? = null,
        @SerialName("AlbumArtist") val albumArtist: String? = null,
    )

    /** An audio item (decoded as [JellyfinTrack]) plus `PlaylistItemId` and `AlbumArtist`. */
    internal object Serializer : KSerializer<PlaylistEntry> {
        private val raw = Raw.serializer()
        override val descriptor = raw.descriptor
        override fun deserialize(decoder: Decoder): PlaylistEntry {
            val input = decoder as? JsonDecoder ?: throw SerializationException("A playlist entry is read from JSON")
            val item = input.decodeJsonElement()
            val track = input.json.decodeFromJsonElement(JellyfinTrack.serializer(), item)
            val r = input.json.decodeFromJsonElement(raw, item)
            return PlaylistEntry(
                entryId = r.playlistItemId ?: track.id,
                track = track,
                albumName = track.album,
                albumId = track.albumId,
                albumArtist = r.albumArtist?.takeIf { it.isNotEmpty() } ?: track.albumArtists?.firstOrNull()?.name,
            )
        }
        override fun serialize(encoder: Encoder, value: PlaylistEntry) {
            val output = encoder as? JsonEncoder ?: throw SerializationException("A playlist entry is written as JSON")
            val item = output.json.encodeToJsonElement(JellyfinTrack.serializer(), value.track).jsonObject
            val extra = buildMap {
                put("PlaylistItemId", JsonPrimitive(value.entryId))
                value.albumArtist?.let { put("AlbumArtist", JsonPrimitive(it)) }
            }
            output.encodeJsonElement(JsonObject(item + extra))
        }
    }
}
