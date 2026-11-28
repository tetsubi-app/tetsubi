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
        if (plan(track, directPlay, null).kind == Plan.Kind.DIRECT) {
            val container = track.mediaSource?.container?.lowercase().orEmpty()
            return api.url("Audio/${track.id}/stream.${fileExtension(container)}", listOf("static" to "true") + token)
        }
        return api.url("Audio/${track.id}/universal", universal(api, userId, fallback) + token)
    }

    /** What [url] chose for a track, and the one thing that decided it (for the `play plan:` log line and tests). */
    data class Plan(val kind: Kind, val reasons: List<String>) {
        enum class Kind(val label: String) { DIRECT("direct"), CONVERTED("converted"), CAPPED("capped"), FILE("file") }
    }

    /**
     * The decision behind [url], pure. Reasons: `cap` (mobile-data cap),
     * `original` (played as the file), `noSourceInfo` (the track has no
     * codec/container the app recognises, so the server converts it),
     * `noContainer` (format known, container missing), `notDirect:<FORMAT>`
     * (the player doesn't play that format as it is: ALAC without a decoder).
     */
    fun plan(track: JellyfinTrack, directPlay: Set<AudioFormat>, maxBitrate: Int?): Plan {
        if (maxBitrate != null) return Plan(Plan.Kind.CAPPED, listOf("cap"))
        val source = AudioFormat.source(track) ?: return Plan(Plan.Kind.CONVERTED, listOf("noSourceInfo"))
        if (track.mediaSource?.container.isNullOrEmpty()) return Plan(Plan.Kind.CONVERTED, listOf("noContainer"))
        if (source !in directPlay) return Plan(Plan.Kind.CONVERTED, listOf("notDirect:${source.name}"))
        return Plan(Plan.Kind.DIRECT, listOf("original"))
    }

    /** The `play plan:` line for a track played from its file on this device. */
    fun fileLine(itemId: String, path: String): String =
        "play plan: item=$itemId plan=file reasons=downloaded format=${path.substringAfterLast('.', "?").lowercase()}"

    /** The `play plan:` log line: identifiers and formats only, no address or token. */
    fun planLine(itemId: String, track: JellyfinTrack, plan: Plan, engineNote: String): String =
        "play plan: item=$itemId codec=${track.mediaSource?.codecs?.lowercase() ?: "?"} container=${track.mediaSource?.container?.lowercase() ?: "?"} " +
            "plan=${plan.kind.label} reasons=${plan.reasons.joinToString(",")}" + if (engineNote.isEmpty()) "" else " $engineNote"

    /**
     * The extension of the original file's URL for a container name. ffprobe
     * names an MP4-family file "mov,mp4,m4a,3gp,3g2,mj2" (what Jellyfin may
     * report), which isn't a file name: ALAC and AAC in it are `m4a`, as
     * Swift's `StreamURLBuilder` treats "ALAC in the MP4 family". A single
     * name (`flac`, `mp3`, `m4a`) is used as it is.
     */
    internal fun fileExtension(container: String): String {
        if (',' !in container) return container
        val names = container.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (names.any { it == "m4a" || it == "mp4" || it == "mov" || it == "m4b" }) "m4a" else names.first()
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
