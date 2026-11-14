package gg.dekiru.musicplayer.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `POST /Users/AuthenticateByName` (Jellyfin calls the password "Pw"). */
@Serializable
internal data class AuthenticateByNameBody(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

/** Body of `POST /Users/AuthenticateWithQuickConnect`. */
@Serializable
internal data class QuickConnectAuthBody(@SerialName("Secret") val secret: String)

@Serializable
data class JellyfinUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

/** Answer to both sign-in calls (`AuthenticateByName`, `AuthenticateWithQuickConnect`). */
@Serializable
data class AuthenticationResponse(
    @SerialName("User") val user: JellyfinUser? = null,
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("SessionInfo") val sessionInfo: SessionInfo? = null,
) {
    @Serializable
    data class SessionInfo(@SerialName("UserId") val userId: String? = null)

    /** The signed-in user's ID: from `User`, else from `SessionInfo`. */
    val resolvedUserId: String? get() = user?.id ?: sessionInfo?.userId
}

/** Answer to `POST /QuickConnect/Initiate`: the 6-digit code to show, and the secret to poll with. */
@Serializable
data class QuickConnectStart(
    @SerialName("Code") val code: String? = null,
    @SerialName("Secret") val secret: String? = null,
    @SerialName("Authenticated") val authenticated: Boolean? = null,
)

/** Answer to `GET /QuickConnect/Connect`: `Authenticated` turns true once the code is approved. */
@Serializable
data class QuickConnectPoll(@SerialName("Authenticated") val authenticated: Boolean? = null) {
    val isApproved: Boolean get() = authenticated == true
}

/** `GET /System/Info/Public` (no sign-in needed). Used to check an address is a Jellyfin server. */
@Serializable
data class PublicSystemInfo(
    @SerialName("Id") val id: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("ProductName") val productName: String? = null,
    @SerialName("ServerName") val serverName: String? = null,
)
