package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.TetsubiJson
import gg.dekiru.musicplayer.data.api.bodyText
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.playback.settle
import gg.dekiru.musicplayer.ui.playlist.AddToPlaylistModel
import gg.dekiru.musicplayer.ui.playlist.PlaylistAdd
import gg.dekiru.musicplayer.ui.playlist.PlaylistModel
import gg.dekiru.musicplayer.ui.playlist.PlaylistName
import gg.dekiru.musicplayer.ui.playlist.PlaylistWording
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Making playlists, adding songs (and the "already there" question), edit mode's sends, and list art. */
class PlaylistEditingTest {

    private fun playlist(name: String, songs: Int = 0, art: Boolean = false) =
        JellyfinPlaylist(id = "id-$name", name = name, songCount = songs, hasArt = art)

    private fun song(id: String, album: String? = "al-$id") =
        """{"Id":"$id","PlaylistItemId":"e-$id","Name":"Song $id","RunTimeTicks":600000000${album?.let { ""","AlbumId":"$it"""" }.orEmpty()}}"""

    private fun songs(vararg ids: String) = """{"Items":[${ids.joinToString(",") { song(it) }}]}"""

    private fun item(name: String, songs: Int = 2, art: Boolean = false) =
        """{"Id":"id-$name","Name":"$name","Type":"Playlist","ChildCount":$songs,"ImageTags":{${if (art) """"Primary":"tag"""" else ""}}}"""

    private fun listJson(vararg items: String) = """{"Items":[${items.joinToString(",")}],"TotalRecordCount":${items.size}}"""

    private fun ids(body: String) = TetsubiJson.parseToJsonElement(body).jsonObject["Ids"]!!.jsonArray.map { it.jsonPrimitive.content }

    private val HttpRequestData.isPost get() = method == HttpMethod.Post

    // The question about songs already there

    @Test
    fun oneSongAlreadyThereAsksAndSkipAddsNothing() {
        val plan = PlaylistAdd.plan(adding = listOf("s1"), existing = listOf("s0", "s1"))
        assertTrue(plan.needsQuestion)
        assertEquals(listOf("s1"), plan.trackIds(addAgain = true))
        assertEquals(emptyList(), plan.trackIds(addAgain = false))
    }

    @Test
    fun aWholeAlbumWithSomeAlreadyThere() {
        val plan = PlaylistAdd.plan(adding = listOf("a1", "a2", "a3", "a4"), existing = listOf("x", "a3", "a1"))
        assertTrue(plan.needsQuestion)
        assertEquals(listOf("a1", "a2", "a3", "a4"), plan.trackIds(addAgain = true), "Add: all of them")
        assertEquals(listOf("a2", "a4"), plan.trackIds(addAgain = false), "Skip: only those not there, in order")
        // All of it there already: Skip leaves nothing to send.
        val all = PlaylistAdd.plan(adding = listOf("a1", "a2"), existing = listOf("a2", "a1"))
        assertTrue(all.needsQuestion)
        assertEquals(emptyList(), all.trackIds(addAgain = false))
    }

    @Test
    fun noneThereAsksNothing() {
        val plan = PlaylistAdd.plan(adding = listOf("a1", "a2"), existing = listOf("x", "y"))
        assertFalse(plan.needsQuestion)
        assertEquals(listOf("a1", "a2"), plan.trackIds(addAgain = true))
        assertFalse(PlaylistAdd.plan(listOf("a1"), emptyList()).needsQuestion, "an empty playlist")
    }

    @Test
    fun wording() {
        assertEquals("Already in Road trip. Add again?", PlaylistWording.duplicateQuestion("Road trip"))
        assertEquals("Added to Road trip.", PlaylistWording.added(1, "Road trip"))
        assertEquals("Added 12 songs to Road trip.", PlaylistWording.added(12, "Road trip"))
        assertEquals("Couldn't add to Road trip. No connection", PlaylistWording.addFailed("Road trip", "No connection"))
        assertEquals("Couldn't save your changes. No connection", PlaylistWording.saveFailed("No connection"))
        assertEquals("Delete “Road trip”?", PlaylistWording.deleteTitle("Road trip"))
        assertEquals("This removes the playlist from your server. The songs stay in your library.", PlaylistWording.DELETE_MESSAGE)
    }

    @Test
    fun namesAreTrimmedAndEmptyOnesRefused() {
        assertEquals("Road trip", PlaylistName.cleaned("  Road trip \n"))
        assertNull(PlaylistName.cleaned(""))
        assertNull(PlaylistName.cleaned("  \n "))
    }

    // The "Add to Playlist…" sheet

    /** Two playlists the user may change (Mix: a, b) and one they may not. */
    private fun addServer(addStatus: HttpStatusCode = HttpStatusCode.NoContent) = FakeServer { request ->
        when {
            request.path == "/Users/u/Items" -> json(listJson(item("Mix"), item("Theirs"), item("Car")))
            request.path == "/Playlists/id-Theirs/Users/u" -> json("", HttpStatusCode.NotFound)
            request.path.endsWith("/Users/u") -> json("""{"UserId":"u","CanEdit":true}""")
            request.path == "/Playlists/id-Mix/Items" && !request.isPost -> json(songs("a", "b"))
            request.path == "/Playlists/id-Car/Items" && !request.isPost -> json(songs())
            request.path.endsWith("/Items") && request.isPost -> json("", addStatus)
            request.path == "/Playlists" && request.isPost -> if (addStatus == HttpStatusCode.NoContent) json("""{"Id":"new"}""") else json("", addStatus)
            else -> json("", HttpStatusCode.NotFound)
        }
    }

    private val FakeServer.posts get() = requests.filter { it.isPost }

    @Test
    fun theSheetListsThePlaylistsTheUserMayChangeByName() = runTest {
        val server = addServer()
        val model = AddToPlaylistModel(server.api(token = "t", userId = "u"), backgroundScope)
        assertFalse(model.isOpen)
        model.start(emptyList())
        assertFalse(model.isOpen, "nothing to add")
        model.start(listOf("c"))
        assertTrue(model.isOpen)
        assertTrue(model.isLoading)
        settle()
        assertEquals(listOf("Car", "Mix"), model.playlists.map { it.name })
        assertFalse(model.isLoading)
        model.dismiss()
        assertFalse(model.isOpen)
        assertNull(model.notice)
        assertTrue(server.posts.isEmpty())
    }

    @Test
    fun addingSongsThatAreNotThereAsksNothing() = runTest {
        val server = addServer()
        val changed = mutableListOf<String?>()
        val model = AddToPlaylistModel(server.api(token = "t", userId = "u"), backgroundScope) { changed += it }
        model.start(listOf("c", "d"))
        settle()
        model.choose(model.playlists.first { it.name == "Mix" })
        settle()
        assertNull(model.question)
        val post = server.posts.single()
        assertEquals("/Playlists/id-Mix/Items", post.path)
        assertEquals("c,d", post.url.parameters["ids"])
        assertEquals("u", post.url.parameters["userId"])
        assertFalse(model.isOpen, "the sheet closes")
        assertEquals(AddToPlaylistModel.Notice("Added 2 songs to Mix."), model.notice)
        assertEquals(listOf<String?>("id-Mix"), changed)
    }

    @Test
    fun oneSongAlreadyThereAddOrSkip() = runTest {
        val server = addServer()
        val model = AddToPlaylistModel(server.api(token = "t", userId = "u"), backgroundScope)
        model.start(listOf("a"))
        settle()
        model.choose(model.playlists.first { it.name == "Mix" })
        settle()
        assertEquals("Mix", model.question?.playlist?.name)
        assertTrue(server.posts.isEmpty(), "nothing sent before the answer")
        model.answer(addAgain = false)
        settle()
        assertTrue(server.posts.isEmpty(), "Skip: nothing added")
        assertFalse(model.isOpen)
        assertNull(model.notice, "nothing happened, nothing said")

        model.start(listOf("a"))
        settle()
        model.choose(model.playlists.first { it.name == "Mix" })
        settle()
        model.answer(addAgain = true)
        settle()
        assertEquals("a", server.posts.single().url.parameters["ids"])
        assertEquals("Added to Mix.", model.notice?.message)
    }

    @Test
    fun severalSongsSomeThereSkipAddsOnlyTheRest() = runTest {
        val server = addServer()
        val model = AddToPlaylistModel(server.api(token = "t", userId = "u"), backgroundScope)
        model.start(listOf("a", "c", "b", "d"))
        settle()
        model.choose(model.playlists.first { it.name == "Mix" })
        settle()
        assertTrue(model.question != null)
        model.answer(addAgain = false)
        settle()
        assertEquals("c,d", server.posts.single().url.parameters["ids"])
        assertEquals("Added 2 songs to Mix.", model.notice?.message)

        model.start(listOf("a", "c", "b", "d"))
        settle()
        model.choose(model.playlists.first { it.name == "Mix" })
        settle()
        model.answer(addAgain = true)
        settle()
        assertEquals("a,c,b,d", server.posts.last().url.parameters["ids"])
        assertEquals("Added 4 songs to Mix.", model.notice?.message)
    }

    @Test
    fun puttingTheQuestionAwayGoesBackToTheList() = runTest {
        val server = addServer()
        val model = AddToPlaylistModel(server.api(token = "t", userId = "u"), backgroundScope)
        model.start(listOf("a"))
        settle()
        model.choose(model.playlists.first { it.name == "Mix" })
        settle()
        model.questionDismissed()
        assertNull(model.question)
        assertTrue(model.isOpen)
        assertFalse(model.isWorking)
        assertTrue(server.posts.isEmpty())
    }

    @Test
    fun anAddThatFailsSaysSo() = runTest {
        val server = addServer(addStatus = HttpStatusCode.Forbidden)
        val changed = mutableListOf<String?>()
        val model = AddToPlaylistModel(server.api(token = "t", userId = "u"), backgroundScope) { changed += it }
        model.start(listOf("c"))
        settle()
        model.choose(model.playlists.first { it.name == "Mix" })
        settle()
        val notice = model.notice
        assertTrue(notice != null && notice.isError)
        assertTrue(notice.message.startsWith("Couldn't add to Mix. "), notice.message)
        assertTrue(changed.isEmpty())
    }

    @Test
    fun newPlaylistIsMadeWithTheSongs() = runTest {
        val server = addServer()
        val changed = mutableListOf<String?>()
        val model = AddToPlaylistModel(server.api(token = "t", userId = "u"), backgroundScope) { changed += it }
        model.start(listOf("c", "d"))
        settle()
        model.askForName()
        assertTrue(model.isNaming)
        assertFalse(model.create("   "), "an empty name is refused")
        assertTrue(model.isNaming)
        assertTrue(model.create("  Road trip "))
        settle()
        val post = server.posts.single()
        assertEquals("/Playlists", post.path)
        val body = TetsubiJson.parseToJsonElement(post.bodyText).jsonObject
        assertEquals("Road trip", body["Name"]!!.jsonPrimitive.content)
        assertEquals(listOf("c", "d"), ids(post.bodyText))
        assertFalse(model.isOpen)
        assertEquals(AddToPlaylistModel.Notice("Added 2 songs to Road trip."), model.notice)
        assertEquals(listOf<String?>(null), changed)
    }

    @Test
    fun aNewPlaylistThatFailsSaysSo() = runTest {
        val server = addServer(addStatus = HttpStatusCode.Forbidden)
        val model = AddToPlaylistModel(server.api(token = "t", userId = "u"), backgroundScope)
        model.start(listOf("c"))
        settle()
        assertTrue(model.create("Road trip"))
        settle()
        assertTrue(model.notice?.isError == true)
        assertTrue(model.notice!!.message.startsWith("Couldn't create the playlist. "), model.notice!!.message)
    }

    // Edit mode

    /**
     * A playlist with songs a, b, c the user may edit. `save` answers each
     * change sent (POST Playlists/id-Mix); `tracks` is what the server has.
     */
    private class EditServer(var tracks: String, var canEdit: Boolean = true, val save: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        val server = FakeServer { request ->
            when {
                request.path == "/Playlists/id-Mix/Items" -> json(tracks)
                request.path == "/Playlists/id-Mix/Users/u" -> json("""{"UserId":"u","CanEdit":$canEdit}""")
                request.path == "/Playlists/id-Mix" && request.method == HttpMethod.Post -> save(request)
                request.path == "/Items/id-Mix" && request.method == HttpMethod.Delete -> save(request)
                request.path == "/Users/u/Items" -> json("""{"Items":[],"TotalRecordCount":0}""")
                else -> json("", HttpStatusCode.NotFound)
            }
        }
        val saves get() = server.requests.filter { it.path == "/Playlists/id-Mix" }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.editing(edit: EditServer, list: PlaylistsModel? = null): PlaylistModel {
        val model = PlaylistModel(playlist("Mix", songs = 3), edit.server.api(token = "t", userId = "u"), backgroundScope, list)
        model.load()
        model.checkCanEdit()
        settle()
        model.startEditing()
        return model
    }

    @Test
    fun editIsOnlyOfferedWhenTheServerAllowsIt() = runTest {
        val edit = EditServer(songs("a", "b", "c"), canEdit = false) { json("", HttpStatusCode.NoContent) }
        val model = editing(edit)
        assertFalse(model.canEdit)
        assertFalse(model.isEditing)
        model.move(0, 2)
        model.remove(0)
        assertFalse(model.rename("Other"))
        settle()
        assertEquals(listOf("a", "b", "c"), model.entries.map { it.track.id })
        assertTrue(edit.saves.isEmpty())
    }

    @Test
    fun aChangeShowsAtOnceAndSendsTheWholeOrder() = runTest {
        val edit = EditServer(songs("a", "b", "c")) { json("", HttpStatusCode.NoContent) }
        val model = editing(edit)
        assertTrue(model.isEditing)
        model.move(0, 2)
        assertEquals(listOf("b", "c", "a"), model.entries.map { it.track.id }, "before the server answers")
        assertEquals(listOf(0, 1, 2), model.entries.map { it.position }, "rows are told apart by their place")
        settle()
        assertEquals(listOf("b", "c", "a"), ids(edit.saves.single().bodyText))
        model.remove(1)
        assertEquals(listOf("b", "a"), model.entries.map { it.track.id })
        settle()
        assertEquals(listOf("b", "a"), ids(edit.saves.last().bodyText))
        assertEquals(2, edit.saves.size)
        assertNull(model.saveError)
        assertEquals("2 songs · 2 min", model.summary)
    }

    @Test
    fun oneSendAtATimeAndANewerOrderReplacesOneWaiting() = runTest {
        val gate = CompletableDeferred<Unit>()
        val edit = EditServer(songs("a", "b", "c")) {
            gate.await()
            json("", HttpStatusCode.NoContent)
        }
        val model = editing(edit)
        model.move(0, 2) // b c a: sent, the server is slow to answer
        settle()
        assertEquals(1, edit.saves.size)
        model.move(0, 1) // c b a: waits
        model.remove(2) // c b: replaces the one waiting
        model.move(1, 0) // b c: replaces that
        settle()
        assertEquals(1, edit.saves.size, "nothing more is sent while the first is on its way")
        assertEquals(listOf("b", "c"), model.entries.map { it.track.id })
        gate.complete(Unit)
        settle()
        assertEquals(listOf(listOf("b", "c", "a"), listOf("b", "c")), edit.saves.map { ids(it.bodyText) }, "then only the newest order")
        assertNull(model.saveError)
    }

    @Test
    fun aSongThatIsThereTwiceLosesOnlyTheRowRemoved() = runTest {
        val edit = EditServer(songs("a", "b", "a")) { json("", HttpStatusCode.NoContent) }
        val model = editing(edit)
        model.remove(2)
        settle()
        assertEquals(listOf("a", "b"), ids(edit.saves.single().bodyText))
        model.remove(0)
        model.remove(0)
        settle()
        assertEquals(emptyList(), ids(edit.saves.last().bodyText), "the last song removed: an empty playlist")
    }

    @Test
    fun aSendThatFailsReloadsFromTheServerAndSaysSo() = runTest {
        var fail = true
        val edit = EditServer(songs("a", "b", "c")) { if (fail) json("", HttpStatusCode.Forbidden) else json("", HttpStatusCode.NoContent) }
        val model = editing(edit)
        // Someone else changed it meanwhile: the reload shows what the server has now.
        edit.tracks = songs("c", "a", "b", "d")
        val loadsBefore = edit.server.requests.count { it.path == "/Playlists/id-Mix/Items" }
        model.remove(0)
        assertEquals(listOf("b", "c"), model.entries.map { it.track.id })
        settle()
        assertEquals(loadsBefore + 1, edit.server.requests.count { it.path == "/Playlists/id-Mix/Items" })
        assertEquals(listOf("c", "a", "b", "d"), model.entries.map { it.track.id })
        assertTrue(model.saveError.orEmpty().startsWith("Couldn't save your changes. "), model.saveError)
        assertTrue(model.isEditing, "still editing")
        // The next change goes through and the message goes.
        fail = false
        model.remove(3)
        settle()
        assertEquals(listOf("c", "a", "b"), ids(edit.saves.last().bodyText))
        assertNull(model.saveError)
    }

    @Test
    fun aFailedSendDropsTheOrderThatWasWaiting() = runTest {
        val gate = CompletableDeferred<Unit>()
        val edit = EditServer(songs("a", "b", "c")) {
            gate.await()
            json("", HttpStatusCode.InternalServerError)
        }
        val model = editing(edit)
        model.remove(0)
        settle()
        model.remove(0) // waits; made against a list the server never took
        gate.complete(Unit)
        settle()
        assertEquals(1, edit.saves.size)
        assertEquals(listOf("a", "b", "c"), model.entries.map { it.track.id })
        assertTrue(model.saveError != null)
    }

    @Test
    fun renameTrimsSendsOnlyTheNameAndRefusesAnEmptyOne() = runTest {
        val edit = EditServer(songs("a", "b")) { json("", HttpStatusCode.NoContent) }
        val model = editing(edit)
        assertFalse(model.rename("  \n"))
        assertTrue(model.rename("Mix"), "the same name: nothing to send")
        settle()
        assertTrue(edit.saves.isEmpty())
        assertTrue(model.rename("  Sunday "))
        assertEquals("Sunday", model.playlist.name, "at once")
        settle()
        assertEquals(TetsubiJson.parseToJsonElement("""{"Name":"Sunday"}"""), TetsubiJson.parseToJsonElement(edit.saves.single().bodyText))
    }

    @Test
    fun aRenameThatFailsPutsTheOldNameBack() = runTest {
        val edit = EditServer(songs("a", "b")) { json("", HttpStatusCode.Forbidden) }
        val model = editing(edit)
        model.rename("Sunday")
        settle()
        assertEquals("Mix", model.playlist.name)
        assertTrue(model.saveError.orEmpty().startsWith("Couldn't save your changes. "), model.saveError)
    }

    @Test
    fun deleteRemovesItFromTheServerAndLeavesThePage() = runTest {
        val edit = EditServer(songs("a", "b")) { json("", HttpStatusCode.NoContent) }
        val model = editing(edit)
        var left = 0
        model.delete { left += 1 }
        assertTrue(model.isDeleting)
        settle()
        assertEquals(1, left)
        val delete = edit.server.requests.single { it.method == HttpMethod.Delete }
        assertEquals("/Items/id-Mix", delete.path)
        assertFalse(model.isDeleting)
        assertNull(model.saveError)
    }

    @Test
    fun aDeleteThatFailsStaysOnThePageAndSaysSo() = runTest {
        val edit = EditServer(songs("a", "b")) { json("", HttpStatusCode.Forbidden) }
        val model = editing(edit)
        var left = 0
        model.delete { left += 1 }
        settle()
        assertEquals(0, left)
        assertTrue(model.saveError.orEmpty().startsWith("Couldn't delete the playlist. "), model.saveError)
        assertEquals(listOf("a", "b"), model.entries.map { it.track.id })
    }

    @Test
    fun changesRefreshTheLibrarysList() = runTest {
        val edit = EditServer(songs("a", "b")) { json("", HttpStatusCode.NoContent) }
        val list = PlaylistsModel(edit.server.api(token = "t", userId = "u"), InMemoryPreferences(), backgroundScope)
        val model = editing(edit, list)
        fun listLoads() = edit.server.requests.count { it.path == "/Users/u/Items" }
        assertEquals("al-a", list.artItemId(playlist("Mix")), "the page's first song gives the list its picture")
        val before = listLoads()
        model.move(0, 1)
        settle()
        assertEquals(before + 1, listLoads())
        assertEquals("al-b", list.artItemId(playlist("Mix")), "the new first song's")
        model.rename("Sunday")
        settle()
        assertEquals(before + 2, listLoads())
        model.delete {}
        settle()
        assertEquals(before + 3, listLoads())
        assertEquals("id-Mix", list.artItemId(playlist("Mix")), "forgotten once it's gone")
    }

    // Art in the Library's list and Home's row

    @Test
    fun aPlaylistWithoutAPictureBorrowsItsFirstSongsOnce() = runTest {
        val server = FakeServer { request ->
            if (request.path.startsWith("/Playlists/")) json(songs("a", "b")) else json(listJson(item("Mix")))
        }
        val model = PlaylistsModel(server.api(token = "t", userId = "u"), InMemoryPreferences(), backgroundScope)
        val mix = playlist("Mix", songs = 2)
        assertEquals("id-Mix", model.artItemId(mix), "the placeholder until the song is known")
        model.lookUpArt(mix)
        model.lookUpArt(mix)
        settle()
        assertEquals("al-a", model.artItemId(mix))
        model.lookUpArt(mix)
        settle()
        assertEquals(listOf("/Playlists/id-Mix/Items"), server.requests.map { it.path }, "one call, remembered")
        // Songs were added: looked up again the next time it shows.
        model.changed("id-Mix")
        model.lookUpArt(mix)
        settle()
        assertEquals(2, server.requests.count { it.path == "/Playlists/id-Mix/Items" })
    }

    @Test
    fun noArtLookupForOwnPictureNoSongsOrOffline() = runTest {
        val server = FakeServer { json(songs("a")) }
        val model = PlaylistsModel(server.api(token = "t", userId = "u"), InMemoryPreferences(), backgroundScope)
        val pictured = playlist("Pictured", songs = 2, art = true)
        model.lookUpArt(pictured)
        model.lookUpArt(playlist("Empty", songs = 0))
        settle()
        assertEquals("id-Pictured", model.artItemId(pictured))
        model.networkChanged(online = false)
        model.lookUpArt(playlist("Mix", songs = 2))
        settle()
        assertTrue(server.requests.isEmpty())
        assertEquals("id-Mix", model.artItemId(playlist("Mix", songs = 2)))
    }

    // Go to Album

    @Test
    fun goToAlbumHandsOverTheArtistSoTheWholeSetOpens() = runTest {
        // The album page finds a multi-disc set's other discs through the album's artist.
        val server = FakeServer { request ->
            if (request.path.startsWith("/Playlists")) {
                json("""{"Items":[${song("s1", album = "d2")}]}""")
            } else {
                json("""{"Id":"d2","Name":"Set (Disc 2)","Type":"MusicAlbum","AlbumArtists":[{"Name":"Band","Id":"artist-1"}]}""")
            }
        }
        val model = PlaylistModel(playlist("Mix"), server.api(token = "t", userId = "u"), backgroundScope)
        model.load()
        settle()
        val opened = mutableListOf<JellyfinAlbum>()
        model.openAlbum(model.entries[0], opened::add)
        settle()
        assertEquals("/Users/u/Items/d2", server.requests.last().path)
        assertEquals("artist-1", opened.single().artistId)
    }
}
