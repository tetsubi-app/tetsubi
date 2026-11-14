package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.playback.AudioFormat

/**
 * The URL to download a track in `format` (as iOS `StreamURLBuilder.url(for:format:)`):
 * - the source container is the format's: the original file, `Audio/{id}/stream.<ext>?static=true` (resumable);
 * - else `Audio/{id}/universal`, converted by the server.
 *
 * No token in the URL: the download sends the `Authorization` header.
 */
object DownloadUrls {
    fun url(api: JellyfinApi, track: JellyfinTrack, format: AudioFormat): String {
        if (isOriginal(track, format)) {
            return api.url("Audio/${track.id}/stream.${format.fileExtension}", listOf("static" to "true"))
        }
        val query = buildList {
            add("UserId" to api.userId.orEmpty())
            add("DeviceId" to api.identity.deviceId)
            add("Container" to format.container)
            add("TranscodingContainer" to format.container)
            add("TranscodingProtocol" to "http")
            format.codec?.let { add("AudioCodec" to it) }
        }
        return api.url("Audio/${track.id}/universal", query)
    }

    /** Whether the download is the original file (no conversion). */
    fun isOriginal(track: JellyfinTrack, format: AudioFormat): Boolean =
        track.mediaSource?.container?.lowercase() == format.container
}
