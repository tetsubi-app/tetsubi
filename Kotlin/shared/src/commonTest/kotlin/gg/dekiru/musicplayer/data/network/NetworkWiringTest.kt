package gg.dekiru.musicplayer.data.network

import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.auth.Authenticator
import gg.dekiru.musicplayer.data.auth.InMemorySecretStore
import gg.dekiru.musicplayer.data.settings.AppEnvironment
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadManifest
import gg.dekiru.musicplayer.downloads.DownloadPaths
import gg.dekiru.musicplayer.downloads.DownloadSettings
import gg.dekiru.musicplayer.downloads.DownloadStatus
import gg.dekiru.musicplayer.downloads.LocalFiles
import gg.dekiru.musicplayer.downloads.tempDir
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkWiringTest {

    @Test
    fun theMonitorBacksTheEnvironment() {
        val network = NetworkMonitor()
        val env = AppEnvironment(cacheDir = "build/test-cache", downloadsDir = "build/test-downloads", preferences = InMemoryPreferences(), network = network)
        assertTrue(env.isOnline())
        assertFalse(env.isExpensiveNetwork())
        network.update(NetworkState(online = true, metered = true))
        assertTrue(env.isExpensiveNetwork())
        network.update(NetworkState(online = false))
        assertFalse(env.isOnline())
    }

    @Test
    fun aNetworkChangeWakesDownloadsWaitingForWifi() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        try {
            runTest(main) {
                // An unfinished album on disk; mobile data, not allowed for downloads.
                val dir = tempDir()
                val paths = DownloadPaths(dir)
                LocalFiles.createDirectories(paths.albumDir("al"))
                LocalFiles.writeText(paths.manifestPath("al"), DownloadManifest("al", "Album", "Band").encode())
                val prefs = InMemoryPreferences()
                DownloadSettings(prefs).allowCellular = false
                val network = NetworkMonitor(NetworkState(online = true, metered = true))
                val server = FakeServer { respond("down", HttpStatusCode.ServiceUnavailable) }
                val session = AppSession(
                    Authenticator(InMemorySecretStore(), "Pad", server.client),
                    AppEnvironment(cacheDir = "build/test-cache", downloadsDir = dir, preferences = prefs, network = network),
                )
                session.signIn(server.api(token = "t", userId = "u"))
                advanceUntilIdle()
                val manager = session.downloads.value!!
                assertEquals(DownloadStatus.WAITING_FOR_WIFI, manager.state("al")?.status)

                // Wi‑Fi: the manager hears of it and carries on.
                network.update(NetworkState(online = true, metered = false))
                advanceUntilIdle()
                assertNotEquals(DownloadStatus.WAITING_FOR_WIFI, manager.state("al")?.status)
                session.signOut()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
