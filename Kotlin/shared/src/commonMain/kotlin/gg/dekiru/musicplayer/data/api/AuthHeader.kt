package gg.dekiru.musicplayer.data.api

import gg.dekiru.musicplayer.AppInfo

/** Who is calling: sent in the `Authorization: MediaBrowser …` header on every request. */
data class ClientIdentity(
    val deviceName: String,
    val deviceId: String,
    val client: String = AppInfo.CLIENT_NAME,
    val version: String = AppInfo.VERSION,
)

/**
 * The `Authorization` header value:
 * `MediaBrowser Client="Tetsubi", Device="…", DeviceId="…", Version="…", Token="…"`.
 * `Token` only when signed in (sign-in and Quick Connect calls go without one).
 */
fun mediaBrowserHeader(identity: ClientIdentity, token: String?): String {
    val parts = buildList {
        add("Client=\"${identity.client}\"")
        add("Device=\"${headerSafe(identity.deviceName)}\"")
        add("DeviceId=\"${identity.deviceId}\"")
        add("Version=\"${identity.version}\"")
        if (token != null) add("Token=\"$token\"")
    }
    return "MediaBrowser " + parts.joinToString(", ")
}

/**
 * `value` as plain ASCII, safe inside the quoted header (as on iOS). Device
 * names often aren't: "Jake’s Mac mini" (curly apostrophe) made every request
 * fail with HTTP 400. Curly quotes become straight, accents are dropped
 * (é → e), anything else non-ASCII and any `"` or `\` is removed. Unlike iOS
 * there's no transliteration of other scripts (Cyrillic, CJK), so those
 * names fall back to "Device".
 */
fun headerSafe(value: String): String {
    val straightened = value
        .replace('‘', '\'')
        .replace('’', '\'')
        .replace("“", "")
        .replace("”", "")
    val kept = stripDiacritics(straightened)
        .filter { it.code in 0x20..0x7E && it != '"' && it != '\\' }
    return kept.split(' ').filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Device" }
}

/** "Zoë" → "Zoe": decompose and drop the combining marks. */
internal expect fun stripDiacritics(text: String): String
