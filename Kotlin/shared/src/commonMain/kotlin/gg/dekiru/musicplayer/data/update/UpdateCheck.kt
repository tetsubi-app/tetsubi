package gg.dekiru.musicplayer.data.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.AppInfo
import gg.dekiru.musicplayer.data.api.TetsubiHttp
import gg.dekiru.musicplayer.data.api.TetsubiJson
import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.logLine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A newer release, as GitHub describes it. [version] is the cleaned tag ("1.0.6"). */
data class UpdateInfo(val version: String, val pageUrl: String, val notes: String)

/** What one look at GitHub found. */
sealed class UpdateOutcome {
    data class Newer(val info: UpdateInfo) : UpdateOutcome()
    data object UpToDate : UpdateOutcome()
    /** [reason] goes in the log only. */
    data class Failed(val reason: String) : UpdateOutcome()
}

/**
 * [answered] is true when something came back at all, even an error page:
 * that is what counts as an attempt for the 24 hours.
 */
data class UpdateResult(val outcome: UpdateOutcome, val answered: Boolean)

/**
 * Looking for a newer release on GitHub (Android and Linux only: iOS and the
 * Mac are updated through the App Store and have no check). One request, a
 * banner on Home if there is one, and nothing downloaded or installed: the
 * person follows the link and decides. Only the app's name and version go
 * with the request, in the User-Agent, which GitHub insists on.
 */
object UpdateCheck {
    /**
     * The whole feature, for this build. Off for a build that is updated by a
     * store (a Google Play build would set this to false): no request, no
     * banner and no Settings rows.
     */
    const val ENABLED_IN_THIS_BUILD = true

    const val ADDRESS = "https://api.github.com/repos/tetsubi-app/tetsubi/releases/latest"
    const val RELEASES_PAGE = "https://github.com/tetsubi-app/tetsubi/releases"
    const val INTERVAL_MILLIS = 24L * 3600 * 1000

    /** The running version as release tags spell it, without the "v": "<versionName>.<versionCode>", e.g. "1.0.5". */
    const val CURRENT_VERSION = "${AppInfo.VERSION}.${AppInfo.BUILD}"

    const val USER_AGENT = "Tetsubi/$CURRENT_VERSION"

    /** The version as people see it in Settings: "1.0 (5)". */
    fun displayVersion(name: String = AppInfo.VERSION, build: Int = AppInfo.BUILD) = "$name ($build)"

    /**
     * "v1.0.6" gives [1, 0, 6]. Reading stops at the first character that is
     * not a digit or a dot, so "1.1-beta" is 1.1. Empty when there is no number at all.
     */
    fun versionParts(text: String): List<Int> {
        val numeric = text.trim().removePrefix("v").removePrefix("V").takeWhile { it in '0'..'9' || it == '.' }
        if (numeric.none { it in '0'..'9' }) return emptyList()
        return numeric.split('.').map { it.toIntOrNull() ?: 0 }
    }

    /** The version as it is shown and remembered: no "v", no suffix. */
    fun cleanVersion(text: String) = versionParts(text).joinToString(".")

    /** Part by part, a missing one counting as 0: 1.0.10 is newer than 1.0.9, 1.0 is not newer than 1.0.0. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = versionParts(latest)
        val b = versionParts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Whether to look when the app opens: the build has the feature, the
     * switch is on, and no look got an answer in the last 24 hours
     * ([lastCheck] 0 = never; a clock that went backwards counts as due).
     */
    fun isLaunchCheckDue(enabledInBuild: Boolean, switchOn: Boolean, lastCheck: Long, now: Long): Boolean {
        if (!enabledInBuild || !switchOn) return false
        if (lastCheck <= 0) return true
        return now - lastCheck !in 0 until INTERVAL_MILLIS
    }

    /** GitHub's answer ([status], [body]) compared with [current]. Any answer counts as answered. */
    fun interpret(status: Int, body: String, current: String): UpdateResult {
        if (status != 200) return UpdateResult(UpdateOutcome.Failed("HTTP $status"), answered = true)
        val json = runCatching { TetsubiJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return UpdateResult(UpdateOutcome.Failed("unreadable answer"), answered = true)
        val tag = json.string("tag_name")
        if (tag.isNullOrEmpty()) return UpdateResult(UpdateOutcome.Failed("unreadable answer"), answered = true)
        if (versionParts(tag).isEmpty()) return UpdateResult(UpdateOutcome.Failed("no version in tag"), answered = true)
        if (!isNewer(tag, current)) return UpdateResult(UpdateOutcome.UpToDate, answered = true)
        // Only a web address is ever opened; anything else falls back to the releases page.
        val page = json.string("html_url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: RELEASES_PAGE
        return UpdateResult(UpdateOutcome.Newer(UpdateInfo(cleanVersion(tag), page, json.string("body").orEmpty())), answered = true)
    }

    /** One request to [address]. A request that gets no answer at all (offline, timeout) is not answered. */
    suspend fun fetch(http: HttpClient, address: String = ADDRESS, current: String = CURRENT_VERSION): UpdateResult {
        val (status, body) = try {
            val response = http.get(address) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "Tetsubi/$current")
            }
            val status = response.status.value
            status to (if (status == 200) response.bodyAsText() else "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The kind of failure goes in the log only; the person is told nothing either way.
            return UpdateResult(UpdateOutcome.Failed("no answer: ${e::class.simpleName}"), answered = false)
        }
        return interpret(status, body, current)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** The update check's saved settings (keys as nagori3k's check). */
class UpdateSettings(private val preferences: Preferences) {
    /** "Check for updates when Tetsubi opens". Default on. */
    var checkForUpdates: Boolean
        get() = preferences.getString("checkForUpdates")?.toBooleanStrictOrNull() ?: true
        set(value) = preferences.putString("checkForUpdates", value.toString())

    /** The release the person chose not to be told about again ("1.0.6"); empty for none. */
    var skippedUpdateVersion: String
        get() = preferences.getString("skippedUpdateVersion").orEmpty()
        set(value) = preferences.putString("skippedUpdateVersion", value.ifEmpty { null })

    /** When a check last got an answer, in milliseconds; 0 for never. */
    var lastUpdateCheck: Long
        get() = preferences.getString("lastUpdateCheck")?.toLongOrNull() ?: 0L
        set(value) = preferences.putString("lastUpdateCheck", value.toString())
}

/** What the screens show and do: the banner on Home and Settings ▸ Updates. One per app (in `AppSession`). */
class UpdateChecker(
    preferences: Preferences,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val http: () -> HttpClient = { TetsubiHttp.client },
    /** [UpdateCheck.ENABLED_IN_THIS_BUILD]; false hides the banner and the Settings rows and sends nothing. */
    val enabled: Boolean = UpdateCheck.ENABLED_IN_THIS_BUILD,
    private val address: String = UpdateCheck.ADDRESS,
    private val current: String = UpdateCheck.CURRENT_VERSION,
    private val log: (String) -> Unit = { logLine("Tetsubi", "update: $it") },
) {
    private val settings = UpdateSettings(preferences)

    /** The newest release found this run, if it is newer than this one. */
    var available by mutableStateOf<UpdateInfo?>(null)
        private set

    /** What "Check now" found, in words, for Settings. */
    var status by mutableStateOf<String?>(null)
        private set

    var checking by mutableStateOf(false)
        private set

    var checkForUpdates by mutableStateOf(settings.checkForUpdates)
        private set

    private var skipped by mutableStateOf(settings.skippedUpdateVersion)
    private var launchCheckDone = false

    /** The banner shows this: a newer version that has not been skipped. */
    val banner: UpdateInfo? get() = if (enabled) available?.takeIf { it.version != skipped } else null

    fun changeCheckForUpdates(on: Boolean) {
        checkForUpdates = on
        settings.checkForUpdates = on
    }

    /** "Skip this version": the banner goes and stays away for this version. */
    fun skip() {
        available?.let { rememberSkipped(it.version) }
    }

    /** When the app opens (once per run): not if the build or the person has switched it off, and not more than once a day. */
    fun checkAtLaunch(): Job? {
        if (launchCheckDone) return null
        launchCheckDone = true
        if (!UpdateCheck.isLaunchCheckDue(enabled, settings.checkForUpdates, settings.lastUpdateCheck, now())) return null
        return start(manual = false)
    }

    /** "Check now": whatever the switch and the time since the last look; a newer release brings back a skipped banner. */
    fun checkNow(): Job? {
        if (!enabled || checking) return null
        status = UpdateText.CHECKING
        return start(manual = true)
    }

    private fun start(manual: Boolean): Job {
        checking = true
        return scope.launch {
            try {
                val result = UpdateCheck.fetch(http(), address, current)
                if (result.answered) settings.lastUpdateCheck = now()
                when (val outcome = result.outcome) {
                    is UpdateOutcome.Newer -> {
                        val info = outcome.info
                        available = info
                        if (manual) rememberSkipped("")
                        if (info.version == skipped) log("skipped ${info.version}") else log("available ${info.version} (this is $current)")
                        if (manual) status = UpdateText.available(info.version)
                    }
                    UpdateOutcome.UpToDate -> {
                        available = null
                        log("up to date ($current)")
                        if (manual) status = UpdateText.upToDate(current)
                    }
                    is UpdateOutcome.Failed -> {
                        log("check failed (${outcome.reason})")
                        if (manual) status = UpdateText.FAILED
                    }
                }
            } finally {
                checking = false
            }
        }
    }

    private fun rememberSkipped(version: String) {
        skipped = version
        settings.skippedUpdateVersion = version
    }
}

/** The update check's wording (banner, dialog, Settings status). */
object UpdateText {
    const val CHECKING = "Checking…"
    const val FAILED = "Couldn't check for updates"
    const val DOWNLOAD = "Download"
    const val WHATS_NEW = "What's new"
    const val SKIP = "Skip this version"
    const val NO_NOTES = "No release notes."
    const val CLOSE = "Close"

    fun available(version: String) = "Tetsubi $version is available"
    fun upToDate(version: String) = "You have the latest version ($version)"
    fun notesTitle(version: String) = "Tetsubi $version"
}
