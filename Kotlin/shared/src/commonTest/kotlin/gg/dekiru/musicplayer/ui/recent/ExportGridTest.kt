package gg.dekiru.musicplayer.ui.recent

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.playback.settle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recently Played ▸ Export grid: the maths (the drawing is tested on the desktop). */
class ExportGridTest {

    private fun albums(count: Int) = (1..count).map { JellyfinAlbum(id = "a$it", name = "Album $it") }

    @Test
    fun gridTakesTheMostRecentAlbumsInOrderOncePerAlbum() {
        val list = albums(30)
        assertEquals((1..9).map { "a$it" }, ExportGrid.albumsFor(list, 3)!!.map { it.id })
        assertEquals((1..25).map { "a$it" }, ExportGrid.albumsFor(list, 5)!!.map { it.id })
        // A repeated album counts once, at its first (most recent) place.
        val repeated = listOf(list[0], list[1], list[0]) + list.drop(2).take(7)
        assertEquals(listOf("a1", "a2") + (3..9).map { "a$it" }, ExportGrid.albumsFor(repeated, 3)!!.map { it.id })
    }

    @Test
    fun tooFewAlbumsGiveNoGrid() {
        assertNull(ExportGrid.albumsFor(albums(8), 3))
        assertNull(ExportGrid.albumsFor(albums(24), 5))
        // Nine entries, but only eight albums.
        assertNull(ExportGrid.albumsFor(albums(8) + albums(1), 3))
        assertFalse(ExportGrid.canExport(8))
        assertTrue(ExportGrid.canExport(9))
    }

    @Test
    fun sizesNeedEnoughAlbums() {
        assertEquals(emptyList(), ExportGrid.availableSizes(8))
        assertEquals(listOf(3), ExportGrid.availableSizes(15))
        assertEquals(listOf(3, 4), ExportGrid.availableSizes(16))
        assertEquals(listOf(3, 4, 5), ExportGrid.availableSizes(25))
        assertEquals(5, ExportGrid.resolvedSize(5, 60))
        assertEquals(4, ExportGrid.resolvedSize(5, 20)) // 5×5 greyed: the biggest that fits
        assertEquals(3, ExportGrid.resolvedSize(3, 20)) // a smaller choice is kept
        assertEquals(3, ExportGrid.resolvedSize(4, 12))
        assertNull(ExportGrid.resolvedSize(3, 8))
    }

    @Test
    fun pageShowsExactlyTheAlbumsTheExportTakes() {
        fun shown(count: Int, chosen: Int) = ExportGrid.shownAlbums(albums(count), chosen).map { it.id }
        // Enough for every size: n² for the chosen n.
        assertEquals((1..9).map { "a$it" }, shown(60, 3))
        assertEquals((1..16).map { "a$it" }, shown(60, 4))
        assertEquals((1..25).map { "a$it" }, shown(60, 5))
        assertEquals((1..25).map { "a$it" }, shown(25, 5))
        // Too few for the choice: the biggest that fits.
        assertEquals(16, shown(24, 5).size)
        assertEquals(9, shown(15, 5).size)
        assertEquals(9, shown(15, 4).size)
        assertEquals(16, shown(20, 4).size)
        assertEquals(9, shown(20, 3).size)
        // Under nine (no export): all of them.
        assertEquals((1..8).map { "a$it" }, shown(8, 5))
        assertEquals(listOf("a1"), shown(1, 3))
        assertEquals(emptyList(), shown(0, 5))
        // A repeat counts once, at its most recent place.
        val repeated = albums(10).let { listOf(it[0], it[1], it[0]) + it.drop(2) }
        assertEquals((1..9).map { "a$it" }, ExportGrid.shownAlbums(repeated, 3).map { it.id })
    }

    @Test
    fun theGridFollowsTheChosenSizeAtOnce() {
        val settings = ExportSettings(InMemoryPreferences())
        val list = albums(30)
        assertEquals(25, ExportGrid.shownAlbums(list, settings.size).size)
        settings.size = 3
        assertEquals(9, ExportGrid.shownAlbums(list, settings.size).size)
        settings.size = 4
        assertEquals(16, ExportGrid.shownAlbums(list, settings.size).size)
        settings.size = 7 // not offered: ignored
        assertEquals(4, settings.size)
    }

    @Test
    fun pageColumnsFollowTheExportSize() {
        val list = albums(30)
        assertEquals(3, ExportGrid.gridColumns(list, 3))
        assertEquals(4, ExportGrid.gridColumns(list, 4))
        assertEquals(5, ExportGrid.gridColumns(list, 5))
        assertEquals(3, ExportGrid.gridColumns(albums(12), 5)) // the size used
        assertNull(ExportGrid.gridColumns(albums(8), 5)) // the Library's usual columns
        assertNull(ExportGrid.gridColumns(albums(8) + albums(8), 3)) // each album once
    }

    @Test
    fun tilesFillTheSquareWithoutGaps() {
        assertEquals(listOf(400, 300, 240), listOf(3, 4, 5).map(ExportGrid::tile))
        for (n in ExportGrid.SIZES) {
            val rects = ExportGrid.tileRects(n)
            assertEquals(n * n, rects.size)
            assertEquals(ExportGrid.TileRect(0, 0, ExportGrid.tile(n)), rects.first())
            assertEquals(ExportGrid.TileRect(ExportGrid.tile(n), 0, ExportGrid.tile(n)), rects[1]) // left to right
            assertEquals(ExportGrid.TileRect(0, ExportGrid.tile(n), ExportGrid.tile(n)), rects[n]) // then the next row
            val last = rects.last()
            assertEquals(ExportGrid.IMAGE_SIZE, last.x + last.size)
            assertEquals(ExportGrid.IMAGE_SIZE, last.y + last.size)
        }
    }

    @Test
    fun labelsScaleWithTheTile() {
        val big = ExportGrid.labelLayout(400)
        assertEquals(8f, big.inset, 0.001f) // 2%
        assertEquals(22f, big.titleSize, 0.001f)
        assertEquals(18f, big.artistSize, 0.001f)
        assertEquals(384f, big.maxWidth, 0.001f)
        assertEquals(2, big.shadowOffset)
        val middle = ExportGrid.labelLayout(300)
        assertEquals(6f, middle.inset, 0.001f)
        assertEquals(288f, middle.maxWidth, 0.001f)
        assertEquals(16.5f, middle.titleSize, 0.001f)
        assertEquals(13.5f, middle.artistSize, 0.001f)
        assertEquals(2, middle.shadowOffset)
        val small = ExportGrid.labelLayout(240)
        assertEquals(4.8f, small.inset, 0.001f)
        assertEquals(13, kotlin.math.round(small.titleSize).toInt())
        assertEquals(11, kotlin.math.round(small.artistSize).toInt())
        assertEquals(230.4f, small.maxWidth, 0.001f)
        assertEquals(1, small.shadowOffset)
        // The title's top at the inset, the artist right under it.
        assertEquals(8f + 20f to 8f + 20f + 5f + 1f + 15f, ExportGrid.baselines(big, 20f, 5f, 1f, 15f))
    }

    @Test
    fun longLinesEndWithAnEllipsis() {
        val measure: (String) -> Float = { it.length * 10f }
        assertEquals("Short", ExportGrid.ellipsize("Short", 100f, measure))
        val cut = ExportGrid.ellipsize("A very long album title", 100f, measure)
        assertTrue(cut.endsWith("…"))
        assertTrue(measure(cut) <= 100f)
        assertEquals("A very…", ExportGrid.ellipsize("A very long", 70f, measure)) // no space before the ellipsis
        assertEquals("", ExportGrid.ellipsize("Anything", 5f, measure))
    }

    @Test
    fun coversAreCroppedToTheirMiddleSquare() {
        assertEquals(listOf(0, 0, 600, 600), ExportGrid.aspectFillSource(600, 600))
        assertEquals(listOf(100, 0, 500, 400), ExportGrid.aspectFillSource(600, 400))
        assertEquals(listOf(0, 50, 300, 350), ExportGrid.aspectFillSource(300, 400))
    }

    @Test
    fun fileNameCarriesTheDateAndTime() {
        assertEquals(
            "Tetsubi Recently Played 2026-11-22 14.05.32.png",
            ExportGrid.fileName(LocalDateTime(2026, 11, 22, 14, 5, 32)),
        )
        // 24-hour, zero-padded, no colons.
        val early = ExportGrid.fileName(LocalDateTime(2026, 1, 2, 3, 4, 5))
        assertEquals("Tetsubi Recently Played 2026-01-02 03.04.05.png", early)
        assertFalse(':' in early)
        assertEquals("Tetsubi Recently Played 2026-11-22 23.59.59.png", ExportGrid.fileName(LocalDateTime(2026, 11, 22, 23, 59, 59)))
    }

    @Test
    fun rememberedSettingsDefaultToFiveAndNoLabels() {
        val prefs = InMemoryPreferences()
        val settings = ExportSettings(prefs)
        assertEquals(5, settings.size)
        assertFalse(settings.showLabels)
        settings.size = 3
        settings.showLabels = true
        assertEquals("3", prefs.getString("recentlyPlayed.exportSize"))
        assertEquals("true", prefs.getString("recentlyPlayed.exportLabels"))
        assertEquals(3, ExportSettings(prefs).size)
        assertTrue(ExportSettings(prefs).showLabels)
        prefs.putString("recentlyPlayed.exportSize", "7") // not offered: the default
        assertEquals(5, ExportSettings(prefs).size)
    }

    @Test
    fun pageAsksForMoreThanHomeAndMergesDiscs() = runTest {
        val tracks = """{"Items":[
            {"Id":"t1","Name":"One","Type":"Audio","AlbumId":"d1","Album":"Set (Disc 1)","AlbumArtists":[{"Name":"A"}],"UserData":{"PlayCount":1}},
            {"Id":"t2","Name":"Two","Type":"Audio","AlbumId":"x","Album":"Other","AlbumArtists":[{"Name":"B"}],"UserData":{"PlayCount":2}},
            {"Id":"t3","Name":"Three","Type":"Audio","AlbumId":"d2","Album":"Set (Disc 2)","AlbumArtists":[{"Name":"A"}],"UserData":{"PlayCount":1}}]}"""
        val server = FakeServer { json(tracks) }
        val model = RecentlyPlayedModel(server.api(token = "t", userId = "u"), backgroundScope)
        model.loadIfNeeded()
        settle()
        assertEquals(listOf("d1", "x"), model.albums.map { it.id })
        assertEquals("1200", server.requests.single().url.parameters["limit"]) // 60 albums' worth of tracks
        assertEquals("DatePlayed", server.requests.single().url.parameters["SortBy"])
        assertTrue(model.loaded)
        model.refresh()
        settle()
        assertEquals(2, server.requests.size)
    }
}
