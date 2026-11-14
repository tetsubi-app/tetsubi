package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinTrack

/**
 * Stream URLs for a track (as `StreamURLBuilder` on iOS).
 *
 * - On mobile data with a cap: `/Audio/{id}/universal` as AAC in m4a at that bitrate.
 * - A source format the player decodes: the original file, `/Audio/{id}/stream.<ext>?static=true`.
 * - Anything else: `/Audio/{id}/universal` transcoded to [fallback] (FLAC: lossless).
 *
 * The token isn't in the URL: the Android player sends the `Authorization`
 * header. `withToken` adds `ApiKey=` for players that can't send headers
 * (Google Cast). `ApiKey`, as iOS's `StreamURLBuilder`: Jellyfin 12 reads
 * `api_key` as a server key, not a user's token.
 */
object StreamUrls {

    fun url(
        api: JellyfinApi,
        track: JellyfinTrack,
        directPlay: Set<AudioFormat>,
        maxBitrate: Int? = null,
        fallback: AudioFormat = AudioFormat.FLAC,
        withToken: Boolean = false,
    ): String {
        val userId = api.userId.orEmpty()
        val token = if (withToken) token(api) else emptyList()
        if (maxBitrate != null) {
            return api.url(
                "Audio/${track.id}/universal",
                universal(api, userId, AudioFormat.AAC) +
                    listOf("MaxStreamingBitrate" to "$maxBitrate", "AudioBitRate" to "$maxBitrate") + token,
            )
        }
        val source = AudioFormat.source(track)
        val container = track.mediaSource?.container?.lowercase()
        if (source != null && source in directPlay && !container.isNullOrEmpty()) {
            return api.url("Audio/${track.id}/stream.$container", listOf("static" to "true") + token)
        }
        return api.url("Audio/${track.id}/universal", universal(api, userId, fallback) + token)
    }

    /**
     * What Google's Default Media Receiver plays as it is. Others (ALAC,
     * Opus) are converted to FLAC by the server, which stays lossless.
     */
    val castFormats: Set<AudioFormat> = setOf(AudioFormat.MP3, AudioFormat.AAC, AudioFormat.FLAC, AudioFormat.WAV)

    /**
     * The stream a Cast device plays: the original file when the receiver
     * plays its format, else converted to FLAC; never capped (the TV has its
     * own connection); the token in the URL.
     */
    fun castUrl(api: JellyfinApi, track: JellyfinTrack): String =
        url(api, track, castFormats, fallback = AudioFormat.FLAC, withToken = true)

    /**
     * A Cast stream for a track whose server format isn't known here (a
     * downloaded album's track, before the server is asked): converted to
     * FLAC, which the receiver always plays.
     */
    fun castFallbackUrl(api: JellyfinApi, trackId: String): String =
        api.url("Audio/$trackId/universal", universal(api, api.userId.orEmpty(), AudioFormat.FLAC) + token(api))

    /** An item's cover for a Cast device: as [JellyfinApi.imageUrl], with the token (the receiver can't send headers). */
    fun castArtworkUrl(api: JellyfinApi, itemId: String, maxWidth: Int = 600): String =
        api.url("Items/$itemId/Images/Primary", listOf("maxWidth" to "$maxWidth", "quality" to "90") + token(api))

    /**
     * The MIME type of a stream URL (Cast needs one per item): the
     * `Container=` of a converted stream, else the file extension; null if
     * unknown.
     */
    fun mimeType(url: String): String? {
        val query = url.substringAfter('?', "")
        val container = if (isConverted(url)) {
            query.split('&').firstOrNull { it.startsWith("Container=") }?.substringAfter('=')
        } else {
            url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        }
        return when (container?.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "mp4", "m4b" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg", "oga", "opus" -> "audio/ogg"
            "webm" -> "audio/webm"
            else -> null
        }
    }

    private fun token(api: JellyfinApi): List<Pair<String, String>> = api.accessToken?.let { listOf("ApiKey" to it) }.orEmpty()

    /** Whether `url` is the server converting on the fly (`/universal`): such a stream can't be seeked. */
    fun isConverted(url: String): Boolean = "/universal?" in url

    /**
     * The same converted stream, started `millis` in (`StartTimeTicks`): how
     * a seek works in a stream that's converted on the fly. Jellyfin's own
     * clients do the same.
     */
    fun startingAt(url: String, millis: Long): String {
        val base = url.replace(Regex("&StartTimeTicks=\\d+"), "")
        return if (millis <= 0) base else "$base&StartTimeTicks=${millis * 10_000}"
    }

    private fun universal(api: JellyfinApi, userId: String, format: AudioFormat): List<Pair<String, String>> = buildList {
        add("UserId" to userId)
        add("DeviceId" to api.identity.deviceId)
        add("Container" to format.container)
        add("TranscodingContainer" to format.container)
        add("TranscodingProtocol" to "http")
        format.codec?.let { add("AudioCodec" to it) }
    }
}
