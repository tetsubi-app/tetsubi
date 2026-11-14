package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.playback.settle
import gg.dekiru.musicplayer.ui.home.HomeModel
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Home's On This Day: release dates only, then details for the matches, at most 12. */
class OnThisDayLoadTest {

    @Test
    fun datesFirstThenOnlyTheMatchesInFullCappedAt12() = runTest {
        // 20 albums released on 10 Nov (years 2000–2019), one on another day.
        val dated = (0 until 20).joinToString(",") { """{"Id":"m$it","PremiereDate":"${2000 + it}-11-10T00:00:00Z"}""" } +
            """,{"Id":"other","PremiereDate":"2000-11-11T00:00:00Z"}"""
        val server = FakeServer { request ->
            val q = request.url.parameters
            when {
                request.path.endsWith("/Items/Latest") -> json("[]")
                q["Ids"] != null -> json(
                    """{"Items":[${q["Ids"]!!.split(',').joinToString(",") { id ->
                        val year = 2000 + id.removePrefix("m").toInt()
                        """{"Id":"$id","Name":"Album $id","PremiereDate":"$year-11-10T00:00:00Z","AlbumArtists":[{"Name":"A"}]}"""
                    }}]}""",
                )
                q["EnableImages"] == "false" -> json("""{"Items":[$dated]}""")
                else -> json("""{"Items":[]}""")
            }
        }
        val model = HomeModel(server.api(token = "t", userId = "u"), FavoritesStore(InMemoryPreferences()) { 0 }, backgroundScope) {
            LocalDate(2026, 11, 10)
        }
        model.refresh()
        settle()
        assertEquals(12, model.onThisDay.size)
        assertEquals("m19", model.onThisDay.first().album.id, "most recent first")
        assertEquals(7, model.onThisDay.first().yearsAgo)
        val detailIds = server.requests.mapNotNull { it.url.parameters["Ids"] }.single().split(',')
        assertEquals(20, detailIds.size, "details only for the 20 matches, not every album")
        assertTrue("other" !in detailIds)
        assertTrue(server.requests.none { it.url.parameters["limit"] == "10000" }, "no full album list any more")
    }
}
