package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.auth.Authenticator
import gg.dekiru.musicplayer.data.auth.InMemorySecretStore
import gg.dekiru.musicplayer.data.settings.AppEnvironment
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadSettings
import gg.dekiru.musicplayer.downloads.tempDir
import gg.dekiru.musicplayer.playback.AudioFormat
import gg.dekiru.musicplayer.playback.FakeEngine
import gg.dekiru.musicplayer.playback.PlaybackSettings
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.playback.RepeatMode
import gg.dekiru.musicplayer.playback.StreamingQuality
import gg.dekiru.musicplayer.ui.settings.SettingsModel
import gg.dekiru.musicplayer.ui.settings.SettingsText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsModelTest {

    private val prefs = InMemoryPreferences()
    private val playback = PlaybackSettings(prefs)
    private val downloadSettings = DownloadSettings(prefs)

    private fun model(player: PlayerController? = null, downloads: DownloadManager? = null) =
        SettingsModel(playback, downloadSettings, { player }, { downloads })

    @Test
    fun formatMenuIsTheOriginalThenEveryFormatAsIos() {
        assertEquals(
            listOf("Original (as served)", "FLAC", "ALAC", "AAC", "Opus", "MP3", "WAV"),
            SettingsText.formatOptions.map { it.second },
        )
        assertNull(SettingsText.formatOptions.first().first)
        assertEquals("Original (as served)", SettingsText.formatLabel(null))
        assertEquals("Opus", SettingsText.formatLabel(AudioFormat.OPUS))
    }

    @Test
    fun qualityMenuOffersTheOriginalFirst() {
        assertEquals(
            listOf("Original (as served)", "High (320 kbps AAC)", "Normal (256 kbps AAC)", "Data saver (128 kbps AAC)"),
            SettingsText.qualityOptions.map { it.displayName },
        )
    }

    @Test
    fun defaultsAsIos() {
        val m = model()
        assertFalse(m.maniacMode)
        assertEquals(StreamingQuality.KBPS256, m.cellularQuality)
        assertFalse(m.normalizeVolume)
        assertTrue(m.downloadOverCellular)
        assertNull(m.defaultFormat)
    }

    @Test
    fun togglesWriteTheIosKeys() {
        val m = model()
        m.changeManiacMode(true)
        m.changeCellularQuality(StreamingQuality.KBPS128)
        m.changeNormalizeVolume(true)
        m.changeDownloadOverCellular(false)
        m.changeDefaultFormat(AudioFormat.FLAC)
        assertEquals("true", prefs.getString("playback.maniacMode"))
        assertEquals("KBPS128", prefs.getString("playback.cellularQuality"))
        assertEquals("true", prefs.getString("playback.normalizeVolume"))
        assertEquals("false", prefs.getString("downloads.allowCellular"))
        assertEquals("flac", prefs.getString("downloads.defaultFormat"))
        m.changeDefaultFormat(null)
        assertEquals("original", prefs.getString("downloads.defaultFormat"))
    }

    @Test
    fun originalIsAQualityLikeAnyOther() {
        playback.cellularQuality = StreamingQuality.ORIGINAL
        val m = model()
        assertEquals(StreamingQuality.ORIGINAL, m.cellularQuality)
        assertNull(StreamingQuality.ORIGINAL.bitrate)
    }

    @Test
    fun maniacModeTurnsShuffleAndRepeatOff() = runTest {
        val engine = FakeEngine()
        val player = PlayerController(FakeServer { json("") }.api(token = "t", userId = "u"), engine, playback, backgroundScope)
        player.changeShuffle(true)
        player.changeRepeatMode(RepeatMode.ALL)
        val m = model(player = player)
        m.changeManiacMode(true)
        assertTrue(player.maniacMode)
        assertFalse(player.shuffle)
        assertEquals(RepeatMode.OFF, player.repeatMode)
        assertFalse(engine.skipEnabled)
        assertTrue(playback.maniacMode)
        m.changeManiacMode(false)
        assertFalse(player.maniacMode)
        assertTrue(engine.skipEnabled)
    }

    @Test
    fun normaliseReappliesTheVolume() = runTest {
        val engine = FakeEngine()
        val player = PlayerController(FakeServer { json("") }.api(token = "t", userId = "u"), engine, playback, backgroundScope)
        engine.currentVolume = 0.3f
        model(player = player).changeNormalizeVolume(false)
        // No track: full volume.
        assertEquals(1f, engine.currentVolume)
    }

    @Test
    fun downloadOverMobileDataGoesThroughTheManager() = runTest {
        val manager = DownloadManager(FakeServer { json("") }.api(), tempDir(), downloadSettings, backgroundScope)
        model(downloads = manager).changeDownloadOverCellular(false)
        assertFalse(manager.settings.allowCellular)
        assertEquals("false", prefs.getString("downloads.allowCellular"))
    }

    @Test
    fun signOutForgetsTheServer() = runTest {
        val store = InMemorySecretStore()
        val server = FakeServer { json("""{"AccessToken":"tok","User":{"Id":"u1","Name":"jake"}}""") }
        val env = AppEnvironment(cacheDir = "build/test-cache", downloadsDir = "build/test-downloads", preferences = InMemoryPreferences())
        val session = AppSession(Authenticator(store, "Pad", server.client), env)
        session.signIn(session.authenticator.signIn("http://jellyfin-box:8096", "jake", "pw"))
        assertEquals("jake", session.authenticator.savedUserName)
        session.signOut()
        assertNull(session.api.value)
        assertNull(session.player.value)
        assertNull(session.authenticator.savedUserName)
    }

    @Test
    fun aboutFooterHasTheCredits() {
        val footer = SettingsText.ABOUT_FOOTER
        assertTrue(footer.startsWith("© 2026 DEKIRU Ltd. All rights reserved.\nMade with AI.\n\n"))
        assertTrue("DEKIRU Sans — based on IBM Plex Sans by IBM Corp., SIL Open Font License 1.1." in footer)
        assertTrue("MusicBrainz and Wikidata (CC0)" in footer)
    }

    @Test
    fun aboutListsTheQaTeam() {
        assertEquals("QA team", SettingsText.QA_TEAM)
        assertEquals(listOf("Matt Evans", "Eloy Smith Rodriguez"), SettingsText.QA_TEAM_NAMES)
        assertEquals("Matt Evans \u2022 Eloy Smith Rodriguez", SettingsText.QA_TEAM_LINE)
    }
}
