package gg.dekiru.musicplayer.data.api

/** Everything that can go wrong talking to the server. `message` is shown to the user. */
sealed class JellyfinError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The server couldn't be reached (offline, wrong address, timed out). */
    class Network(cause: Throwable) :
        JellyfinError("Couldn't reach the server (${cause.message ?: cause::class.simpleName}).", cause)

    /** The server answered with something we couldn't read. */
    class Decode(cause: Throwable) : JellyfinError("Failed to read the server's answer: ${cause.message}", cause)

    /** The server answered with an error status. */
    class Http(val status: Int, val body: String) :
        JellyfinError("HTTP $status" + if (body.isBlank()) "" else " — ${body.take(200)}")

    /** `AuthenticateByName` said no. */
    class WrongCredentials : JellyfinError("Wrong username or password.")

    /** A signed-in call on a signed-out API. */
    class NotSignedIn : JellyfinError("Not signed in.")

    /** Sign-in worked but the answer had no token or user. */
    class IncompleteLogin : JellyfinError("The server returned an incomplete login.")

    /** `QuickConnect/Initiate` answered 401: switched off in the server's settings. */
    class QuickConnectDisabled :
        JellyfinError("Quick Connect isn't available on this server — use username & password.")

    /** `QuickConnect/Connect` answered 404: the code expired or is unknown. */
    class QuickConnectExpired :
        JellyfinError("The login code expired. Try again, or sign in manually.")
}
