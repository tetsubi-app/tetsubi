package gg.dekiru.musicplayer.data.models

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Track titles, release dates and On This Day. Ported from TrackTitleTests, JellyfinAlbumTests, DateFilterHelperTests. */
class DatesAndTitlesTest {

    // Track titles

    @Test
    fun stripsNumberAndArtist() {
        assertEquals("Follow" to 1, TrackTitle.clean("01 - BrokenMan - Follow", listOf("BrokenMan")))
    }

    @Test
    fun keepsOtherArtistAndPlainTitles() {
        assertEquals("Someone Else - Song", TrackTitle.clean("02 - Someone Else - Song", listOf("BrokenMan")).first)
        assertEquals("1979", TrackTitle.clean("1979", emptyList()).first)
        assertEquals("99 Problems", TrackTitle.clean("99 Problems", emptyList()).first)
        assertEquals("Follow", TrackTitle.clean("Follow", listOf("BrokenMan")).first)
    }

    @Test
    fun neverEmpty() {
        assertTrue(TrackTitle.clean("BrokenMan - ", listOf("BrokenMan")).first.isNotEmpty())
    }

    @Test
    fun trackUsesCleanTitleAndNumberFromName() {
        val track = JellyfinTrack(id = "t", name = "07 - BrokenMan - Follow", albumArtists = listOf(JellyfinPerson(name = "BrokenMan")))
        assertEquals("Follow", track.displayName)
        assertEquals(7, track.trackNumber)
        assertEquals(3, track.copy(indexNumber = 3).trackNumber, "the tag wins")
    }

    // Release dates

    private fun album(premiere: String? = null, year: Int? = null, id: String = "a", name: String = "Album") =
        JellyfinAlbum(id = id, name = name, premiereDate = premiere, productionYear = year)

    @Test
    fun releaseDateReadsFractionalIsoString() {
        assertEquals(LocalDate(1994, 10, 31), album("1994-10-31T00:00:00.0000000Z", 1994).releaseDate)
    }

    @Test
    fun releaseDateFallsBackToJanFirst() {
        assertEquals(LocalDate(2019, 1, 1), album(year = 2019).releaseDate)
        assertEquals(LocalDate(2019, 1, 1), album("0001-01-01T00:00:00.0000000Z", 2019).releaseDate, "Jellyfin's empty date")
        assertNull(album().releaseDate)
    }

    @Test
    fun releaseDatesBefore1970Work() {
        assertEquals(LocalDate(1965, 12, 3), album("1965-12-03T00:00:00Z").releaseDate)
    }

    // On This Day

    @Test
    fun onThisDayMatchesMonthAndDayInEarlierYears() {
        val today = LocalDate(2026, 10, 31)
        val entries = OnThisDay.entries(
            listOf(
                album("1994-10-31T00:00:00Z", id = "unplugged", name = "MTV Unplugged"),
                album("2021-10-31T00:00:00Z", id = "recent", name = "Recent"),
                album("1994-11-01T00:00:00Z", id = "wrong-day", name = "Wrong Day"),
                album("2026-10-31T00:00:00Z", id = "today", name = "Out Today"),
            ),
            today,
        )
        assertEquals(listOf("recent" to 5, "unplugged" to 32), entries.map { it.album.id to it.yearsAgo })
        assertEquals("5 years ago", entries[0].label)
    }

    @Test
    fun onThisDayShowsDiscsOnce() {
        val today = LocalDate(2026, 3, 15)
        val entries = OnThisDay.entries(
            listOf(album("1995-03-15T00:00:00Z", id = "d1", name = "Set (Disc 1)"), album("1995-03-15T00:00:00Z", id = "d2", name = "Set (Disc 2)")),
            today,
        )
        assertEquals(1, entries.size)
        assertEquals("Set", entries[0].album.displayName)
    }

    @Test
    fun anniversaryLabels() {
        assertEquals("1 year ago", OnThisDay.anniversaryLabel(1))
        assertEquals("30 years ago", OnThisDay.anniversaryLabel(30))
        assertEquals("", OnThisDay.anniversaryLabel(0))
    }
}
