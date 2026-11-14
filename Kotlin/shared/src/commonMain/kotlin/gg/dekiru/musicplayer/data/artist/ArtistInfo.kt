package gg.dekiru.musicplayer.data.artist

import gg.dekiru.musicplayer.AppInfo
import gg.dekiru.musicplayer.data.api.TetsubiHttp
import gg.dekiru.musicplayer.data.api.TetsubiJson
import gg.dekiru.musicplayer.data.api.stripDiacritics
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * "About <artist>" on the album page (as `ArtistInfo` on iOS). Facts (type,
 * origin, years, genres) come from MusicBrainz; MusicBrainz has no
 * biographies, so the summary is the Wikipedia introduction it links to
 * (through Wikidata).
 */
@Serializable
data class ArtistInfo(
    val name: String,
    /** "Group", "Person", "Orchestra", … */
    val type: String? = null,
    val disambiguation: String? = null,
    /** e.g. "Cleveland, United States". */
    val origin: String? = null,
    val begin: String? = null,
    val end: String? = null,
    val ended: Boolean = false,
    /** Most-voted first, at most five. */
    val genres: List<String> = emptyList(),
    val summary: String? = null,
    val wikipediaUrl: String? = null,
    val musicBrainzUrl: String,
    val fetchedMillis: Long = 0,
) {
    /** One line, e.g. "Group · Cleveland, United States · 1988–present". */
    val facts: String?
        get() = listOfNotNull(type, origin, years(begin, end, ended)).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    companion object {
        /** "1988–present", "1988–2003", or null without a start year. */
        fun years(begin: String?, end: String?, ended: Boolean): String? {
            val start = begin?.take(4)?.takeIf { it.length == 4 } ?: return null
            val finish = end?.take(4)?.takeIf { it.length == 4 }
            return when {
                finish != null -> if (finish == start) start else "$start–$finish"
                ended -> start
                else -> "$start–present"
            }
        }
    }
}

// MusicBrainz answers

@Serializable
internal data class MbSearch(val artists: List<Artist> = emptyList()) {
    @Serializable
    data class Artist(val id: String, val name: String, val score: Int? = null)
}

@Serializable
internal data class MbArtist(
    val id: String,
    val name: String,
    val type: String? = null,
    val disambiguation: String? = null,
    val area: Area? = null,
    @SerialName("begin-area") val beginArea: Area? = null,
    @SerialName("life-span") val lifeSpan: LifeSpan? = null,
    val genres: List<Genre>? = null,
    val relations: List<Relation>? = null,
) {
    @Serializable data class Area(val name: String)
    @Serializable data class LifeSpan(val begin: String? = null, val end: String? = null, val ended: Boolean? = null)
    @Serializable data class Genre(val name: String, val count: Int? = null)
    @Serializable data class Relation(val type: String, val url: Url? = null) {
        @Serializable data class Url(val resource: String)
    }
}

/** Where looked-up artists are kept between launches. */
interface ArtistInfoCache {
    fun load(key: String): String?
    fun save(key: String, text: String)
}

/**
 * Fetches and caches artist info, following MusicBrainz's rules: an
 * identifying User-Agent and at most one request a second.
 */
class ArtistInfoClient(
    private val cache: ArtistInfoCache,
    private val now: () -> Long,
    private val http: HttpClient = TetsubiHttp.client,
) {
    private val mutex = Mutex()
    private val rateMutex = Mutex()
    private var nextMusicBrainzSlot = 0L
    private val memory = mutableMapOf<String, ArtistInfo>()

    /** Lookups that definitely found nothing this run (not saved; retried next launch). */
    private val misses = mutableSetOf<String>()

    /** Info by MusicBrainz ID when known (exact), else by name (only a confident match). Null if none or offline. */
    suspend fun info(name: String, musicBrainzId: String?): ArtistInfo? = mutex.withLock {
        val key = musicBrainzId ?: ("name-" + name.lowercase())
        memory[key]?.let { return it }
        if (key in misses) return null
        loadCached(key)?.let { memory[key] = it; return it }

        val outcome = Lookup()
        val info = outcome.fetch(name, musicBrainzId)
        if (info == null) {
            if (!outcome.requestFailed) misses += key
            return null
        }
        memory[key] = info
        cache.save(key, TetsubiJson.encodeToString(ArtistInfo.serializer(), info))
        info
    }

    private fun loadCached(key: String): ArtistInfo? =
        cache.load(key)
            ?.let { runCatching { TetsubiJson.decodeFromString(ArtistInfo.serializer(), it) }.getOrNull() }
            ?.takeIf { now() - it.fetchedMillis < MAX_AGE_MILLIS }

    /** One lookup; remembers whether any request failed (so "not found" isn't remembered when offline). */
    private inner class Lookup {
        var requestFailed = false

        suspend fun fetch(name: String, knownId: String?): ArtistInfo? {
            val mbid = knownId ?: run {
                val url = URLBuilder("https://musicbrainz.org/ws/2/artist").apply {
                    parameters.append("query", "artist:\"${name.replace("\"", "\\\"")}\"")
                    parameters.append("fmt", "json")
                    parameters.append("limit", "5")
                }.buildString()
                val found = musicBrainz(url)?.decode(MbSearch.serializer()) ?: return null
                bestMatch(name, found.artists)
            } ?: return null

            val artist = musicBrainz("https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels+genres&fmt=json")
                ?.decode(MbArtist.serializer()) ?: return null
            var info = infoFrom(artist, now())
            wikipediaTitle(artist.relations.orEmpty())?.let { title ->
                wikipediaSummary(title)?.let { (summary, page) -> info = info.copy(summary = summary, wikipediaUrl = page) }
            }
            return info
        }

        private fun <T> String.decode(serializer: kotlinx.serialization.KSerializer<T>): T? =
            runCatching { TetsubiJson.decodeFromString(serializer, this) }.getOrNull()

        private suspend fun wikipediaTitle(relations: List<MbArtist.Relation>): String? {
            wikidataId(relations)?.let { q ->
                val json = get("https://www.wikidata.org/wiki/Special:EntityData/$q.json")?.second
                val title = json?.let { runCatching { TetsubiJson.parseToJsonElement(it).jsonObject }.getOrNull() }
                    ?.obj("entities")?.values?.firstOrNull()?.jsonObject
                    ?.obj("sitelinks")?.obj("enwiki")?.get("title")?.jsonPrimitive?.contentOrNull
                if (title != null) return title
            }
            return wikipediaTitleFromLink(relations)
        }

        private suspend fun wikipediaSummary(title: String): Pair<String, String?>? {
            val path = title.replace(' ', '_').encodeURLPathPart().replace("/", "%2F")
            val json = get("https://en.wikipedia.org/api/rest_v1/page/summary/$path")?.second ?: return null
            val obj = runCatching { TetsubiJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "disambiguation") return null
            val extract = obj["extract"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            val page = obj.obj("content_urls")?.obj("desktop")?.get("page")?.jsonPrimitive?.contentOrNull
            return extract to page
        }

        /** A MusicBrainz request, at least 1.1 s after the last; "busy" (503) retried once. */
        private suspend fun musicBrainz(url: String): String? {
            repeat(2) { attempt ->
                rateMutex.withLock {
                    val wait = nextMusicBrainzSlot - now()
                    if (wait > 0) delay(wait)
                    nextMusicBrainzSlot = now() + if (attempt == 0) 1_100 else 3_000
                }
                val (status, body) = get(url) ?: return null
                if (status == 503 && attempt == 0) {
                    rateMutex.withLock { nextMusicBrainzSlot = now() + 2_000 }
                    return@repeat
                }
                return body
            }
            return null
        }

        /** (status, body for 200). Anything but 200 or 404 counts as a failed request. */
        private suspend fun get(url: String): Pair<Int, String?>? = try {
            val response = http.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/json")
                timeout { requestTimeoutMillis = 15_000 }
            }
            val status = response.status.value
            if (status != 200 && status != 404) requestFailed = true
            status to (if (status == 200) response.bodyAsText() else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            requestFailed = true
            null
        }
    }

    companion object {
        const val USER_AGENT = "Tetsubi/${AppInfo.VERSION} ( https://tetsubi.app )"
        const val MAX_AGE_MILLIS = 30L * 24 * 3600 * 1000

        /** The search result to trust: the same name (ignoring case and accents) with score ≥ 90. Never a guess. */
        internal fun bestMatch(name: String, artists: List<MbSearch.Artist>): String? {
            fun fold(text: String) = stripDiacritics(text).lowercase()
            return artists.firstOrNull { fold(it.name) == fold(name) && (it.score ?: 0) >= 90 }?.id
        }

        internal fun infoFrom(a: MbArtist, nowMillis: Long): ArtistInfo {
            val origin = listOfNotNull(a.beginArea?.name, a.area?.name).distinct()
            return ArtistInfo(
                name = a.name,
                type = a.type,
                disambiguation = a.disambiguation?.takeIf { it.isNotEmpty() },
                origin = origin.takeIf { it.isNotEmpty() }?.joinToString(", "),
                begin = a.lifeSpan?.begin,
                end = a.lifeSpan?.end,
                ended = a.lifeSpan?.ended ?: false,
                genres = a.genres.orEmpty().sortedByDescending { it.count ?: 0 }.take(5).map { it.name },
                musicBrainzUrl = "https://musicbrainz.org/artist/${a.id}",
                fetchedMillis = nowMillis,
            )
        }

        /** Wikidata ID ("Q11647") from MusicBrainz relations. */
        internal fun wikidataId(relations: List<MbArtist.Relation>): String? =
            relations.firstOrNull { it.type == "wikidata" }?.url?.resource?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

        /** English Wikipedia title from an old-style direct Wikipedia link. */
        internal fun wikipediaTitleFromLink(relations: List<MbArtist.Relation>): String? {
            val link = relations.firstOrNull { it.type == "wikipedia" && it.url?.resource?.contains("en.wikipedia.org") == true }
                ?.url?.resource ?: return null
            return link.substringAfter("/wiki/").decodeURLPart()
        }
    }
}

private fun JsonObject.obj(name: String): JsonObject? = runCatching { get(name)?.jsonObject }.getOrNull()
