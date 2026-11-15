package gg.dekiru.musicplayer.data.settings

import gg.dekiru.musicplayer.data.auth.FileSecretStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the desktop app keeps its data must not carry the app's name: the
 * folders are named after the app ID, so a rename never moves a user's login,
 * settings or downloads.
 */
class DesktopPathsTest {
    @Test
    fun foldersAreNamedAfterTheAppId() {
        assertEquals("gg.dekiru.musicplayer", DESKTOP_DIR_NAME)
        assertEquals(DESKTOP_DIR_NAME, xdg("XDG_CACHE_HOME", ".cache").name)
        assertEquals(DESKTOP_DIR_NAME, xdg("XDG_CONFIG_HOME", ".config").name)
        assertEquals(DESKTOP_DIR_NAME, xdg("XDG_DATA_HOME", ".local/share").name)
    }

    @Test
    fun loginAndDownloadsSitInTheDataFolder() {
        val data = xdg("XDG_DATA_HOME", ".local/share")
        assertEquals(File(data, "login.properties"), FileSecretStore.defaultFile())
        assertEquals(File(data, "Downloads").absolutePath, desktopEnvironment().downloadsDir)
    }
}
