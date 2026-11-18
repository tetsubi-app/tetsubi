package gg.dekiru.musicplayer

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared code's version is generated from Kotlin/gradle.properties, the one place Android and Linux read it from. */
class AppVersionTest {
    @Test
    fun generatedVersionMatchesGradleProperties() {
        // Tests run in the :shared folder.
        val file = listOf(File("../gradle.properties"), File("gradle.properties")).first { it.exists() && "tetsubi.versionName" in it.readText() }
        val properties = Properties().apply { file.inputStream().use(::load) }
        assertEquals(properties.getProperty("tetsubi.versionName"), AppInfo.VERSION)
        assertEquals(properties.getProperty("tetsubi.versionCode").toInt(), AppInfo.BUILD)
        assertTrue(AppInfo.BUILD > 0)
    }
}
