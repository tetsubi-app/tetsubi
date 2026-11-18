package gg.dekiru.musicplayer.data.update

import gg.dekiru.musicplayer.AppInfo
import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.ui.settings.SettingsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {

    private fun release(tag: String = "v1.0.6", page: String? = "https://github.com/tetsubi-app/tetsubi/releases/tag/v1.0.6", body: String? = "Fixes.") =
        buildString {
            append("{\"tag_name\":\"").append(tag).append('"')
            append(",\"html_url\":").append(page?.let { "\"$it\"" } ?: "null")
            append(",\"body\":").append(body?.let { "\"$it\"" } ?: "null")
            append('}')
        }

    // Versions

    @Test
    fun versionsAreReadAndCompared() {
        assertEquals(listOf(1, 0, 6), UpdateCheck.versionParts("v1.0.6"))
        assertEquals(listOf(1, 0, 6), UpdateCheck.versionParts("V1.0.6"))
        assertTrue(UpdateCheck.isNewer("v1.0.6", "1.0.5"))
        assertFalse(UpdateCheck.isNewer("v1.0.5", "1.0.5"))
        assertFalse(UpdateCheck.isNewer("v1.0.4", "1.0.5"))
        assertTrue(UpdateCheck.isNewer("1.0.10", "1.0.9"))
        assertFalse(UpdateCheck.isNewer("1.0", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("1.0.0", "1.0"))
        assertTrue(UpdateCheck.isNewer("1.1", "1.0.99"))
    }

    @Test
    fun aSuffixIsIgnoredAndNoNumberIsNoVersion() {
        assertEquals(listOf(1, 1), UpdateCheck.versionParts("1.1-beta"))
        assertEquals("1.1", UpdateCheck.cleanVersion("v1.1-beta"))
        assertEquals(emptyList(), UpdateCheck.versionParts("latest"))
        assertEquals(emptyList(), UpdateCheck.versionParts(""))
        assertEquals(emptyList(), UpdateCheck.versionParts("v"))
    }

    @Test
    fun theRunningVersionIsNameDotBuild() {
        assertEquals("${AppInfo.VERSION}.${AppInfo.BUILD}", UpdateCheck.CURRENT_VERSION)
        assertEquals("Tetsubi/${UpdateCheck.CURRENT_VERSION}", UpdateCheck.USER_AGENT)
        assertEquals("1.0 (5)", UpdateCheck.displayVersion("1.0", 5))
        assertEquals("${AppInfo.VERSION} (${AppInfo.BUILD})", SettingsText.version)
    }

    // When to look

    @Test
    fun launchCheckIsDueOncePerDayWhileSwitchedOn() {
        val day = UpdateCheck.INTERVAL_MILLIS
        val now = 10 * day
        assertTrue(UpdateCheck.isLaunchCheckDue(true, true, lastCheck = 0, now = now), "never checked")
        assertFalse(UpdateCheck.isLaunchCheckDue(true, false, lastCheck = 0, now = now), "switch off")
        assertFalse(UpdateCheck.isLaunchCheckDue(false, true, lastCheck = 0, now = now), "off in this build")
        assertFalse(UpdateCheck.isLaunchCheckDue(true, true, lastCheck = now - day + 1, now = now), "within 24 h")
        assertTrue(UpdateCheck.isLaunchCheckDue(true, true, lastCheck = now - day, now = now), "after 24 h")
        assertTrue(UpdateCheck.isLaunchCheckDue(true, true, lastCheck = now + 1000, now = now), "clock went back")
    }

    // GitHub's answer

    @Test
    fun aNewerTagIsOffered() {
        val result = UpdateCheck.interpret(200, release(), "1.0.5")
        assertTrue(result.answered)
        assertEquals(
            UpdateOutcome.Newer(UpdateInfo("1.0.6", "https://github.com/tetsubi-app/tetsubi/releases/tag/v1.0.6", "Fixes.")),
            result.outcome,
        )
    }

    @Test
    fun theSameOrAnOlderTagIsUpToDate() {
        assertEquals(UpdateOutcome.UpToDate, UpdateCheck.interpret(200, release("v1.0.5"), "1.0.5").outcome)
        assertEquals(UpdateOutcome.UpToDate, UpdateCheck.interpret(200, release("v1.0.4"), "1.0.5").outcome)
    }

    @Test
    fun aMissingOrNumberlessTagOrBadJsonFailsButCountsAsAnswered() {
        for (body in listOf("{\"html_url\":\"https://github.com\"}", "{\"tag_name\":null}", release("latest"), "not json", "[1,2]", "")) {
            val result = UpdateCheck.interpret(200, body, "1.0.5")
            assertTrue(result.outcome is UpdateOutcome.Failed, body)
            assertTrue(result.answered, body)
        }
    }

    @Test
    fun onlyAWebAddressIsOpened() {
        for (page in listOf("javascript:alert(1)", "file:///etc/passwd", "", null)) {
            val outcome = UpdateCheck.interpret(200, release(page = page), "1.0.5").outcome as UpdateOutcome.Newer
            assertEquals(UpdateCheck.RELEASES_PAGE, outcome.info.pageUrl, "$page")
        }
        val plain = UpdateCheck.interpret(200, release(page = "http://example.com/r"), "1.0.5").outcome as UpdateOutcome.Newer
        assertEquals("http://example.com/r", plain.info.pageUrl)
    }

    @Test
    fun noBodyIsNoNotes() {
        val outcome = UpdateCheck.interpret(200, release(body = null), "1.0.5").outcome as UpdateOutcome.Newer
        assertEquals("", outcome.info.notes)
    }

    @Test
    fun theRequestCarriesOnlyTheAppsNameAndVersion() = runTest {
        val server = FakeServer { json(release()) }
        val result = UpdateCheck.fetch(server.client, "https://api.example/releases/latest", "1.0.5")
        assertTrue(result.outcome is UpdateOutcome.Newer)
        val request = server.requests.single()
        assertEquals("https://api.example/releases/latest", request.url.toString())
        assertEquals("application/vnd.github+json", request.headers[HttpHeaders.Accept])
        assertEquals("Tetsubi/1.0.5", request.headers[HttpHeaders.UserAgent])
        assertNull(request.headers[HttpHeaders.Authorization])
        assertNull(request.headers[HttpHeaders.Cookie])
    }

    @Test
    fun anHttpErrorFailsButCountsAsAnswered() = runTest {
        val server = FakeServer { json("{\"message\":\"Not Found\"}", HttpStatusCode.NotFound) }
        val result = UpdateCheck.fetch(server.client, "https://api.example/x", "1.0.5")
        assertEquals(UpdateOutcome.Failed("HTTP 404"), result.outcome)
        assertTrue(result.answered)
    }

    @Test
    fun noAnswerAtAllIsNotAnswered() = runTest {
        val server = FakeServer { throw RuntimeException("offline") }
        val result = UpdateCheck.fetch(server.client, "https://api.example/x", "1.0.5")
        assertTrue(result.outcome is UpdateOutcome.Failed)
        assertFalse(result.answered)
    }

    // The checker: banner, skip, Check now, the switch

    private class Clock(var millis: Long = 100 * UpdateCheck.INTERVAL_MILLIS)

    @Test
    fun aLaunchCheckShowsTheBannerAndRemembersTheTime() = runTest {
        val prefs = InMemoryPreferences()
        val clock = Clock()
        val server = FakeServer { json(release()) }
        val checker = UpdateChecker(prefs, this, { clock.millis }, { server.client }, current = "1.0.5", log = {})
        checker.checkAtLaunch()!!.join()
        assertEquals("1.0.6", checker.banner?.version)
        assertNull(checker.status, "a launch check says nothing in Settings")
        assertEquals(clock.millis, UpdateSettings(prefs).lastUpdateCheck)
        // Once per run.
        assertNull(checker.checkAtLaunch())
        assertEquals(1, server.requests.size)
    }

    @Test
    fun noLaunchCheckWithin24HoursOrWithTheSwitchOff() = runTest {
        val prefs = InMemoryPreferences()
        val clock = Clock()
        val server = FakeServer { json(release()) }
        UpdateSettings(prefs).lastUpdateCheck = clock.millis - 1000
        assertNull(UpdateChecker(prefs, this, { clock.millis }, { server.client }, current = "1.0.5", log = {}).checkAtLaunch())

        UpdateSettings(prefs).lastUpdateCheck = 0
        UpdateSettings(prefs).checkForUpdates = false
        assertNull(UpdateChecker(prefs, this, { clock.millis }, { server.client }, current = "1.0.5", log = {}).checkAtLaunch())
        assertEquals(0, server.requests.size)
    }

    @Test
    fun aCheckWithNoAnswerIsTriedAgainNextTime() = runTest {
        val prefs = InMemoryPreferences()
        val server = FakeServer { throw RuntimeException("offline") }
        val checker = UpdateChecker(prefs, this, { 5_000L }, { server.client }, current = "1.0.5", log = {})
        checker.checkAtLaunch()!!.join()
        assertNull(checker.banner)
        assertEquals(0L, UpdateSettings(prefs).lastUpdateCheck)
    }

    @Test
    fun skippingHidesTheBannerForThatVersionOnly() = runTest {
        val prefs = InMemoryPreferences()
        var tag = "v1.0.6"
        val server = FakeServer { json(release(tag)) }
        val clock = Clock()
        val checker = UpdateChecker(prefs, this, { clock.millis }, { server.client }, current = "1.0.5", log = {})
        checker.checkAtLaunch()!!.join()
        checker.skip()
        assertNull(checker.banner)
        assertEquals("1.0.6", UpdateSettings(prefs).skippedUpdateVersion)

        // The next run, a day later: still skipped.
        clock.millis += UpdateCheck.INTERVAL_MILLIS
        val again = UpdateChecker(prefs, this, { clock.millis }, { server.client }, current = "1.0.5", log = {})
        again.checkAtLaunch()!!.join()
        assertNull(again.banner)

        // A newer release than the skipped one shows.
        tag = "v1.0.7"
        clock.millis += UpdateCheck.INTERVAL_MILLIS
        val later = UpdateChecker(prefs, this, { clock.millis }, { server.client }, current = "1.0.5", log = {})
        later.checkAtLaunch()!!.join()
        assertEquals("1.0.7", later.banner?.version)
    }

    @Test
    fun checkNowIgnoresTheSwitchAndTheDayAndClearsASkip() = runTest {
        val prefs = InMemoryPreferences()
        val settings = UpdateSettings(prefs)
        settings.checkForUpdates = false
        settings.lastUpdateCheck = 99
        settings.skippedUpdateVersion = "1.0.6"
        val server = FakeServer { json(release()) }
        val checker = UpdateChecker(prefs, this, { 100L }, { server.client }, current = "1.0.5", log = {})
        checker.checkNow()!!.join()
        assertEquals("Tetsubi 1.0.6 is available", checker.status)
        assertEquals("1.0.6", checker.banner?.version)
        assertEquals("", settings.skippedUpdateVersion)
        assertFalse(checker.checking)
    }

    @Test
    fun checkNowSaysWhatItFound() = runTest {
        var answer: () -> Pair<String, HttpStatusCode> = { release("v1.0.5") to HttpStatusCode.OK }
        val server = FakeServer { answer().let { (body, status) -> json(body, status) } }
        val checker = UpdateChecker(InMemoryPreferences(), this, { 1L }, { server.client }, current = "1.0.5", log = {})
        checker.checkNow()!!.join()
        assertEquals("You have the latest version (1.0.5)", checker.status)
        assertNull(checker.banner)

        answer = { "" to HttpStatusCode.InternalServerError }
        checker.checkNow()!!.join()
        assertEquals("Couldn't check for updates", checker.status)
    }

    @Test
    fun offInThisBuildSendsNothingAndShowsNothing() = runTest {
        val server = FakeServer { json(release()) }
        val checker = UpdateChecker(InMemoryPreferences(), this, { 1L }, { server.client }, enabled = false, current = "1.0.5", log = {})
        assertNull(checker.checkAtLaunch())
        assertNull(checker.checkNow())
        assertNull(checker.banner)
        assertEquals(0, server.requests.size)
    }

    @Test
    fun theSwitchIsSaved() = runTest {
        val prefs = InMemoryPreferences()
        val checker = UpdateChecker(prefs, this, { 1L }, current = "1.0.5", log = {})
        assertTrue(checker.checkForUpdates)
        checker.changeCheckForUpdates(false)
        assertFalse(UpdateSettings(prefs).checkForUpdates)
        assertFalse(UpdateChecker(prefs, this, { 1L }, current = "1.0.5", log = {}).checkForUpdates)
    }
}
