package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.playback.settle
import gg.dekiru.musicplayer.ui.library.AlphaIndex
import gg.dekiru.musicplayer.ui.library.ArtistModel
import gg.dekiru.musicplayer.ui.library.GridZoom
import gg.dekiru.musicplayer.ui.library.LibraryDisplayMode
import gg.dekiru.musicplayer.ui.library.LibraryFilter
import gg.dekiru.musicplayer.ui.library.LibraryModel
import gg.dekiru.musicplayer.ui.library.LibrarySort
import gg.dekiru.musicplayer.ui.library.LibraryTab
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Library: sorting, filtering, the A–Z index, Random's pages, offline, remembered choices. */
class LibraryModelTest {

    private fun album(name: String, artist: String = "X", year: Int? = null, id: String = "id-$name", date: String? = null) =
        JellyfinAlbum(id = id, name = name, premiereDate = date, productionYear = year, albumArtists = listOf(JellyfinPerson(name = artist)))

    private fun albums(count: Int) = (0 until count).map { album("Album $it", id = "a$it") }

    private fun downloaded(id: String, title: String, artist: String = "Band", at: Long = 0) =
        DownloadedAlbum(id, title, artist, null, "flac", at, 0, 0.0, emptyList(), "/d/$id")

    private fun model(
        scope: CoroutineScope,
        server: FakeServer = FakeServer { json("""{"Items":[]}""") },
        prefs: InMemoryPreferences = InMemoryPreferences(),
    ) = LibraryModel(server.api(token = "t", userId = "u"), FavoritesStore(InMemoryPreferences()) { 0 }, prefs, scope, Random(7))

    // A–Z index

    @Test
    fun indexBuckets() {
        assertEquals("A", AlphaIndex.bucket("Abbey Road"))
        assertEquals("E", AlphaIndex.bucket("émile"))
        assertEquals("#", AlphaIndex.bucket("13 Songs"))
        assertEquals("#", AlphaIndex.bucket("...out flew reason"))
        assertEquals("#", AlphaIndex.bucket(""))
        assertEquals("T", AlphaIndex.bucket("  the band"))
        assertEquals(AlphaIndex.OTHER, AlphaIndex.bucket("ヘイトスピーチ"))
        assertEquals(AlphaIndex.OTHER, AlphaIndex.bucket("Привет"))
    }

    @Test
    fun indexLabelOrder() {
        assertEquals("#", AlphaIndex.labels.first())
        assertEquals(AlphaIndex.OTHER, AlphaIndex.labels.last())
        assertEquals(28, AlphaIndex.labels.size)
        assertEquals("A", AlphaIndex.labels[1])
    }

    @Test
    fun indexJumpsToTheLetterOrTheNextWithItems() {
        val list = listOf(album("Abba"), album("Air"), album("Cream"), album("ゆらゆら"))
        val first = AlphaIndex.firstIds(list, { it.displayName }) { it.id }
        assertEquals(mapOf("A" to "id-Abba", "C" to "id-Cream", AlphaIndex.OTHER to "id-ゆらゆら"), first)
        assertEquals("id-Cream", AlphaIndex.target("B", first))
        assertEquals("id-ゆらゆら", AlphaIndex.target("Z", first))
        assertEquals("id-Abba", AlphaIndex.target("#", first))
        assertNull(AlphaIndex.target("A", emptyMap()))
    }

    // Sorting

    @Test
    fun sortByNameFollowsTheIndex() {
        val list = listOf(album("Zebra"), album("ゆらゆら"), album("émile"), album("10 Songs"), album("apple"), album("Mango"))
        assertEquals(
            listOf("10 Songs", "apple", "émile", "Mango", "Zebra", "ゆらゆら"),
            LibraryModel.sort(list, LibrarySort.NAME).map { it.displayName },
        )
    }

    @Test
    fun sortByArtist() {
        val list = listOf(album("A", artist = "Zebra"), album("B", artist = "Alpha"), album("C", artist = "Delta"))
        assertEquals(listOf("Alpha", "Delta", "Zebra"), LibraryModel.sort(list, LibrarySort.ARTIST).map { it.artistName })
    }

    @Test
    fun sortByYearNewestFirstNoYearLast() {
        val list = listOf(album("NoYear"), album("Old", year = 2000), album("New", year = 2025), album("Mid", year = 2015))
        assertEquals(listOf(2025, 2015, 2000, null), LibraryModel.sort(list, LibrarySort.YEAR).map { it.productionYear })
    }

    @Test
    fun sortByYearUsesTheFullReleaseDateThenJan1OfTheYear() {
        val list = listOf(
            album("Spring", year = 2026, date = "2026-03-01T00:00:00.0000000Z"),
            album("YearOnly", year = 2026),
            album("Autumn", year = 2026, date = "2026-10-16T00:00:00.0000000Z"),
            album("Late2025", year = 2025, date = "2025-12-31T00:00:00.0000000Z"),
            album("SameDayAddedLater", year = 2026, date = "2026-03-01T00:00:00.0000000Z"),
        )
        assertEquals(listOf("Autumn", "Spring", "SameDayAddedLater", "YearOnly", "Late2025"),
            LibraryModel.sort(list, LibrarySort.YEAR).map { it.displayName })
    }

    @Test
    fun dateAddedKeepsTheServersOrder() {
        val list = listOf(album("First"), album("Second"), album("Third"))
        assertEquals(list, LibraryModel.sort(list, LibrarySort.DATE_ADDED))
    }

    @Test
    fun theIndexOnlyForAlphabeticalSorts() = runTest {
        val model = model(backgroundScope)
        model.sortOption = LibrarySort.NAME
        assertEquals("T", model.indexKey!!(album("T")))
        model.sortOption = LibrarySort.ARTIST
        assertEquals("Band", model.indexKey!!(album("T", artist = "Band")))
        model.sortOption = LibrarySort.YEAR
        assertNull(model.indexKey)
        model.sortOption = LibrarySort.RANDOM
        assertNull(model.indexKey)
    }

    // Grid

    @Test
    fun threeAcrossOnAPhoneWithTheStrip() {
        assertEquals(3, LibraryModel.gridLayout(393f - 4 - 22).columns)
        assertEquals(3.5f, LibraryModel.gridLayout(393f - 4 - 22).spacing, 0.01f)
        assertEquals(8f, LibraryModel.gridLayout(402f - 4 - 22).spacing, 0.01f)
        assertEquals(2, LibraryModel.gridLayout(375f - 4 - 22).columns)
        assertEquals(3, LibraryModel.gridLayout(411f - 4 - 22).columns) // a typical Android phone
        assertEquals(LibraryModel.gridLayout(1000f).columns, 7)
        assertEquals(12f, LibraryModel.gridLayout(1000f).spacing)
    }

    // Wide grids (cards per row, zoom)

    @Test
    fun wideIsTheDesktopOrA600dpAndroidWindow() {
        assertTrue(LibraryModel.isWide(isAndroid = false, windowWidth = 400f))
        assertFalse(LibraryModel.isWide(isAndroid = true, windowWidth = 411f))
        assertTrue(LibraryModel.isWide(isAndroid = true, windowWidth = 600f))
        assertFalse(LibraryModel.isWide(isAndroid = true, windowWidth = 599.9f))
    }

    @Test
    fun phonesKeepTheFixedGrid() {
        val grid = LibraryModel.cardGrid(393f - 4 - 22, wide = false, chosen = 5, displayScale = 3f)
        assertEquals(3, grid.columns)
        assertEquals(3.5f, grid.spacing, 0.01f)
        assertEquals(120f, grid.cardWidth)
        assertEquals(1f, grid.textScale)
        assertEquals(300, grid.imageSize)
    }

    @Test
    fun wideAutomaticIsTodaysCountKeptToThreeToTen() {
        assertEquals(7, LibraryModel.cardGrid(1000f, wide = true).columns)
        assertEquals(3, LibraryModel.cardGrid(300f, wide = true).columns) // today 2
        assertEquals(10, LibraryModel.cardGrid(3000f, wide = true).columns) // today 22
    }

    @Test
    fun wideCardsFillTheirColumns() {
        val grid = LibraryModel.cardGrid(1000f, wide = true, chosen = 4)
        assertEquals(4, grid.columns)
        assertEquals(12f, grid.spacing)
        assertEquals((1000f - 3 * 12) / 4, grid.cardWidth, 0.01f)
        assertEquals(grid.columns * grid.cardWidth + (grid.columns - 1) * grid.spacing, 1000f, 0.01f)
        // At ten, a wider window makes bigger cards, not an eleventh column.
        val narrow = LibraryModel.cardGrid(2000f, wide = true, chosen = 10)
        val wider = LibraryModel.cardGrid(2500f, wide = true, chosen = 10)
        assertEquals(10, wider.columns)
        assertTrue(wider.cardWidth > narrow.cardWidth)
        // A saved choice outside 3…10 is kept inside.
        assertEquals(10, LibraryModel.cardGrid(1000f, wide = true, chosen = 14).columns)
        assertEquals(3, LibraryModel.cardGrid(1000f, wide = true, chosen = 1).columns)
    }

    @Test
    fun fixedColumnsFillTheirColumnsOnAnyWidth() {
        val phone3 = LibraryModel.fixedCardGrid(393f - 8, 3, displayScale = 3f)
        assertEquals(3, phone3.columns)
        assertEquals(12f, phone3.spacing)
        assertEquals((385f - 24) / 3, phone3.cardWidth, 0.01f)
        val phone5 = LibraryModel.fixedCardGrid(360f - 8, 5)
        assertEquals(5, phone5.columns)
        assertEquals((352f - 48) / 5, phone5.cardWidth, 0.01f)
        assertEquals(1f, phone5.textScale) // the phone's text, cut short
        val desktop5 = LibraryModel.fixedCardGrid(1000f, 5)
        assertEquals((1000f - 48) / 5 / 120f, desktop5.textScale, 0.001f)
        assertEquals(3f, LibraryModel.fixedCardGrid(3000f, 3).textScale) // at most ×3
        // Wide with a chosen count is the same layout.
        assertEquals(LibraryModel.cardGrid(1000f, wide = true, chosen = 4), LibraryModel.fixedCardGrid(1000f, 4))
    }

    @Test
    fun textScalesWithTheCardBetweenOneAndThree() {
        assertEquals(1f, LibraryModel.cardGrid(600f, wide = true, chosen = 10).textScale) // tiny cards: never smaller
        assertEquals(2f, LibraryModel.cardGrid(3 * 240f + 2 * 12, wide = true, chosen = 3).textScale, 0.001f)
        assertEquals(3f, LibraryModel.cardGrid(4000f, wide = true, chosen = 3).textScale) // a huge monitor
        // The platform's UI scale counts as the card's normal size.
        assertEquals(1f, LibraryModel.cardGrid(3 * 240f + 2 * 24, wide = true, chosen = 3, scale = 2f).textScale, 0.001f)
    }

    @Test
    fun coversAreFetchedAtTheirDrawnSizeInFourSteps() {
        assertEquals(300, LibraryModel.imageSize(120f))
        assertEquals(300, LibraryModel.imageSize(300f))
        assertEquals(600, LibraryModel.imageSize(301f))
        assertEquals(1000, LibraryModel.imageSize(900f))
        assertEquals(1500, LibraryModel.imageSize(1400f))
        assertEquals(1500, LibraryModel.imageSize(4000f))
        // 4 across 1000 dp at 2× density: 241 dp cards, 482 px.
        assertEquals(600, LibraryModel.cardGrid(1000f, wide = true, chosen = 4, displayScale = 2f).imageSize)
    }

    @Test
    fun homePhonesKeepTheFixedCards() {
        val row = LibraryModel.homeRow(393f, wide = false, chosen = 5, displayScale = 3f)
        assertEquals(140f, row.cardWidth)
        assertEquals(12f, row.spacing)
        assertEquals(1f, row.textScale)
        assertEquals(300, row.imageSize)
    }

    @Test
    fun homeAutomaticIsTodaysWholeCardsKeptToThreeToTen() {
        // 1000 visible after the 16 dp leading: (1000 + 12) / 152 = 6.66 → 6 (as iOS).
        assertEquals(6, LibraryModel.homeRow(1016f, wide = true).columns)
        assertEquals(6, LibraryModel.homeRow(916f, wide = true).columns) // 6 × 140 + 5 × 12 = 900, exactly
        assertEquals(5, LibraryModel.homeRow(915f, wide = true).columns)
        assertEquals(3, LibraryModel.homeRow(400f, wide = true).columns) // today 2
        assertEquals(10, LibraryModel.homeRow(3000f, wide = true).columns) // today 19
    }

    @Test
    fun homeShowsTheChosenCardsAndAThirdOfTheNext() {
        for (width in listOf(700f, 1024f, 1600f, 2400f)) {
            for (chosen in 3..10) {
                val row = LibraryModel.homeRow(width, wide = true, chosen = chosen)
                assertEquals(chosen, row.columns)
                // Leading, n whole cards with their gaps, then a third of the next: the whole width.
                assertEquals(width, LibraryModel.HOME_WIDE_LEADING + row.columns * (row.cardWidth + row.spacing) + row.cardWidth / 3, 0.01f)
            }
        }
        assertEquals((1000f - 4 * 12) / (4 + 1f / 3), LibraryModel.homeRow(1016f, wide = true, chosen = 4).cardWidth, 0.01f)
        assertEquals(10, LibraryModel.homeRow(1016f, wide = true, chosen = 14).columns)
        assertEquals(3, LibraryModel.homeRow(1016f, wide = true, chosen = 1).columns)
    }

    @Test
    fun homeTextScalesFromA140CardBetweenOneAndThree() {
        assertEquals(1f, LibraryModel.homeRow(800f, wide = true, chosen = 10).textScale) // tiny cards: never smaller
        val twice = 16 + 3 * (280 + 12) + 280f / 3
        assertEquals(2f, LibraryModel.homeRow(twice, wide = true, chosen = 3).textScale, 0.001f)
        assertEquals(3f, LibraryModel.homeRow(4000f, wide = true, chosen = 3).textScale)
        // The platform's UI scale counts as the card's normal size.
        val row = LibraryModel.homeRow(2000f, wide = true, chosen = 4, scale = 1.6f)
        assertEquals(row.cardWidth / 224, row.textScale, 0.001f)
    }

    @Test
    fun homeCoversAreFetchedAtTheirDrawnSize() {
        // Four across 1000 dp: 220 dp cards.
        assertEquals(600, LibraryModel.homeRow(1016f, wide = true, chosen = 4, displayScale = 2f).imageSize)
        assertEquals(1000, LibraryModel.homeRow(1016f, wide = true, chosen = 4, displayScale = 3f).imageSize)
        assertEquals(300, LibraryModel.homeRow(1016f, wide = true, chosen = 10, displayScale = 2f).imageSize)
        assertEquals(1500, LibraryModel.homeRow(6000f, wide = true, chosen = 3, displayScale = 2f).imageSize)
    }

    @Test
    fun zoomStepsOneColumnAndStopsAtTheEnds() {
        assertEquals(4, LibraryModel.zoomed(5, zoomIn = true))
        assertEquals(6, LibraryModel.zoomed(5, zoomIn = false))
        assertEquals(3, LibraryModel.zoomed(3, zoomIn = true))
        assertEquals(10, LibraryModel.zoomed(10, zoomIn = false))
        assertFalse(LibraryModel.canZoomIn(3))
        assertTrue(LibraryModel.canZoomIn(4))
        assertFalse(LibraryModel.canZoomOut(10))
        assertTrue(LibraryModel.canZoomOut(9))
    }

    @Test
    fun theFirstZoomStartsFromWhatsShownAndIsRemembered() {
        val prefs = InMemoryPreferences()
        val zoom = GridZoom(prefs)
        assertNull(zoom.columns)
        assertFalse(zoom.canZoomIn, "nothing drawn yet")
        zoom.shown = 7 // automatic
        zoom.zoomIn()
        assertEquals(6, zoom.columns)
        zoom.shown = 6
        repeat(5) { zoom.zoomIn() }
        assertEquals(3, zoom.columns)
        assertFalse(zoom.canZoomIn)
        assertTrue(zoom.canZoomOut)
        assertEquals("3", prefs.getString(LibraryModel.GRID_COLUMNS_KEY))
        assertEquals(3, GridZoom(prefs).columns)
        repeat(9) { zoom.zoomOut() }
        assertEquals(10, zoom.columns)
        assertFalse(zoom.canZoomOut)
        prefs.putString(LibraryModel.GRID_COLUMNS_KEY, "nonsense")
        assertNull(GridZoom(prefs).columns)
        prefs.putString(LibraryModel.GRID_COLUMNS_KEY, "14")
        assertNull(GridZoom(prefs).columns, "outside 3…10 is automatic")
    }

    @Test
    fun artistsAreAGridByDefaultRememberedApartFromAlbums() = runTest {
        val prefs = InMemoryPreferences()
        val first = model(backgroundScope, prefs = prefs)
        assertEquals(LibraryDisplayMode.GRID, first.artistsDisplayMode)
        first.artistsDisplayMode = LibraryDisplayMode.LIST
        assertEquals(LibraryDisplayMode.GRID, first.displayMode, "the albums' view is its own")
        val again = model(backgroundScope, prefs = prefs)
        assertEquals(LibraryDisplayMode.LIST, again.artistsDisplayMode)
        assertEquals(LibraryDisplayMode.GRID, again.displayMode)
        again.displayMode = LibraryDisplayMode.LIST
        again.artistsDisplayMode = LibraryDisplayMode.GRID
        assertEquals(LibraryDisplayMode.GRID, model(backgroundScope, prefs = prefs).artistsDisplayMode)
    }

    // Random

    private fun randomModel(scope: CoroutineScope, count: Int): LibraryModel {
        val model = model(scope)
        model.receive(albums(count))
        model.sortOption = LibrarySort.RANDOM
        return model
    }

    @Test
    fun randomShowsAPageThenMoreAtTheEnd() = runTest {
        val model = randomModel(backgroundScope, 60)
        assertEquals(24, model.sortedAlbums.size)
        model.loadMoreIfNeeded(model.sortedAlbums[10]) // not the last: nothing
        assertEquals(24, model.sortedAlbums.size)
        model.loadMoreIfNeeded(model.sortedAlbums.last())
        assertEquals(48, model.sortedAlbums.size)
        model.loadMoreIfNeeded(model.sortedAlbums.last())
        assertEquals(60, model.sortedAlbums.size)
        model.loadMoreIfNeeded(model.sortedAlbums.last())
        assertEquals(60, model.sortedAlbums.size)
        assertEquals(albums(60).map { it.id }.toSet(), model.sortedAlbums.map { it.id }.toSet())
    }

    @Test
    fun randomOrderSurvivesARefreshAndNewAlbumsGoLast() = runTest {
        val model = randomModel(backgroundScope, 60)
        model.loadMoreIfNeeded(model.sortedAlbums.last())
        val before = model.sortedAlbums.map { it.id }
        model.receive(albums(61))
        assertEquals(before, model.sortedAlbums.map { it.id }.take(48))
    }

    @Test
    fun shufflingAgainStartsOverAtOnePage() = runTest {
        val model = randomModel(backgroundScope, 60)
        val first = model.sortedAlbums.map { it.id }
        model.loadMoreIfNeeded(model.sortedAlbums.last())
        model.shuffleAgain()
        assertEquals(24, model.sortedAlbums.size)
        assertNotEquals(first, model.sortedAlbums.map { it.id })
        assertEquals(1, model.shuffles)
    }

    @Test
    fun otherSortsShowEverything() = runTest {
        val model = randomModel(backgroundScope, 60)
        model.sortOption = LibrarySort.NAME
        assertEquals(60, model.sortedAlbums.size)
    }

    @Test
    fun pullToReshuffleFetchesAgain() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"a"},{"Id":"b"},{"Id":"c"}]}""") }
        val model = model(backgroundScope, server)
        model.sortOption = LibrarySort.RANDOM
        model.refreshRandom()
        settle()
        assertEquals(setOf("a", "b", "c"), model.sortedAlbums.map { it.id }.toSet())
        assertEquals(1, server.requests.size)
        assertFalse(model.isRefreshing)
    }

    // Filter and downloads

    @Test
    fun downloadedFilterNeedsEveryDisc() = runTest {
        val model = model(backgroundScope)
        model.receive(listOf(album("Set (Disc 1)", id = "d1"), album("Set (Disc 2)", id = "d2"), album("Solo", id = "s")))
        model.downloadsChanged(listOf(downloaded("d1", "Set (Disc 1)"), downloaded("s", "Solo")))
        model.filter = LibraryFilter.DOWNLOADED
        assertEquals(listOf("s"), model.sortedAlbums.map { it.id })
        model.downloadsChanged(listOf(downloaded("d1", "Set (Disc 1)"), downloaded("d2", "Set (Disc 2)"), downloaded("s", "Solo")))
        assertEquals(listOf("d1", "s"), model.sortedAlbums.map { it.id })
        // Being downloaded again doesn't count.
        model.downloadsChanged(listOf(downloaded("s", "Solo")), activeIds = setOf("s"))
        assertEquals(emptyList(), model.sortedAlbums)
        model.filter = LibraryFilter.ALL
        assertEquals(listOf("Set", "Solo"), model.sortedAlbums.map { it.displayName })
    }

    @Test
    fun offlineShowsTheDownloadedAlbumsAndAsksNothing() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"a","Name":"Server Album"}]}""") }
        val model = model(backgroundScope, server)
        model.downloadsChanged(listOf(downloaded("x", "Kept")))
        model.networkChanged(online = false)
        settle()
        assertTrue(server.requests.isEmpty())
        assertTrue(model.isOffline)
        assertEquals(listOf("x"), model.sortedAlbums.map { it.id })

        model.networkChanged(online = true)
        settle()
        assertEquals(listOf("a"), model.sortedAlbums.map { it.id })
        assertEquals("/Users/u/Items", server.requests.single().path)
    }

    @Test
    fun aFailedFirstLoadShowsTheError() = runTest {
        val server = FakeServer { json("", HttpStatusCode.BadGateway) }
        val model = model(backgroundScope, server)
        model.networkChanged(online = true)
        settle()
        assertTrue(model.errorMessage!!.startsWith("Failed to load library: HTTP 502"))
        assertFalse(model.isLoading)
    }

    @Test
    fun aFailedReloadKeepsWhatsShown() = runTest {
        var fail = false
        val server = FakeServer { if (fail) json("", HttpStatusCode.BadGateway) else json("""{"Items":[{"Id":"a"}]}""") }
        val model = model(backgroundScope, server)
        model.load()
        settle()
        fail = true
        model.load()
        settle()
        assertNull(model.errorMessage)
        assertEquals(listOf("a"), model.sortedAlbums.map { it.id })
    }

    // Remembered choices

    @Test
    fun tabSortAndViewAreRemembered() = runTest {
        val prefs = InMemoryPreferences()
        val first = model(backgroundScope, prefs = prefs)
        assertEquals(LibraryTab.ALBUMS, first.tab)
        assertEquals(LibrarySort.NAME, first.sortOption)
        assertEquals(LibraryDisplayMode.GRID, first.displayMode)
        first.tab = LibraryTab.ARTISTS
        first.sortOption = LibrarySort.DATE_ADDED
        first.displayMode = LibraryDisplayMode.LIST
        first.filter = LibraryFilter.DOWNLOADED
        assertEquals("Date Added", prefs.getString(LibraryModel.SORT_KEY))

        val again = model(backgroundScope, prefs = prefs)
        assertEquals(LibraryTab.ARTISTS, again.tab)
        assertEquals(LibrarySort.DATE_ADDED, again.sortOption)
        assertEquals(LibraryDisplayMode.LIST, again.displayMode)
        assertEquals("Downloaded", prefs.getString(LibraryModel.FILTER_KEY))
        assertEquals(LibraryFilter.DOWNLOADED, again.filter, "the filter is kept")
    }

    @Test
    fun aStrangeSavedValueFallsBackToTheDefault() = runTest {
        val prefs = InMemoryPreferences()
        prefs.putString(LibraryModel.SORT_KEY, "Colour")
        assertEquals(LibrarySort.NAME, model(backgroundScope, prefs = prefs).sortOption)
    }

    // Artists and Favourites

    @Test
    fun artistsSortedLikeTheStripAndLoadedOnce() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"1","Name":"Ólafur"},{"Id":"2","Name":"!!!"},{"Id":"3","Name":"Air"},{"Id":"4","Name":"坂本龍一"}]}""") }
        val model = model(backgroundScope, server)
        model.loadArtists()
        settle()
        assertEquals(listOf("!!!", "Air", "Ólafur", "坂本龍一"), model.artists.map { it.name })
        model.loadArtists()
        settle()
        assertEquals(1, server.requests.size)
    }

    @Test
    fun favouritesOfflineAreTheDownloadedOnes() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"f1","Name":"One"},{"Id":"f2","Name":"Two"}]}""") }
        val model = model(backgroundScope, server)
        model.loadFavourites()
        settle()
        assertEquals("IsFavorite", server.requests.single().url.parameters["Filters"])
        assertEquals(listOf("f1", "f2"), model.favourites.map { it.id })
        model.downloadsChanged(listOf(downloaded("f2", "Two")))
        model.networkChanged(online = false)
        model.loadFavourites()
        settle()
        assertEquals(listOf("f2"), model.favourites.map { it.id })
        assertTrue(model.favouritesLoaded)
    }

    @Test
    fun anArtistsPageOfflineShowsTheirDownloads() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"b1","Name":"Bleach"},{"Id":"n1","Name":"Nevermind"}]}""") }
        val model = ArtistModel(JellyfinArtist("ar", "Nirvana"), server.api(token = "t", userId = "u"), backgroundScope)
        val downloads = listOf(album("Nevermind", artist = "nirvana", id = "n1"), album("Other", artist = "Someone", id = "o"))
        // Never loaded: matched by name from the downloads.
        assertEquals(listOf("n1"), model.shown(offline = true, downloads) { it.id == "n1" }.map { it.id })
        model.loadIfNeeded()
        settle()
        assertEquals("ar", server.requests.single().url.parameters["AlbumArtistIds"])
        assertEquals(listOf("b1", "n1"), model.shown(offline = false, downloads) { false }.map { it.id })
        assertEquals(listOf("n1"), model.shown(offline = true, downloads) { it.id == "n1" }.map { it.id })
    }

    @Test
    fun anArtistsPageShowsTheYearWhereOtherGridsShowTheArtist() {
        assertEquals("1994", ArtistModel.yearText(album("A", year = 1994, date = "1993-12-31T00:00:00.0000000Z")))
        assertEquals("1987", ArtistModel.yearText(album("A", date = "1987-03-09T00:00:00.0000000Z")))
        assertNull(ArtistModel.yearText(album("A")), "nothing when the year is unknown")
    }

    @Test
    fun anArtistsPageIsNewestReleaseFirstThenByTitle() {
        val albums = listOf(
            album("Unknown", id = "u"),
            album("Old", year = 1980, id = "old"),
            album("Bravo", year = 2001, id = "b"),
            album("Late 2001", date = "2001-11-05T00:00:00.0000000Z", id = "full"),
            album("Alpha", year = 2001, id = "a"),
            album("New", year = 2020, id = "new"),
        )
        assertEquals(listOf("new", "full", "a", "b", "old", "u"), ArtistModel.ordered(albums).map { it.id })
    }
}
