package gg.dekiru.musicplayer.data.api

import gg.dekiru.musicplayer.data.models.AuthenticateByNameBody
import gg.dekiru.musicplayer.data.models.AuthenticationResponse
import gg.dekiru.musicplayer.data.models.ItemsResponse
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.JellyfinUser
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.data.models.QuickConnectAuthBody
import gg.dekiru.musicplayer.data.models.QuickConnectPoll
import gg.dekiru.musicplayer.data.models.QuickConnectStart
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer

/**
 * Talks to one Jellyfin server. Every call sends the
 * `Authorization: MediaBrowser …` header ([mediaBrowserHeader]), with the
 * token once signed in. Errors come out as [JellyfinError].
 *
 * Signed out, it's used for the sign-in calls; [withSession] gives the
 * signed-in copy.
 */
class JellyfinApi(
    baseUrl: String,
    val identity: ClientIdentity,
    val accessToken: String? = null,
    val userId: String? = null,
    internal val http: HttpClient = TetsubiHttp.client,
) {
    /** e.g. "http://192.168.1.20:8096" or "https://example.com/jellyfin" (no trailing slash). */
    val baseUrl: String = baseUrl.trimEnd('/')

    val isSignedIn: Boolean get() = accessToken != null && userId != null

    fun withSession(accessToken: String, userId: String) =
        JellyfinApi(baseUrl, identity, accessToken, userId, http)

    /**
     * `baseUrl` + `path` + query. The server's own path is kept, so a server
     * behind a proxy at `/jellyfin` works. The token is never put in the URL.
     */
    fun url(path: String, query: List<Pair<String, String>> = emptyList()): String =
        URLBuilder(baseUrl).apply {
            appendPathSegments(path.trim('/').split('/'))
            query.forEach { (name, value) -> parameters.append(name, value) }
        }.buildString()

    /** The header value for this API's token (or `token`). */
    fun authorizationHeader(token: String? = accessToken): String = mediaBrowserHeader(identity, token)

    // Sign-in

    /** `POST /Users/AuthenticateByName`. 401 → [JellyfinError.WrongCredentials]. */
    suspend fun authenticateByName(username: String, password: String): AuthenticationResponse =
        try {
            send(
                HttpMethod.Post, "Users/AuthenticateByName", token = null,
                body = TetsubiJson.encodeToString(AuthenticateByNameBody.serializer(), AuthenticateByNameBody(username, password)),
            )
        } catch (e: JellyfinError.Http) {
            throw if (e.status == 401) JellyfinError.WrongCredentials() else e
        }

    /** `POST /QuickConnect/Initiate`: a code to show. 401 → [JellyfinError.QuickConnectDisabled]. */
    suspend fun startQuickConnect(): QuickConnectStart =
        try {
            send(HttpMethod.Post, "QuickConnect/Initiate", token = null)
        } catch (e: JellyfinError.Http) {
            throw if (e.status == 401) JellyfinError.QuickConnectDisabled() else e
        }

    /** `GET /QuickConnect/Connect?secret=…`: approved yet? 404 → [JellyfinError.QuickConnectExpired]. */
    suspend fun pollQuickConnect(secret: String): QuickConnectPoll =
        try {
            send(HttpMethod.Get, "QuickConnect/Connect", query = listOf("secret" to secret), token = null)
        } catch (e: JellyfinError.Http) {
            throw if (e.status == 404) JellyfinError.QuickConnectExpired() else e
        }

    /** `POST /Users/AuthenticateWithQuickConnect`: swaps an approved secret for a token. */
    suspend fun authenticateWithQuickConnect(secret: String): AuthenticationResponse =
        send(
            HttpMethod.Post, "Users/AuthenticateWithQuickConnect", token = null,
            body = TetsubiJson.encodeToString(QuickConnectAuthBody.serializer(), QuickConnectAuthBody(secret)),
        )

    /** `GET /Users/Me`: the signed-in user (also proves the token still works). */
    suspend fun currentUser(): JellyfinUser = send(HttpMethod.Get, "Users/Me")

    // Browsing (the same queries as iOS)

    private val user: String get() = userId ?: throw JellyfinError.NotSignedIn()

    /** Random albums. */
    suspend fun randomAlbums(limit: Int = 12): List<JellyfinAlbum> =
        send<ItemsResponse<JellyfinAlbum>>(
            HttpMethod.Get, "Users/$user/Items",
            listOf(
                "IncludeItemTypes" to "MusicAlbum", "SortBy" to "Random", "limit" to "$limit",
                "ImageTypeLimit" to "1", "Fields" to "AlbumArtists,ProductionYear,Album,ParentId", "Recursive" to "true",
            ),
        ).items

    /** Recently added albums. `Items/Latest` answers with a bare JSON array, not `{Items: […]}`. */
    suspend fun recentlyAddedAlbums(limit: Int = 12): List<JellyfinAlbum> =
        send(
            HttpMethod.Get, "Users/$user/Items/Latest",
            listOf(
                "IncludeItemTypes" to "MusicAlbum", "limit" to "$limit", "ImageTypeLimit" to "1",
                "Fields" to "Type,Album,AlbumArtists,ProductionYear,PremiereDate",
            ),
        )

    /** Favourite albums, newest library additions first (the server can't sort by date favourited). */
    suspend fun favoriteAlbums(): List<JellyfinAlbum> =
        send<ItemsResponse<JellyfinAlbum>>(
            HttpMethod.Get, "Items",
            listOf(
                "UserId" to user, "Recursive" to "true", "IncludeItemTypes" to "MusicAlbum", "Filters" to "IsFavorite",
                "SortBy" to "DateCreated", "SortOrder" to "Descending",
                "Fields" to "Type,Album,AlbumArtists,ProductionYear,PremiereDate,UserData", "ImageTypeLimit" to "1",
            ),
        ).items

    /** One album artist's albums, newest release first. */
    suspend fun albumsByArtist(artistId: String): List<JellyfinAlbum> =
        send<ItemsResponse<JellyfinAlbum>>(
            HttpMethod.Get, "Items",
            listOf(
                "UserId" to user, "AlbumArtistIds" to artistId, "Recursive" to "true", "IncludeItemTypes" to "MusicAlbum",
                "SortBy" to "ProductionYear,SortName", "SortOrder" to "Descending",
                "Fields" to "Type,Album,AlbumArtists,ProductionYear,PremiereDate", "ImageTypeLimit" to "1",
            ),
        ).items

    /** Every album artist, by name (Library ▸ Artists). */
    suspend fun albumArtists(): List<gg.dekiru.musicplayer.data.models.JellyfinArtist> =
        send<ItemsResponse<gg.dekiru.musicplayer.data.models.JellyfinArtist>>(
            HttpMethod.Get, "Artists/AlbumArtists",
            listOf("UserId" to user, "SortBy" to "SortName", "SortOrder" to "Ascending"),
        ).items

    /**
     * Every album's ID, name and release date, and nothing else (no image
     * info, artists or play counts): what On This Day checks. Much smaller
     * than [allAlbums]; details for the few matches come from [albumsByIds].
     */
    suspend fun albumReleaseDates(): List<JellyfinAlbum> =
        send<ItemsResponse<JellyfinAlbum>>(
            HttpMethod.Get, "Users/$user/Items",
            listOf(
                "IncludeItemTypes" to "MusicAlbum", "Recursive" to "true", "Fields" to "ProductionYear,PremiereDate",
                "EnableImages" to "false", "EnableUserData" to "false", "EnableTotalRecordCount" to "false",
            ),
        ).items

    /** These albums in full (artists, year, release date). */
    suspend fun albumsByIds(ids: List<String>): List<JellyfinAlbum> {
        if (ids.isEmpty()) return emptyList()
        return send<ItemsResponse<JellyfinAlbum>>(
            HttpMethod.Get, "Users/$user/Items",
            listOf("Ids" to ids.joinToString(","), "Fields" to "AlbumArtists,Album,ProductionYear,PremiereDate", "ImageTypeLimit" to "1"),
        ).items
    }

    /** Every album (for On This Day, which filters by release date here). */
    suspend fun allAlbums(limit: Int = 10_000): List<JellyfinAlbum> =
        send<ItemsResponse<JellyfinAlbum>>(
            HttpMethod.Get, "Users/$user/Items",
            listOf(
                "IncludeItemTypes" to "MusicAlbum", "Recursive" to "true", "limit" to "$limit",
                "SortBy" to "DateAdded", "SortOrder" to "Descending",
                "Fields" to "AlbumArtists,Album,ProductionYear,PremiereDate", "ImageTypeLimit" to "1",
            ),
        ).items

    /**
     * Distinct albums most recently played, newest first. Tracks sorted by
     * `DatePlayed`, grouped into albums; "played" means `PlayCount > 0`
     * (Jellyfin often leaves `IsPlayed`/`DatePlayed` unset). Not `Items/Resume`,
     * which only lists half-played items.
     */
    suspend fun recentlyPlayedAlbums(limit: Int = 12): List<JellyfinAlbum> {
        val tracks = send<ItemsResponse<JellyfinAlbum>>(
            HttpMethod.Get, "Users/$user/Items",
            listOf(
                "Recursive" to "true", "IncludeItemTypes" to "Audio", "SortBy" to "DatePlayed", "SortOrder" to "Descending",
                "limit" to "${maxOf(limit * 20, 200)}", "Fields" to "Album,AlbumArtists,ProductionYear,UserData",
                "ImageTypeLimit" to "1",
            ),
        ).items
        // One per album: by name and artist, and by album ID too, since tracks of one album can
        // carry different artists (a compilation, a guest) and would otherwise repeat it.
        return tracks.filter { it.hasBeenPlayed }
            .distinctBy { "${it.album ?: it.name.orEmpty()}|${it.artistName}" }
            .distinctBy { it.id }
            .take(limit)
    }

    /** An album's tracks in disc and track order (only its own, should the server ignore `ParentId`). */
    suspend fun tracks(albumId: String): List<JellyfinTrack> =
        send<ItemsResponse<JellyfinTrack>>(
            HttpMethod.Get, "Items",
            listOf(
                "ParentId" to albumId, "UserId" to user, "IncludeItemTypes" to "Audio",
                "SortBy" to "ParentIndexNumber,IndexNumber,SortName", "SortOrder" to "Ascending",
                "Recursive" to "false", "Fields" to "ProviderIds,MediaSources,NormalizationGain,ImageBlurHashes",
            ),
        ).items.filter { it.albumId == null || it.albumId.equals(albumId, ignoreCase = true) }

    /** One album. */
    suspend fun album(albumId: String): JellyfinAlbum = send(HttpMethod.Get, "Users/$user/Items/$albumId")

    // Playlists

    /** The user's music playlists, with the server's song count and running time. */
    suspend fun playlists(): List<JellyfinPlaylist> =
        send<ItemsResponse<JellyfinPlaylist>>(
            HttpMethod.Get, "Users/$user/Items",
            listOf(
                "IncludeItemTypes" to "Playlist", "MediaTypes" to "Audio", "Recursive" to "true",
                "Fields" to "ChildCount,CumulativeRunTimeTicks,DateCreated",
            ),
        ).items

    /** A playlist's tracks in playlist order, each with its position. An unknown playlist is a 404. */
    suspend fun playlistTracks(playlistId: String): List<PlaylistEntry> =
        send<ItemsResponse<PlaylistEntry>>(
            HttpMethod.Get, "Playlists/$playlistId/Items",
            listOf("userId" to user, "Fields" to "MediaSources,NormalizationGain"),
        ).items.mapIndexed { i, entry -> entry.copy(position = i) }

    /**
     * Makes a private audio playlist holding `trackIds` in that order
     * (none: an empty playlist) and answers its ID. The server takes any
     * name, even an empty one, so the caller checks it first.
     */
    suspend fun createPlaylist(name: String, trackIds: List<String>): String {
        val body = buildJsonObject {
            put("Name", name)
            putJsonArray("Ids") { trackIds.forEach { add(it) } }
            put("UserId", user)
            put("MediaType", "Audio")
            put("IsPublic", false)
        }
        return send<CreatedPlaylist>(HttpMethod.Post, "Playlists", body = body.toString()).id
    }

    @Serializable
    private data class CreatedPlaylist(@SerialName("Id") val id: String)

    /** Adds tracks at the end of a playlist, in that order; a track already there is added again. */
    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        fetchText(
            HttpMethod.Post,
            url("Playlists/$playlistId/Items", listOf("ids" to trackIds.joinToString(","), "userId" to user)), accessToken, body = null,
        )
    }

    /**
     * Sets a playlist to exactly these tracks in this order (the same track
     * may come twice; none empties it). Edit mode sends the whole new order
     * with this after each change, not the server's move and remove calls.
     */
    suspend fun setPlaylistItems(playlistId: String, trackIds: List<String>) {
        val body = buildJsonObject { putJsonArray("Ids") { trackIds.forEach { add(it) } } }
        fetchText(HttpMethod.Post, url("Playlists/$playlistId"), accessToken, body.toString())
    }

    /** Renames a playlist; its songs stay. The caller checks the name isn't empty. */
    suspend fun renamePlaylist(playlistId: String, name: String) {
        val body = buildJsonObject { put("Name", name) }
        fetchText(HttpMethod.Post, url("Playlists/$playlistId"), accessToken, body.toString())
    }

    /** Whether the user may change a playlist. Not when the server doesn't know the playlist or can't be asked. */
    suspend fun canEditPlaylist(playlistId: String): Boolean =
        try {
            send<PlaylistUser>(HttpMethod.Get, "Playlists/$playlistId/Users/$user").canEdit
        } catch (e: JellyfinError) {
            false
        }

    @Serializable
    private data class PlaylistUser(@SerialName("CanEdit") val canEdit: Boolean = false)

    /** Deletes a playlist from the server (its songs stay in the library). */
    suspend fun deletePlaylist(playlistId: String) {
        fetchText(HttpMethod.Delete, url("Items/$playlistId"), accessToken, body = null)
    }

    /** Whether the user has favourited an item. */
    suspend fun isFavorite(itemId: String): Boolean =
        send<ItemsResponse<JellyfinAlbum>>(
            HttpMethod.Get, "Items", listOf("Ids" to itemId, "UserId" to user, "Fields" to "UserData"),
        ).items.firstOrNull()?.userData?.isFavorite ?: false

    /** Adds (`POST`) or removes (`DELETE`) a favourite. */
    suspend fun setFavorite(itemId: String, isFavorite: Boolean) {
        fetchText(
            if (isFavorite) HttpMethod.Post else HttpMethod.Delete,
            url("UserFavoriteItems/$itemId", listOf("userId" to user)), accessToken, body = null,
        )
    }

    /**
     * Tells the server a track started (`Sessions/Playing`) or stopped
     * (`Sessions/Playing/Stopped`), so it records plays (PlayCount, DatePlayed:
     * Recently Played) and shows Tetsubi on its dashboard.
     */
    suspend fun reportPlayback(itemId: String, positionSeconds: Double, stopped: Boolean) {
        val body = buildJsonObject {
            put("ItemId", itemId)
            put("PositionTicks", (maxOf(0.0, positionSeconds) * 10_000_000).toLong())
            if (!stopped) {
                put("CanSeek", true)
                put("PlayMethod", "DirectPlay")
            }
        }
        fetchText(
            HttpMethod.Post, url(if (stopped) "Sessions/Playing/Stopped" else "Sessions/Playing"),
            accessToken, body.toString(),
        )
    }

    // Search (as iOS `searchForAlbums` / `searchArtists`)

    /** Albums whose title matches `term`, by name. A blank term asks nothing. */
    suspend fun searchAlbums(term: String, limit: Int = 50): List<JellyfinAlbum> {
        if (term.isBlank()) return emptyList()
        return send<ItemsResponse<JellyfinAlbum>>(
            HttpMethod.Get, "Users/$user/Items",
            listOf(
                "SearchTerm" to term, "IncludeItemTypes" to "MusicAlbum", "Recursive" to "true", "limit" to "$limit",
                "SortBy" to "Name", "SortOrder" to "Ascending",
                "Fields" to "AlbumArtists,Album,ProductionYear,PremiereDate", "ImageTypeLimit" to "1",
            ),
        ).items
    }

    /** The user's music playlists whose name matches `term`, as [playlists] lists them. A blank term asks nothing. */
    suspend fun searchPlaylists(term: String): List<JellyfinPlaylist> {
        if (term.isBlank()) return emptyList()
        return send<ItemsResponse<JellyfinPlaylist>>(
            HttpMethod.Get, "Users/$user/Items",
            listOf(
                "SearchTerm" to term, "IncludeItemTypes" to "Playlist", "MediaTypes" to "Audio", "Recursive" to "true",
                "Fields" to "ChildCount,CumulativeRunTimeTicks,DateCreated",
            ),
        ).items
    }

    /** Album artists whose name matches `term` (the album search only matches titles). */
    suspend fun searchArtists(term: String, limit: Int = 5): List<gg.dekiru.musicplayer.data.models.JellyfinArtist> {
        if (term.isBlank()) return emptyList()
        return send<ItemsResponse<gg.dekiru.musicplayer.data.models.JellyfinArtist>>(
            HttpMethod.Get, "Artists/AlbumArtists",
            listOf("UserId" to user, "searchTerm" to term, "Limit" to "$limit", "SortBy" to "SortName"),
        ).items
    }

    /** The artist's MusicBrainz ID, if the server has matched it. */
    suspend fun musicBrainzArtistId(artistId: String): String? =
        send<ProviderIdsItem>(HttpMethod.Get, "Users/$user/Items/$artistId")
            .providerIds?.get("MusicBrainzArtist")?.takeIf { it.isNotEmpty() }

    @Serializable
    private data class ProviderIdsItem(@SerialName("ProviderIds") val providerIds: Map<String, String>? = null)

    /**
     * An item's cover art. Needs [authorizationHeader] on the request: the
     * token is not in the URL (Jellyfin 12 reads `api_key` as a server key,
     * not a user token).
     */
    fun imageUrl(itemId: String, maxWidth: Int = 500): String =
        url("Items/$itemId/Images/Primary", listOf("maxWidth" to "$maxWidth", "quality" to "90"))

    // Core

    private suspend inline fun <reified T> send(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        token: String? = accessToken,
        body: String? = null,
    ): T = send(method, path, query, token, body, serializer<T>())

    private suspend fun <T> send(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>>,
        token: String?,
        body: String?,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val text = fetchText(method, url(path, query), token, body)
        return try {
            TetsubiJson.decodeFromString(deserializer, text)
        } catch (e: SerializationException) {
            throw JellyfinError.Decode(e)
        } catch (e: IllegalArgumentException) {
            throw JellyfinError.Decode(e)
        }
    }

    private suspend fun fetchText(method: HttpMethod, url: String, token: String?, body: String?): String {
        val response: HttpResponse
        val text: String
        try {
            response = http.request(url) {
                this.method = method
                header(HttpHeaders.Authorization, authorizationHeader(token))
                header(HttpHeaders.Accept, "application/json")
                if (body != null) setBody(TextContent(body, ContentType.Application.Json))
            }
            text = response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JellyfinError.Network(e)
        }
        if (!response.status.isSuccess()) throw JellyfinError.Http(response.status.value, text)
        return text
    }
}
