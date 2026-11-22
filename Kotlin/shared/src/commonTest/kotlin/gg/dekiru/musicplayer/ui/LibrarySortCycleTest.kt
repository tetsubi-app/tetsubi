package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.ui.library.LibraryModel
import gg.dekiru.musicplayer.ui.library.LibrarySort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tapping the selected Albums tab steps the sort: Name → Artist → Year → Date Added → Random → Name. */
class LibrarySortCycleTest {

    private fun model(scope: CoroutineScope, prefs: InMemoryPreferences = InMemoryPreferences()) = LibraryModel(
        FakeServer { json("""{"Items":[]}""") }.api(token = "t", userId = "u"),
        FavoritesStore(InMemoryPreferences()) { 0 }, prefs, scope, Random(7),
    )

    @Test
    fun theOrderIsTheMenusAndGoesRound() {
        assertEquals(LibrarySort.ARTIST, LibrarySort.NAME.next)
        assertEquals(LibrarySort.YEAR, LibrarySort.ARTIST.next)
        assertEquals(LibrarySort.DATE_ADDED, LibrarySort.YEAR.next)
        assertEquals(LibrarySort.RANDOM, LibrarySort.DATE_ADDED.next)
        assertEquals(LibrarySort.NAME, LibrarySort.RANDOM.next)
    }

    @Test
    fun cyclingSetsAndRemembersTheSort() = runTest {
        val prefs = InMemoryPreferences()
        val model = model(backgroundScope, prefs)
        assertEquals(LibrarySort.NAME, model.sortOption)
        assertEquals(LibrarySort.ARTIST, model.cycleSort())
        assertEquals(LibrarySort.YEAR, model.cycleSort())
        assertEquals(LibrarySort.YEAR, model.sortOption)
        // Remembered as the menu's choice.
        assertEquals("Year", prefs.getString(LibraryModel.SORT_KEY))
        assertEquals(LibrarySort.YEAR, model(backgroundScope, prefs).sortOption)
        assertEquals(LibrarySort.DATE_ADDED, model.cycleSort())
        assertEquals(LibrarySort.RANDOM, model.cycleSort())
        assertEquals(LibrarySort.NAME, model.cycleSort())
    }

    @Test
    fun reachingRandomDealsAShuffle() = runTest {
        val model = model(backgroundScope)
        model.receive((0 until 60).map { JellyfinAlbum(id = "a$it", name = "Album $it", albumArtists = listOf(JellyfinPerson(name = "X"))) })
        model.sortOption = LibrarySort.DATE_ADDED
        assertEquals(60, model.sortedAlbums.size)
        assertEquals(LibrarySort.RANDOM, model.cycleSort())
        // Random's first page, as picking it from the menu.
        assertEquals(24, model.sortedAlbums.size)
    }
}
