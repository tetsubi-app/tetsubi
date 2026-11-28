package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.playback.AudioFormat
import gg.dekiru.musicplayer.playback.StreamUrls

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
            // The same file URL the direct stream uses (an MP4-family container list is "m4a").
            val ext = StreamUrls.fileExtension(track.mediaSource?.container?.lowercase().orEmpty())
            return api.url("Audio/${track.id}/stream.$ext", listOf("static" to "true"))
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

    /**
     * Whether the download is the original file (no conversion): the source container is the format's
     * (ffprobe's "mov,mp4,m4a,..." counts as m4a, as [StreamUrls.fileExtension] reads it) and, for the
     * two formats that share m4a (ALAC and AAC), the source codec is the format's too.
     */
    fun isOriginal(track: JellyfinTrack, format: AudioFormat): Boolean {
        val container = track.mediaSource?.container?.lowercase() ?: return false
        val ext = StreamUrls.fileExtension(container)
        val sameContainer = ext == format.container || (format.container == "m4a" && ext in setOf("mp4", "m4b", "mov"))
        if (!sameContainer) return false
        val source = AudioFormat.source(track)
        return source == null || source == format || (format != AudioFormat.ALAC && format != AudioFormat.AAC)
    }
}
