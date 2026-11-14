// MARK: - Authenticator — orchestrates the full Jellyfin login flow

import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Wraps the Jellyfin authentication flow:
/// - First launch: AuthenticateByName → JWT + UserID
/// - Re-launch: read JWT from Keychain → AuthenticateWithToken → verify
/// - On failure: re-prompt user to enter credentials
public final class Authenticator {

    // MARK: Properties

    /// Stable per-device UUID, persisted in Keychain once.
    private var deviceId: UUID {
        get {
            if let cached = try? keychain.load(UUID.self, for: .deviceId) {
                return cached
            }
            let id = UUID()
            try? keychain.store(id, for: .deviceId)
            return id
        }
    }

    /// A client ID that identifies this app to the Jellyfin server.
    public let clientId = "Tetsubi"

    public let keychain: KeychainManager

    // MARK: Init

    public init(keychain: KeychainManager = KeychainManager()) {
        self.keychain = keychain
    }

    // MARK: - Login

    /// Authenticates a user with username + password.
    /// On success: stores JWT and server config in Keychain.
    public func authenticate(
        serverURL: URL,
        username: String,
        password: String,
        deviceName: String = "Tetsubi",
        completion: @escaping (Result<AuthenticationResponse, JellyfinError>) -> Void
    ) {
        let api = JellyfinAPI(
            baseURL:    serverURL,
            clientId:   clientId,
            deviceName: deviceNameString(),
            deviceId:   deviceId.uuidString,
            appVersion: "1.0"
        )
        api.authenticate(username: username, password: password) { [weak self] result in
            switch result {
            case .success(let response):
                if let self {
                    try? self.keychain.store(serverURL.absoluteString, for: .serverURL)
                    // The server's spelling of the name, as Android saves it.
                    try? self.keychain.store(response.user?.name ?? username, for: .username)
                    try? self.keychain.store(response.resolvedUserId ?? "", for: .userId)
                    if let token = response.accessToken {
                        try? self.keychain.store(token, for: .jwtToken)
                    }
                }
                completion(.success(response))
            case .failure(let error):
                completion(.failure(error))
            }
        }
    }

    /// Tries to re-authenticate with the stored JWT.
    /// Returns `true` if a reauth request was sent (the completion
    /// delivers the result). Returns `false` if there are no stored
    /// credentials, in which case a full login is required.
    public func reauthenticate(
        completion: @escaping (Result<AuthenticationResponse, JellyfinError>) -> Void
    ) -> Bool {
        guard
            let urlStr = try? keychain.loadServerURLString(),
            let token  = try? keychain.loadJWTToken(),
            let userId = try? keychain.load(String.self, for: .userId),
            let url    = URL(string: urlStr)
        else {
            return false  // no stored credentials
        }

        let api = JellyfinAPI(
            baseURL:     url,
            accessToken: token,
            clientId:    clientId,
            deviceName:  deviceNameString(),
            deviceId:    deviceId.uuidString,
            appVersion:  "1.0"
        )
        api.reauthenticate(userId: userId, accessToken: token) { [weak self] result in
            switch result {
            case .success(let response):
                if let self {
                    // Rotate the token in case the server issued a new one
                    if let newToken = response.accessToken, newToken != token {
                        try? self.keychain.store(newToken, for: .jwtToken)
                    }
                }
                completion(.success(response))
            case .failure(let error):
                completion(.failure(error))
            }
        }
        return true
    }

    // MARK: - Quick Connect

    /// Begins a Quick Connect login against `serverURL`. Returns the
    /// `QuickConnectStart` (6-digit code + secret) so the caller can poll.
    /// Builds a fresh `JellyfinAPI` with no access token — the code is the
    /// credential at this stage — using the same device identity as the
    /// username/password flow.
    public func startQuickConnect(
        serverURL: URL,
        completion: @escaping (Result<QuickConnectStart, JellyfinError>) -> Void
    ) {
        let api = JellyfinAPI(
            baseURL:    serverURL,
            clientId:   clientId,
            deviceName: deviceNameString(),
            deviceId:   deviceId.uuidString,
            appVersion: "1.0"
        )
        api.startQuickConnect(completion: completion)
    }

    private func quickConnectAPI(serverURL: URL) -> JellyfinAPI {
        JellyfinAPI(
            baseURL:    serverURL,
            clientId:   clientId,
            deviceName: deviceNameString(),
            deviceId:   deviceId.uuidString,
            appVersion: "1.0"
        )
    }

    /// Polls whether the Quick Connect login for `secret` has been approved.
    public func pollQuickConnect(
        serverURL: URL,
        secret: String,
        completion: @escaping (Result<QuickConnectPoll, JellyfinError>) -> Void
    ) {
        quickConnectAPI(serverURL: serverURL).pollQuickConnect(secret: secret, completion: completion)
    }

    /// Exchanges an approved Quick Connect `secret` for an access token.
    public func completeQuickConnect(
        serverURL: URL,
        secret: String,
        completion: @escaping (Result<AuthenticationResponse, JellyfinError>) -> Void
    ) {
        quickConnectAPI(serverURL: serverURL).authenticateWithQuickConnect(secret: secret, completion: completion)
    }

    /// Stores a successful Quick Connect login — access token + userId + server
    /// URL — in the Keychain (the same three fields `makeAPI` /
    /// `reauthenticate` read back) and returns a ready-to-use `JellyfinAPI`.
    /// The user's name, when the server gave one, is kept for Settings.
    public func storeQuickConnectSuccess(
        serverURL: URL,
        accessToken: String,
        userId: String,
        userName: String? = nil
    ) -> JellyfinAPI? {
        try? keychain.store(serverURL.absoluteString, for: .serverURL)
        keychain.delete(for: .username)
        if let userName, !userName.isEmpty { try? keychain.store(userName, for: .username) }
        try? keychain.store(userId, for: .userId)
        try? keychain.store(accessToken, for: .jwtToken)
        return makeAPI()
    }

    /// Whether there are stored credentials to attempt reauth with.
    /// Useful to decide "show login sheet or try auto-login?" at app launch.
    public var hasStoredCredentials: Bool {
        (try? keychain.loadServerURLString()) != nil
    }

    /// The signed-in user's name, if known (Settings ▸ Server).
    public var savedUserName: String? {
        guard let name = try? keychain.load(String.self, for: .username), !name.isEmpty else { return nil }
        return name
    }

    /// Clears all stored credentials.
    public func logout() {
        keychain.clearAll()
    }

    /// Returns a new `JellyfinAPI` instance with the stored credentials,
    /// or `nil` if none are stored.
    public func makeAPI() -> JellyfinAPI? {
        guard
            let urlStr = try? keychain.loadServerURLString(),
            let token  = try? keychain.loadJWTToken(),
            let userId = try? keychain.load(String.self, for: .userId),
            let url    = URL(string: urlStr)
        else { return nil }
        let api = JellyfinAPI(
            baseURL:     url,
            accessToken: token,
            clientId:    clientId,
            deviceName:  deviceNameString(),
            deviceId:    deviceId.uuidString,
            appVersion:  "1.0"
        )
        api.setUserId(userId)
        return api
    }

    // MARK: - Helpers

    private func deviceNameString() -> String {
        #if canImport(UIKit)
            UIDevice.current.name
        #elseif canImport(Foundation) && os(macOS)
            Host.current().localizedName ?? "Mac"
        #else
            "Device"
        #endif
    }
}
