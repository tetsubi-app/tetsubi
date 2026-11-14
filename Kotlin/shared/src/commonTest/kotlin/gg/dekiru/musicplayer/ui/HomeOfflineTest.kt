package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadSettings
import gg.dekiru.musicplayer.downloads.tempDir
import gg.dekiru.musicplayer.playback.settle
import gg.dekiru.musicplayer.playback.writeDownload
import gg.dekiru.musicplayer.ui.home.HomeModel
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Home offline: nothing asked of the server, the downloaded albums instead, a reload on reconnect. */
class HomeOfflineTest {

    @Test
    fun offlineHomeWaitsAndReloadsOnReconnect() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"a1","Name":"Album","AlbumArtists":[{"Name":"A"}]}]}""") }
        val model = HomeModel(server.api(token = "t", userId = "u"), FavoritesStore(InMemoryPreferences()) { 0 }, backgroundScope) {
            LocalDate(2026, 11, 10)
        }
        model.networkChanged(online = false)
        settle()
        assertTrue(model.isOffline)
        assertTrue(server.requests.isEmpty(), "nothing loads while offline")

        model.networkChanged(online = true)
        settle()
        assertFalse(model.isOffline)
        assertTrue(server.requests.isNotEmpty())
        assertEquals(listOf("a1"), model.randomAlbums.map { it.id })

        // Back to Home (the same state again): what's there is kept.
        val asked = server.requests.size
        model.networkChanged(online = true)
        settle()
        assertEquals(asked, server.requests.size)

        // Dropping out and coming back reloads.
        model.networkChanged(online = false)
        model.networkChanged(online = true)
        settle()
        assertTrue(server.requests.size > asked)
    }

    @Test
    fun theDownloadedRowIsNewestFirstWithDiscsMerged() = runTest {
        val dir = tempDir()
        writeDownload(dir, "old", "Old Album", downloadedAt = 1)
        writeDownload(dir, "d2", "Set (Disc 2)", downloadedAt = 5)
        writeDownload(dir, "d1", "Set (Disc 1)", downloadedAt = 4)
        writeDownload(dir, "new", "New Album", downloadedAt = 9)
        val manager = DownloadManager(FakeServer { json("") }.api(), dir, DownloadSettings(InMemoryPreferences()), backgroundScope)

        val row = HomeModel.downloadedRow(manager.downloaded.value)
        assertEquals(listOf("New Album", "Set", "Old Album"), row.map { it.displayName })
        assertEquals(listOf("d1", "d2"), row[1].discAlbums.map { it.id })
        assertEquals("Band", row[0].artistName)
        assertEquals(1999, row[0].productionYear)
    }
}
