// MARK: - KeychainManager — stores JWT token and server config in the Keychain

import Foundation
import Security

/// Stores and retrieves JWT access token and server config from the system keychain.
///
/// The JWT and server URL are the two most sensitive items. They are stored here
/// so they are never written to the Swift Data store or UserDefaults.
public final class KeychainManager {

    /// Keys for keychain items.  Public so callers in other modules
    /// can reference a subset, though the convenience methods below
    /// cover the common cases.
    public enum Key {
        case jwtToken
        case serverURL
        case username
        case userId
        case deviceId

        var keychainKey: String {
            switch self {
            case .jwtToken: "musicplayer.jwt"
            case .serverURL: "musicplayer.serverURL"
            case .username:  "musicplayer.username"
            case .userId:    "musicplayer.userId"
            case .deviceId:  "musicplayer.deviceId"
            }
        }
    }

    /// App Group identifier — allows sharing secrets between the main app
    /// and CarPlay / widget extensions if added later.
    private let accessGroup: String

    // MARK: - Init

    public init(accessGroup: String = "group.gg.dekiru.musicplayer.shared") {
        self.accessGroup = accessGroup
    }

    // MARK: - Storage

    /// Groups to try, in order. The shared group needs a signed build with the
    /// entitlement; without it (-34018) we fall back to the app's own keychain
    /// so the login is still remembered. The Mac app has no App Group, so it
    /// uses its own keychain only.
    /// iOS always uses the data protection keychain. On the Mac it needs a
    /// provisioning profile (application-identifier entitlement), which the
    /// sandboxed Mac build doesn't carry, so every save failed with -34018 and
    /// sign-in couldn't be kept. The Mac uses the standard login keychain.
    static var useDataProtectionKeychain: Bool {
        #if os(macOS)
        false
        #else
        true
        #endif
    }

    /// On the Mac, entries are labelled with the app's bundle ID
    /// (`kSecAttrService`): the login keychain is shared by every program, and
    /// without a label another program saving under the same names (the test
    /// runner did) collides with Tetsubi's entry and can't replace it (-25299).
    static var macService: String { Bundle.main.bundleIdentifier ?? "Tetsubi" }

    /// Which keychain an item lives in: data protection on iOS, the login
    /// keychain with Tetsubi's label on the Mac.
    private static func applyStore(to query: inout [String: Any]) {
        if useDataProtectionKeychain {
            query[kSecUseDataProtectionKeychain as String] = true
        } else {
            query[kSecAttrService as String] = macService
        }
    }

    private var groups: [String?] {
        #if os(macOS)
        [nil]
        #else
        [accessGroup, nil]
        #endif
    }

    public func store<T: Encodable>(_ value: T, for key: Key) throws {
        let data: Data = try JSONEncoder().encode(value)
        var lastStatus: OSStatus = errSecSuccess
        for group in groups {
            var query: [String: Any] = [
                kSecClass as String:       kSecClassGenericPassword,
                kSecAttrAccount as String: key.keychainKey
            ]
            if let group { query[kSecAttrAccessGroup as String] = group }
            Self.applyStore(to: &query)
            SecItemDelete(query as CFDictionary)
            query[kSecValueData as String] = data
            // Allow background access — critical for a background music player
            query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            lastStatus = SecItemAdd(query as CFDictionary, nil)
            if lastStatus == errSecSuccess { return }
        }
        print("[Keychain] store \(key.keychainKey) failed: \(lastStatus)")
        throw KeychainError.add(lastStatus)
    }

    public func load<T: Decodable>(_ type: T.Type, for key: Key) throws -> T? {
        for group in groups {
            var query: [String: Any] = [
                kSecClass as String:       kSecClassGenericPassword,
                kSecAttrAccount as String: key.keychainKey,
                kSecReturnData as String:  true,
                kSecMatchLimit as String:  kSecMatchLimitOne,
            ]
            Self.applyStore(to: &query)
            if let group { query[kSecAttrAccessGroup as String] = group }
            var result: AnyObject?
            if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
               let data = result as? Data {
                return try JSONDecoder().decode(T.self, from: data)
            }
        }
        return nil
    }

    public func delete(for key: Key) {
        for group in groups {
            var query: [String: Any] = [
                kSecClass as String:       kSecClassGenericPassword,
                kSecAttrAccount as String: key.keychainKey,
            ]
            Self.applyStore(to: &query)
            if let group { query[kSecAttrAccessGroup as String] = group }
            SecItemDelete(query as CFDictionary)
        }
    }

    public func clearAll() {
        for key in [Key.jwtToken, Key.serverURL, Key.username, Key.userId, Key.deviceId] {
            delete(for: key)
        }
    }

    // MARK: - Convenience

    public func loadJWTToken() throws -> String? {
        try load(String.self, for: .jwtToken)
    }

    public func storeJWTToken(_ token: String) throws {
        try store(token, for: .jwtToken)
    }

    public func deleteJWTToken() {
        delete(for: .jwtToken)
    }

    public func loadServerURLString() throws -> String? {
        try load(String.self, for: .serverURL)
    }

    public func storeServerURLString(_ url: String) throws {
        try store(url, for: .serverURL)
    }
}

// MARK: - Errors

public enum KeychainError: Error, CustomStringConvertible {
    case add(OSStatus)
    case update(OSStatus)
    case copy(OSStatus)

    public var description: String {
        switch self {
        case .add(let s):    "Keychain add failed: \(s)"
        case .update(let s): "Keychain update failed: \(s)"
        case .copy(let s):   "Keychain load failed: \(s)"
        }
    }
}
