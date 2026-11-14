// MARK: - JellyfinAPI — HTTP client for the Jellyfin server

import Foundation

/// A minimal, protocol-oriented Jellyfin API client.
///
/// All network calls go through this class. It uses `URLSession` with a
/// configurable base URL and bearer token.
public final class JellyfinAPI {

    // MARK: Properties

    public let baseURL: URL
    public private(set) var accessToken: String?
    public private(set) var userId: String?

    // Device profile — sent in the Authorization: MediaBrowser header on every request
    public let clientId: String
    public let deviceName: String
    public let deviceId: String
    public let appVersion: String

    private let session: URLSession

    // MARK: Init

    public init(
        baseURL: URL,
        accessToken: String? = nil,
        clientId: String = "Tetsubi",
        deviceName: String = "Apple",
        deviceId: String = UUID().uuidString,
        appVersion: String = "1.0"
    ) {
        self.baseURL      = baseURL
        self.accessToken  = accessToken
        self.clientId     = clientId
        self.deviceName   = deviceName
        self.deviceId     = deviceId
        self.appVersion   = appVersion

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource  = 300  // 5-min for album downloads
        config.httpAdditionalHeaders = ["Accept": "application/json"]
        self.session = URLSession(configuration: config)
    }

    /// A URLSession lives until it's invalidated, so free it with this API.
    /// Requests already running still finish. (Sign-in builds a short-lived
    /// API per request — every 2 s while Quick Connect polls.)
    deinit {
        session.finishTasksAndInvalidate()
    }

    public func setAccessToken(_ token: String) {
        self.accessToken = token
    }

    public func setUserId(_ id: String) {
        self.userId = id
    }

    /// `value` as plain ASCII, safe inside the quoted header. Device names
    /// often aren't: a Mac called "Jake’s Mac mini" (curly apostrophe) made
    /// URLSession send a header Jellyfin couldn't read, so every request,
    /// Quick Connect included, failed with HTTP 400. Curly quotes become
    /// straight, accents are dropped (é → e), anything else non-ASCII and any
    /// `"` or `\` is removed.
    static func headerSafe(_ value: String) -> String {
        let straightened = value
            .replacingOccurrences(of: "\u{2018}", with: "'")
            .replacingOccurrences(of: "\u{2019}", with: "'")
            .replacingOccurrences(of: "\u{201C}", with: "")
            .replacingOccurrences(of: "\u{201D}", with: "")
        let latin = straightened.applyingTransform(.toLatin, reverse: false) ?? straightened
        let plain = latin.applyingTransform(.stripDiacritics, reverse: false) ?? latin
        let kept = plain.unicodeScalars.filter { $0.isASCII && $0.value >= 0x20 && $0 != "\"" && $0 != "\\" }
        let result = String(String.UnicodeScalarView(kept))
            .split(separator: " ", omittingEmptySubsequences: true).joined(separator: " ")
        return result.isEmpty ? "Device" : result
    }

    /// Builds the `Authorization: MediaBrowser` header value.
    /// Includes Token when authenticated.
    private func mediaBrowserHeader(token: String? = nil) -> String {
        var parts = [
            "Client=\"\(clientId)\"",
            "Device=\"\(Self.headerSafe(deviceName))\"",
            "DeviceId=\"\(deviceId)\"",
            "Version=\"\(appVersion)\""
        ]
        if let token = token {
            parts.append("Token=\"\(token)\"")
        }
        return "MediaBrowser " + parts.joined(separator: ", ")
    }

    // MARK: - Core request

    struct APIResponse<T: Decodable>: Decodable {
        public let totalCount: Int?
        public let items: [T]

        enum CodingKeys: String, CodingKey {
            case totalCount = "TotalRecordCount"
            case items      = "Items"
        }

        // Handle responses that omit Items entirely
        init(from decoder: any Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            totalCount = try c.decodeIfPresent(Int.self, forKey: .totalCount)
            items      = try c.decodeIfPresent([T].self, forKey: .items) ?? []
        }
    }

    /// GET /Users/{userId}/... with optional query params and body
    func get<T: Decodable>(
        _ path: String,
        queryItems: [URLQueryItem] = [],
        as: T.Type,
        completion: @escaping (Result<T, JellyfinError>) -> Void
    ) {
        guard let request = getRequest(path, queryItems: queryItems) else {
            completion(.failure(.invalidURL))
            return
        }
        session.dataTask(with: request) { data, response, error in
            completion(Self.result(as: T.self, data: data, response: response, error: error))
        }.resume()
    }

    /// The GET request for `path`, with the `Authorization` header set.
    func getRequest(_ path: String, queryItems: [URLQueryItem] = []) -> URLRequest? {
        guard let url = makeURL(path: path, queryItems: queryItems) else { return nil }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(mediaBrowserHeader(token: accessToken), forHTTPHeaderField: "Authorization")
        return request
    }

    /// Turns a GET's answer into a value or an error.
    static func result<T: Decodable>(
        as: T.Type, data: Data?, response: URLResponse?, error: Error?
    ) -> Result<T, JellyfinError> {
        if let error {
            return .failure(.network(underlying: error))
        }
        // Check HTTP status before attempting to decode.
        if let http = response as? HTTPURLResponse, (400 ... 599).contains(http.statusCode) {
            let bodyStr = data.flatMap { String(decoding: $0, as: UTF8.self) } ?? ""
            return .failure(.http(http.statusCode, bodyStr))
        }
        guard let data else {
            return .failure(.noData)
        }
        do {
            return .success(try JSONDecoder().decode(T.self, from: data))
        } catch let e as JellyfinError {
            return .failure(e)
        } catch {
            return .failure(.decode(underlying: error))
        }
    }

    /// POST JSON body
    func post<T: Decodable, B: Encodable>(
        _ path: String,
        body: B,
        as: T.Type,
        completion: @escaping (Result<T, JellyfinError>) -> Void
    ) {
        guard let url = makeURL(path: path) else {
            completion(.failure(.invalidURL))
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(mediaBrowserHeader(token: accessToken), forHTTPHeaderField: "Authorization")
        request.httpBody = try? JSONEncoder().encode(body)
        session.dataTask(with: request) { data, response, error in
            if let error {
                completion(.failure(.network(underlying: error)))
                return
            }
            // Check HTTP status before attempting to decode.
            if let http = response as? HTTPURLResponse, (400 ... 599).contains(http.statusCode) {
                let bodyStr = data.flatMap { String(decoding: $0, as: UTF8.self) } ?? ""
                completion(.failure(.http(http.statusCode, bodyStr)))
                return
            }
            guard let data else {
                completion(.failure(.noData))
                return
            }
            do {
                let decoded = try JSONDecoder().decode(T.self, from: data)
                completion(.success(decoded))
            } catch {
                completion(.failure(.decode(underlying: error)))
            }
        }.resume()
    }

    // MARK: - Auth

    /// POST /Users/AuthenticateByName
    ///
    /// Body only carries username+password — device identity is in the
    /// Authorization: MediaBrowser header (added by `post`).
    public func authenticate(
        username: String,
        password: String,
        completion: @escaping (Result<AuthenticationResponse, JellyfinError>) -> Void
    ) {
        let body = AuthenticationRequest(username: username, password: password)
        post("Users/AuthenticateByName", body: body,
             as: AuthenticationResponse.self, completion: completion)
    }

    /// POST /Users/AuthenticateWithToken  — reauth with existing token
    public func reauthenticate(
        userId: String,
        accessToken: String,
        completion: @escaping (Result<AuthenticationResponse, JellyfinError>) -> Void
    ) {
        guard let url = makeURL(path: "Users/AuthenticateWithToken",
                                queryItems: [
                                    URLQueryItem(name: "UserId", value: userId),
                                    URLQueryItem(name: "AccessToken", value: accessToken)
                                ]) else {
            completion(.failure(.invalidURL))
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(mediaBrowserHeader(token: accessToken), forHTTPHeaderField: "Authorization")
        session.dataTask(with: request) { data, response, error in
            if let error {
                completion(.failure(.network(underlying: error)))
                return
            }
            if let http = response as? HTTPURLResponse, (400 ... 599).contains(http.statusCode) {
                let bodyStr = data.flatMap { String(decoding: $0, as: UTF8.self) } ?? ""
                completion(.failure(.http(http.statusCode, bodyStr)))
                return
            }
            guard let data else {
                completion(.failure(.noData))
                return
            }
            do {
                let decoded = try JSONDecoder().decode(AuthenticationResponse.self, from: data)
                completion(.success(decoded))
            } catch {
                completion(.failure(.decode(underlying: error)))
            }
        }.resume()
    }

    // MARK: - Quick Connect

    /// Builds the `POST /QuickConnect/Initiate` request. Pure (no send) so it's
    /// unit-testable. No body and no user token: the device identity travels
    /// in the `Authorization: MediaBrowser` header.
    public func startQuickConnectRequest() -> URLRequest? {
        guard let url = makeURL(path: "QuickConnect/Initiate") else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(mediaBrowserHeader(token: nil), forHTTPHeaderField: "Authorization")
        return request
    }

    /// Begins a Quick Connect login. Returns the 6-digit code + secret. A 401
    /// from the server means Quick Connect is disabled in its config.
    public func startQuickConnect(
        completion: @escaping (Result<QuickConnectStart, JellyfinError>) -> Void
    ) {
        guard let request = startQuickConnectRequest() else {
            completion(.failure(.invalidURL))
            return
        }
        print("[QuickConnect] POST \(request.url?.absoluteString ?? "?")")
        session.dataTask(with: request) { data, response, error in
            if let error {
                completion(.failure(.network(underlying: error)))
                return
            }
            if let http = response as? HTTPURLResponse, (400 ... 599).contains(http.statusCode) {
                let bodyStr = data.flatMap { String(decoding: $0, as: UTF8.self) } ?? ""
                print("[QuickConnect] /Initiate HTTP \(http.statusCode): \(bodyStr)")
                // Jellyfin answers 401 when Quick Connect is switched off.
                completion(.failure(http.statusCode == 401 ? .quickConnectDisabled
                                                           : .http(http.statusCode, bodyStr)))
                return
            }
            guard let data else {
                completion(.failure(.noData))
                return
            }
            do {
                let decoded = try JSONDecoder().decode(QuickConnectStart.self, from: data)
                completion(.success(decoded))
            } catch {
                completion(.failure(.decode(underlying: error)))
            }
        }.resume()
    }

    /// Builds the `GET /QuickConnect/Connect?secret=` request. Pure so it's
    /// testable; carries NO user token.
    public func quickConnectPollRequest(forSecret secret: String) -> URLRequest? {
        guard let url = makeURL(path: "QuickConnect/Connect",
                                queryItems: [URLQueryItem(name: "secret", value: secret)]) else { return nil }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(mediaBrowserHeader(token: nil), forHTTPHeaderField: "Authorization")
        return request
    }

    /// Polls whether the user has approved the login for `secret`.
    /// A 404 means the secret is unknown/expired.
    public func pollQuickConnect(
        secret: String,
        completion: @escaping (Result<QuickConnectPoll, JellyfinError>) -> Void
    ) {
        guard let request = quickConnectPollRequest(forSecret: secret) else {
            completion(.failure(.invalidURL))
            return
        }
        session.dataTask(with: request) { data, response, error in
            if let error {
                completion(.failure(.network(underlying: error)))
                return
            }
            if let http = response as? HTTPURLResponse, (400 ... 599).contains(http.statusCode) {
                let bodyStr = data.flatMap { String(decoding: $0, as: UTF8.self) } ?? ""
                print("[QuickConnect] /Connect HTTP \(http.statusCode): \(bodyStr)")
                completion(.failure(http.statusCode == 404 ? .quickConnectExpired
                                                           : .http(http.statusCode, bodyStr)))
                return
            }
            guard let data else {
                completion(.failure(.noData))
                return
            }
            do {
                completion(.success(try JSONDecoder().decode(QuickConnectPoll.self, from: data)))
            } catch {
                completion(.failure(.decode(underlying: error)))
            }
        }.resume()
    }

    /// POST /Users/AuthenticateWithQuickConnect — exchanges an approved
    /// secret for an access token + user.
    public func authenticateWithQuickConnect(
        secret: String,
        completion: @escaping (Result<AuthenticationResponse, JellyfinError>) -> Void
    ) {
        post("Users/AuthenticateWithQuickConnect", body: QuickConnectAuthRequest(secret: secret),
             as: AuthenticationResponse.self, completion: completion)
    }

    // MARK: - URL builder

    /// Builds a URL from `baseURL` + `path` + `queryItems`.
    ///
    /// `public` so downstream components (e.g. `StreamURLBuilder`) can
    /// construct authenticated audio stream URLs without going through
    /// `get`/`post`. The `accessToken` is **not** embedded in the URL —
    /// callers must add `api_key=<token>` themselves when handing a URL to
    /// a framework (like AVURLAsset) that won't add headers.
    public func buildURL(_ path: String, queryItems: [URLQueryItem] = []) -> URL? {
        makeURL(path: path, queryItems: queryItems)
    }

    private func makeURL(path: String, queryItems: [URLQueryItem] = []) -> URL? {
        guard var comps = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            return nil
        }
        // Append to the server's own path rather than replacing it, so a
        // server behind a proxy at e.g. https://example.com/jellyfin keeps
        // its "/jellyfin". (Replacing it sent every call to example.com/….)
        var basePath = comps.path
        while basePath.hasSuffix("/") { basePath.removeLast() }
        var relative = Substring(path)
        while relative.hasPrefix("/") { relative.removeFirst() }
        comps.path = basePath + "/" + relative
        if !queryItems.isEmpty {
            comps.queryItems = (comps.queryItems ?? []) + queryItems
        }
        return comps.url
    }

    // MARK: - Music Browsing

    /// Returns up to 12 random albums from the user's library.
    /// GET /Users/{userId}/Items?IncludeItemTypes=MusicAlbum&SortBy=Random&limit=12
    public func randomAlbums(
        limit: Int = 12,
        completion: @escaping (Result<[JellyfinAlbum], JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        // Omit empty ParentId — it would filter to "no parent", which matches nothing.
        let queryItems = [
            URLQueryItem(name: "IncludeItemTypes", value: "MusicAlbum"),
            URLQueryItem(name: "SortBy", value: "Random"),
            URLQueryItem(name: "limit", value: String(limit)),
            URLQueryItem(name: "ImageTypeLimit", value: "1"),
            URLQueryItem(name: "Fields", value: "AlbumArtists,ProductionYear,Album,ParentId"),
            URLQueryItem(name: "Recursive", value: "true"),
        ]
        get("Users/\(userId)/Items",
            queryItems: queryItems,
            as: APIResponse<JellyfinAlbum>.self
        ) { result in
            switch result {
            case .success(let resp): completion(.success(resp.items))
            case .failure(let e):    completion(.failure(e))
            }
        }
    }

    /// Returns recently added albums and tracks.
    /// GET /Users/{userId}/Items/Latest
    ///
    /// Note: `Items/Latest` returns a *bare JSON array* `[ … ]`, NOT the
    /// `{ Items: [ … ] }` envelope the other `/Users/{id}/Items*` endpoints use.
    /// So this decodes `[JellyfinAlbum]` directly — decoding it as `APIResponse`
    /// fails silently (top-level array, not object) and the section stays empty.
    public func recentItems(
        limit: Int = 20,
        itemTypes: String = "MusicAlbum",
        completion: @escaping (Result<[JellyfinAlbum], JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        get("Users/\(userId)/Items/Latest",
            queryItems: [
                URLQueryItem(name: "IncludeItemTypes", value: itemTypes),
                URLQueryItem(name: "limit", value: String(limit)),
                URLQueryItem(name: "ImageTypeLimit", value: "1"),
                URLQueryItem(name: "Fields", value: "Type,Album,AlbumArtists,ProductionYear,PremiereDate")
            ],
            as: [JellyfinAlbum].self
        ) { result in
            switch result {
            case .success(let items): completion(.success(items))
            case .failure(let e):     completion(.failure(e))
            }
        }
    }

    /// The user's favourite albums, newest library additions first (the server
    /// has no "date favourited" sort; `FavoritesStore` reorders client-side).
    /// GET /Items?Filters=IsFavorite&IncludeItemTypes=MusicAlbum&Recursive=true
    public func favoriteAlbums(
        completion: @escaping (Result<[JellyfinAlbum], JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        get("Items",
            queryItems: [
                URLQueryItem(name: "UserId", value: userId),
                URLQueryItem(name: "Recursive", value: "true"),
                URLQueryItem(name: "IncludeItemTypes", value: "MusicAlbum"),
                URLQueryItem(name: "Filters", value: "IsFavorite"),
                URLQueryItem(name: "SortBy", value: "DateCreated"),
                URLQueryItem(name: "SortOrder", value: "Descending"),
                URLQueryItem(name: "Fields", value: "Type,Album,AlbumArtists,ProductionYear,PremiereDate,UserData"),
                URLQueryItem(name: "ImageTypeLimit", value: "1")
            ],
            as: APIResponse<JellyfinAlbum>.self
        ) { result in
            completion(result.map { $0.items })
        }
    }

    /// All album artists, sorted by name.
    /// GET /Artists/AlbumArtists?UserId=&SortBy=SortName
    public func albumArtists(
        completion: @escaping (Result<[JellyfinArtist], JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        get("Artists/AlbumArtists",
            queryItems: [
                URLQueryItem(name: "UserId", value: userId),
                URLQueryItem(name: "SortBy", value: "SortName"),
                URLQueryItem(name: "SortOrder", value: "Ascending")
            ],
            as: APIResponse<JellyfinArtist>.self
        ) { result in
            completion(result.map { $0.items })
        }
    }

    /// Album artists whose name matches `term` (Jellyfin's album search only
    /// matches album titles, so artist searches go through here).
    public func searchArtists(
        term: String,
        limit: Int = 5,
        completion: @escaping (Result<[JellyfinArtist], JellyfinError>) -> Void
    ) {
        guard let userId, !term.trimmingCharacters(in: .whitespaces).isEmpty else {
            completion(.success([]))
            return
        }
        get("Artists/AlbumArtists",
            queryItems: [
                URLQueryItem(name: "UserId", value: userId),
                URLQueryItem(name: "searchTerm", value: term),
                URLQueryItem(name: "Limit", value: String(limit)),
                URLQueryItem(name: "SortBy", value: "SortName"),
            ],
            as: APIResponse<JellyfinArtist>.self
        ) { result in
            completion(result.map { $0.items })
        }
    }

    /// Albums by one album artist, newest release first.
    /// GET /Items?AlbumArtistIds=<id>&IncludeItemTypes=MusicAlbum&Recursive=true
    public func albums(
        forArtist artistId: String,
        completion: @escaping (Result<[JellyfinAlbum], JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        get("Items",
            queryItems: [
                URLQueryItem(name: "UserId", value: userId),
                URLQueryItem(name: "AlbumArtistIds", value: artistId),
                URLQueryItem(name: "Recursive", value: "true"),
                URLQueryItem(name: "IncludeItemTypes", value: "MusicAlbum"),
                URLQueryItem(name: "SortBy", value: "ProductionYear,SortName"),
                URLQueryItem(name: "SortOrder", value: "Descending"),
                URLQueryItem(name: "Fields", value: "Type,Album,AlbumArtists,ProductionYear,PremiereDate"),
                URLQueryItem(name: "ImageTypeLimit", value: "1")
            ],
            as: APIResponse<JellyfinAlbum>.self
        ) { result in
            completion(result.map { $0.items })
        }
    }

    /// Whether the user has favourited the given item.
    /// GET /Items?Ids=<id>&UserId=<user>&Fields=UserData
    public func isFavorite(
        itemId: String,
        completion: @escaping (Result<Bool, JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        get("Items",
            queryItems: [
                URLQueryItem(name: "Ids", value: itemId),
                URLQueryItem(name: "UserId", value: userId),
                URLQueryItem(name: "Fields", value: "UserData")
            ],
            as: APIResponse<JellyfinAlbum>.self
        ) { result in
            completion(result.map { $0.items.first?.userData?.isFavorite ?? false })
        }
    }

    /// Tell the server playback started / stopped so it records PlayCount and
    /// DatePlayed (which drive "Recently Played"). Fire-and-forget.
    /// POST /Sessions/Playing and POST /Sessions/Playing/Stopped
    public func reportPlayback(itemId: String, positionSeconds: TimeInterval, stopped: Bool) {
        guard let url = makeURL(path: stopped ? "Sessions/Playing/Stopped" : "Sessions/Playing") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(mediaBrowserHeader(token: accessToken), forHTTPHeaderField: "Authorization")
        var body: [String: Any] = [
            "ItemId": itemId,
            "PositionTicks": Int64(max(0, positionSeconds) * 10_000_000)
        ]
        if !stopped {
            body["CanSeek"] = true
            body["PlayMethod"] = "DirectPlay"
        }
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        session.dataTask(with: request) { _, response, error in
            if let error {
                print("[JellyfinAPI] reportPlayback failed: \(error.localizedDescription)")
            } else if let http = response as? HTTPURLResponse, (400 ... 599).contains(http.statusCode) {
                print("[JellyfinAPI] reportPlayback HTTP \(http.statusCode)")
            }
        }.resume()
    }

    /// Mark / unmark an item as a favourite.
    /// POST (add) or DELETE (remove) /UserFavoriteItems/{itemId}?userId=
    public func setFavorite(
        itemId: String,
        isFavorite: Bool,
        completion: @escaping (Result<Void, JellyfinError>) -> Void
    ) {
        guard let userId,
              let url = makeURL(path: "UserFavoriteItems/\(itemId)",
                                queryItems: [URLQueryItem(name: "userId", value: userId)])
        else {
            completion(.failure(.missingCredentials))
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = isFavorite ? "POST" : "DELETE"
        request.setValue(mediaBrowserHeader(token: accessToken), forHTTPHeaderField: "Authorization")
        session.dataTask(with: request) { data, response, error in
            if let error {
                completion(.failure(.network(underlying: error)))
            } else if let http = response as? HTTPURLResponse, (400 ... 599).contains(http.statusCode) {
                completion(.failure(.http(http.statusCode, data.map { String(decoding: $0, as: UTF8.self) } ?? "")))
            } else {
                completion(.success(()))
            }
        }.resume()
    }

    /// Every album's ID, name and release date, and nothing else (no image
    /// info, artists or play counts): what On This Day checks. Much smaller
    /// than `allMusicAlbums` on a big library. Details for the few matches
    /// come from `albums(ids:)`.
    public func albumReleaseDates(
        completion: @escaping (Result<[JellyfinAlbum], JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        get("Users/\(userId)/Items",
            queryItems: [
                URLQueryItem(name: "IncludeItemTypes", value: "MusicAlbum"),
                URLQueryItem(name: "Recursive", value: "true"),
                URLQueryItem(name: "Fields", value: "ProductionYear,PremiereDate"),
                URLQueryItem(name: "EnableImages", value: "false"),
                URLQueryItem(name: "EnableUserData", value: "false"),
                URLQueryItem(name: "EnableTotalRecordCount", value: "false"),
            ],
            as: APIResponse<JellyfinAlbum>.self
        ) { completion($0.map(\.items)) }
    }

    /// These albums in full (artists, year, release date, art).
    public func albums(
        ids: [String],
        completion: @escaping (Result<[JellyfinAlbum], JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        guard !ids.isEmpty else {
            completion(.success([]))
            return
        }
        get("Users/\(userId)/Items",
            queryItems: [
                URLQueryItem(name: "Ids", value: ids.joined(separator: ",")),
                URLQueryItem(name: "Fields", value: "AlbumArtists,Album,ProductionYear,PremiereDate"),
                URLQueryItem(name: "ImageTypeLimit", value: "1"),
            ],
            as: APIResponse<JellyfinAlbum>.self
        ) { completion($0.map(\.items)) }
    }

    /// Returns ALL music albums in the user's library.
    /// GET /Users/{userId}/Items?IncludeItemTypes=MusicAlbum&Recursive=true
    /// Used by "On This Day" which needs the full catalog, not just recent additions.
    public func allMusicAlbums(
        limit: Int = 10000,
        completion: @escaping (Result<[JellyfinAlbum], JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        get("Users/\(userId)/Items",
            queryItems: [
                URLQueryItem(name: "IncludeItemTypes", value: "MusicAlbum"),
                URLQueryItem(name: "Recursive", value: "true"),
                URLQueryItem(name: "limit", value: String(limit)),
                URLQueryItem(name: "SortBy", value: "DateAdded"),
                URLQueryItem(name: "SortOrder", value: "Descending"),
                URLQueryItem(name: "Fields", value: "AlbumArtists,Album,ProductionYear,PremiereDate"),
                URLQueryItem(name: "ImageTypeLimit", value: "1")
            ],
            as: APIResponse<JellyfinAlbum>.self
        ) { result in
            switch result {
            case .success(let resp): completion(.success(resp.items))
            case .failure(let e):    completion(.failure(e))
            }
        }
    }

    /// Returns up to `limit` distinct albums the user has most recently played,
    /// most-recently-played first.
    ///
    /// Does *not* use `Items/Resume` (that only returns in-progress resume
    /// points, so tracks played to completion never appear). Instead it queries
    /// audio items sorted by `DatePlayed` descending and groups played tracks
    /// into distinct albums.
    ///
    /// Crucially, the "has been played" test keys off `UserData.PlayCount`, not
    /// `DatePlayed`/`IsPlayed` — for many items Jellyfin records a play count
    /// (e.g. 1) but leaves `DatePlayed`/`IsPlayed` nil, so the latter would miss
    /// everything. `Fields` must include `UserData` for any play info to come back.
    ///
    /// GET /Users/{userId}/Items?Recursive=true&IncludeItemTypes=Audio
    ///     &SortBy=DatePlayed&SortOrder=Descending&Fields=Album,AlbumArtists,UserData
    public func recentlyPlayedAlbums(
        limit: Int = 12,
        completion: @escaping (Result<[JellyfinAlbum], JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        // Fetch more tracks than we need so we can de-duplicate to distinct
        // albums and still fill `limit`.
        get("Users/\(userId)/Items",
            queryItems: [
                URLQueryItem(name: "Recursive", value: "true"),
                URLQueryItem(name: "IncludeItemTypes", value: "Audio"),
                URLQueryItem(name: "SortBy", value: "DatePlayed"),
                URLQueryItem(name: "SortOrder", value: "Descending"),
                URLQueryItem(name: "limit", value: String(max(limit * 20, 200))),
                URLQueryItem(name: "Fields", value: "Album,AlbumArtists,ProductionYear,UserData"),
                URLQueryItem(name: "ImageTypeLimit", value: "1")
            ],
            as: APIResponse<JellyfinAlbum>.self
        ) { result in
            switch result {
            case .success(let resp):
                // Group tracks into distinct recently-played albums, keeping only
                // items the user has actually played.
                let albums = Self.distinctPlayedAlbums(resp.items, limit: limit)
                completion(.success(albums))
            case .failure(let e):
                completion(.failure(e))
            }
        }
    }

    /// Recently played tracks (each standing for its album) down to one
    /// entry per album, newest first, at most `limit`, keeping only items
    /// actually played (play count, or a last-played time). Once per album
    /// name and artist, and once per album ID: tracks of one album can carry
    /// different artists (a split, a guest) and would otherwise repeat it.
    static func distinctPlayedAlbums(_ items: [JellyfinAlbum], limit: Int) -> [JellyfinAlbum] {
        var seen = Set<String>()
        var seenIDs = Set<String>()
        var albums: [JellyfinAlbum] = []
        for item in items {
            guard item.hasBeenPlayed, !seenIDs.contains(item.id) else { continue }
            let key = "\(item.album ?? item.name ?? "")|\(item.artistName)"
            guard seen.insert(key).inserted else { continue }
            seenIDs.insert(item.id)
            albums.append(item)
            if albums.count >= limit { break }
        }
        return albums
    }

    /// Returns the full track list for a given album.
    /// GET /Items?ParentId={albumId}&UserId={userId}
    public func tracksForAlbum(
        _ albumId: String,
        completion: @escaping (Result<[JellyfinTrack], JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        get("Items",
            queryItems: [
                URLQueryItem(name: "ParentId", value: albumId),
                URLQueryItem(name: "UserId", value: userId),
                URLQueryItem(name: "IncludeItemTypes", value: "Audio"),
                URLQueryItem(name: "SortBy", value: "ParentIndexNumber,IndexNumber,SortName"),
                URLQueryItem(name: "SortOrder", value: "Ascending"),
                URLQueryItem(name: "Recursive", value: "false"),
                URLQueryItem(name: "Fields", value: "ProviderIds,MediaSources,NormalizationGain,ImageBlurHashes")
            ],
            as: APIResponse<JellyfinTrack>.self
        ) { result in
            switch result {
            case .success(let resp):
                // Defensive: only keep tracks that belong to this album, even if
                // the server ignores the ParentId filter.
                let own = resp.items.filter {
                    guard let tid = $0.albumId else { return true }
                    return tid.caseInsensitiveCompare(albumId) == .orderedSame
                }
                if own.count != resp.items.count {
                    print("[JellyfinAPI] tracksForAlbum(\(albumId)): dropped \(resp.items.count - own.count) of \(resp.items.count) tracks from other albums")
                }
                completion(.success(own))
            case .failure(let e):    completion(.failure(e))
            }
        }
    }

    /// The artist's MusicBrainz ID, if the server has matched it.
    public func musicBrainzArtistID(artistId: String) async -> String? {
        struct Item: Decodable {
            let providerIds: [String: String]?
            enum CodingKeys: String, CodingKey { case providerIds = "ProviderIds" }
        }
        guard let userId else { return nil }
        return await withCheckedContinuation { continuation in
            get("Users/\(userId)/Items/\(artistId)", as: Item.self) { result in
                let id = (try? result.get())?.providerIds?["MusicBrainzArtist"]
                continuation.resume(returning: (id?.isEmpty ?? true) ? nil : id)
            }
        }
    }

    /// Fetches a single album's metadata.
    /// GET /Users/{userId}/Items/{albumId}
    public func album(
        _ albumId: String,
        completion: @escaping (Result<JellyfinAlbum, JellyfinError>) -> Void
    ) {
        guard let userId else {
            completion(.failure(.missingCredentials))
            return
        }
        get("Users/\(userId)/Items/\(albumId)",
            as: JellyfinAlbum.self
        ) { result in
            switch result {
            case .success(let album): completion(.success(album))
            case .failure(let e):     completion(.failure(e))
            }
        }
    }

    // MARK: - Playlists

    /// The request behind `playlists`: every music playlist, with its
    /// song count, playing time and creation date.
    func playlistsRequest() -> URLRequest? {
        guard let userId else { return nil }
        return getRequest("Users/\(userId)/Items", queryItems: [
            URLQueryItem(name: "IncludeItemTypes", value: "Playlist"),
            URLQueryItem(name: "MediaTypes", value: "Audio"),
            URLQueryItem(name: "Recursive", value: "true"),
            URLQueryItem(name: "Fields", value: "ChildCount,CumulativeRunTimeTicks,DateCreated")
        ])
    }

    /// The request behind `searchPlaylists`: as `playlistsRequest`, for
    /// the music playlists whose name matches `term`.
    func searchPlaylistsRequest(term: String) -> URLRequest? {
        guard let userId else { return nil }
        return getRequest("Users/\(userId)/Items", queryItems: [
            URLQueryItem(name: "SearchTerm", value: term),
            URLQueryItem(name: "IncludeItemTypes", value: "Playlist"),
            URLQueryItem(name: "MediaTypes", value: "Audio"),
            URLQueryItem(name: "Recursive", value: "true"),
            URLQueryItem(name: "Fields", value: "ChildCount,CumulativeRunTimeTicks,DateCreated")
        ])
    }

    /// The request behind `playlistTracks`.
    func playlistTracksRequest(_ playlistId: String) -> URLRequest? {
        guard let userId else { return nil }
        return getRequest("Playlists/\(playlistId)/Items", queryItems: [
            URLQueryItem(name: "userId", value: userId),
            URLQueryItem(name: "Fields", value: "MediaSources,NormalizationGain")
        ])
    }

    /// The user's music playlists, in the server's order.
    /// GET /Users/{userId}/Items?IncludeItemTypes=Playlist&MediaTypes=Audio
    public func playlists(
        completion: @escaping (Result<[JellyfinPlaylist], JellyfinError>) -> Void
    ) {
        guard let request = playlistsRequest() else {
            completion(.failure(.missingCredentials))
            return
        }
        session.dataTask(with: request) { data, response, error in
            completion(Self.playlistsResult(data: data, response: response, error: error))
        }.resume()
    }

    /// The music playlists whose name matches `term` (none for a blank one).
    /// GET /Users/{userId}/Items?IncludeItemTypes=Playlist&MediaTypes=Audio&SearchTerm=…
    public func searchPlaylists(
        term: String,
        completion: @escaping (Result<[JellyfinPlaylist], JellyfinError>) -> Void
    ) {
        let term = term.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else {
            completion(.success([]))
            return
        }
        guard let request = searchPlaylistsRequest(term: term) else {
            completion(.failure(.missingCredentials))
            return
        }
        session.dataTask(with: request) { data, response, error in
            completion(Self.playlistsResult(data: data, response: response, error: error))
        }.resume()
    }

    /// A playlist's songs in playlist order; a song added twice is there
    /// twice. A playlist that's gone answers HTTP 404.
    /// GET /Playlists/{playlistId}/Items?userId={userId}
    public func playlistTracks(
        _ playlistId: String,
        completion: @escaping (Result<[PlaylistEntry], JellyfinError>) -> Void
    ) {
        guard let request = playlistTracksRequest(playlistId) else {
            completion(.failure(.missingCredentials))
            return
        }
        session.dataTask(with: request) { data, response, error in
            completion(Self.playlistTracksResult(data: data, response: response, error: error))
        }.resume()
    }

    static func playlistsResult(
        data: Data?, response: URLResponse?, error: Error?
    ) -> Result<[JellyfinPlaylist], JellyfinError> {
        result(as: APIResponse<JellyfinPlaylist>.self, data: data, response: response, error: error).map(\.items)
    }

    static func playlistTracksResult(
        data: Data?, response: URLResponse?, error: Error?
    ) -> Result<[PlaylistEntry], JellyfinError> {
        result(as: PlaylistEntryList.self, data: data, response: response, error: error).map(\.entries)
    }

    // MARK: - Playlists: making and editing

    /// A request that changes something: `method` on `path`, with a JSON
    /// `body` when there is one.
    private func writeRequest(_ method: String, _ path: String, queryItems: [URLQueryItem] = [],
                              body: (some Encodable)? = String?.none) -> URLRequest? {
        guard let url = makeURL(path: path, queryItems: queryItems) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue(mediaBrowserHeader(token: accessToken), forHTTPHeaderField: "Authorization")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? JSONEncoder().encode(body)
        }
        return request
    }

    private struct NewPlaylistBody: Encodable {
        let name: String
        let ids: [String]
        let userId: String
        let mediaType = "Audio"
        let isPublic = false
        enum CodingKeys: String, CodingKey {
            case name = "Name", ids = "Ids", userId = "UserId", mediaType = "MediaType", isPublic = "IsPublic"
        }
    }

    private struct PlaylistItemsBody: Encodable {
        let ids: [String]
        enum CodingKeys: String, CodingKey { case ids = "Ids" }
    }

    private struct PlaylistNameBody: Encodable {
        let name: String
        enum CodingKeys: String, CodingKey { case name = "Name" }
    }

    private struct NewPlaylistAnswer: Decodable {
        let id: String
        enum CodingKeys: String, CodingKey { case id = "Id" }
    }

    private struct PlaylistUserAnswer: Decodable {
        let canEdit: Bool?
        enum CodingKeys: String, CodingKey { case canEdit = "CanEdit" }
    }

    /// The request behind `createPlaylist`. New playlists are private.
    func createPlaylistRequest(name: String, trackIds: [String]) -> URLRequest? {
        guard let userId else { return nil }
        return writeRequest("POST", "Playlists", body: NewPlaylistBody(name: name, ids: trackIds, userId: userId))
    }

    /// The request behind `addToPlaylist`.
    func addToPlaylistRequest(_ playlistId: String, trackIds: [String]) -> URLRequest? {
        guard let userId else { return nil }
        return writeRequest("POST", "Playlists/\(playlistId)/Items", queryItems: [
            URLQueryItem(name: "ids", value: trackIds.joined(separator: ",")),
            URLQueryItem(name: "userId", value: userId)
        ])
    }

    /// The request behind `setPlaylistItems`.
    func setPlaylistItemsRequest(_ playlistId: String, trackIds: [String]) -> URLRequest? {
        writeRequest("POST", "Playlists/\(playlistId)", body: PlaylistItemsBody(ids: trackIds))
    }

    /// The request behind `renamePlaylist`.
    func renamePlaylistRequest(_ playlistId: String, name: String) -> URLRequest? {
        writeRequest("POST", "Playlists/\(playlistId)", body: PlaylistNameBody(name: name))
    }

    /// The request behind `canEditPlaylist`.
    func canEditPlaylistRequest(_ playlistId: String) -> URLRequest? {
        guard let userId else { return nil }
        return getRequest("Playlists/\(playlistId)/Users/\(userId)")
    }

    /// The request behind `deletePlaylist`.
    func deletePlaylistRequest(_ playlistId: String) -> URLRequest? {
        writeRequest("DELETE", "Items/\(playlistId)")
    }

    /// Makes a private music playlist holding `trackIds` in that order
    /// (none makes an empty one) and answers its ID.
    /// POST /Playlists
    public func createPlaylist(
        name: String,
        trackIds: [String],
        completion: @escaping (Result<String, JellyfinError>) -> Void
    ) {
        guard let request = createPlaylistRequest(name: name, trackIds: trackIds) else {
            completion(.failure(.missingCredentials))
            return
        }
        session.dataTask(with: request) { data, response, error in
            completion(Self.createPlaylistResult(data: data, response: response, error: error))
        }.resume()
    }

    /// Adds songs to the end of a playlist. A song already there is added
    /// again; the caller asks about duplicates first (`PlaylistAdd`).
    /// POST /Playlists/{playlistId}/Items?ids=a,b&userId={userId}
    public func addToPlaylist(
        _ playlistId: String,
        trackIds: [String],
        completion: @escaping (Result<Void, JellyfinError>) -> Void
    ) {
        sendWrite(addToPlaylistRequest(playlistId, trackIds: trackIds), completion: completion)
    }

    /// Sets a playlist to exactly these songs in this order (a song listed
    /// twice stays twice; none empties it). Edits send the whole new order
    /// this way: the server's Move and Remove go by an entry ID that two
    /// copies of one song share.
    /// POST /Playlists/{playlistId} {"Ids": […]}
    public func setPlaylistItems(
        _ playlistId: String,
        trackIds: [String],
        completion: @escaping (Result<Void, JellyfinError>) -> Void
    ) {
        sendWrite(setPlaylistItemsRequest(playlistId, trackIds: trackIds), completion: completion)
    }

    /// Renames a playlist; its songs stay. The server takes any name, so
    /// the caller checks it first (`PlaylistName`).
    /// POST /Playlists/{playlistId} {"Name": …}
    public func renamePlaylist(
        _ playlistId: String,
        name: String,
        completion: @escaping (Result<Void, JellyfinError>) -> Void
    ) {
        sendWrite(renamePlaylistRequest(playlistId, name: name), completion: completion)
    }

    /// Whether the signed-in user may change the playlist. A playlist
    /// that's gone (HTTP 404), or any other failure, counts as no.
    /// GET /Playlists/{playlistId}/Users/{userId}
    public func canEditPlaylist(_ playlistId: String, completion: @escaping (Bool) -> Void) {
        guard let request = canEditPlaylistRequest(playlistId) else {
            completion(false)
            return
        }
        session.dataTask(with: request) { data, response, error in
            completion(Self.canEditPlaylistResult(data: data, response: response, error: error))
        }.resume()
    }

    /// Deletes a playlist from the server. Its songs stay in the library.
    /// DELETE /Items/{playlistId}
    public func deletePlaylist(
        _ playlistId: String,
        completion: @escaping (Result<Void, JellyfinError>) -> Void
    ) {
        sendWrite(deletePlaylistRequest(playlistId), completion: completion)
    }

    /// Sends a request whose answer has no body (HTTP 204).
    private func sendWrite(_ request: URLRequest?, completion: @escaping (Result<Void, JellyfinError>) -> Void) {
        guard let request else {
            completion(.failure(.missingCredentials))
            return
        }
        session.dataTask(with: request) { data, response, error in
            completion(Self.emptyResult(data: data, response: response, error: error))
        }.resume()
    }

    /// The answer of a call that returns nothing: fine unless the
    /// connection failed or the server answered with an error status.
    static func emptyResult(data: Data?, response: URLResponse?, error: Error?) -> Result<Void, JellyfinError> {
        if let error {
            return .failure(.network(underlying: error))
        }
        if let http = response as? HTTPURLResponse, (400 ... 599).contains(http.statusCode) {
            return .failure(.http(http.statusCode, data.map { String(decoding: $0, as: UTF8.self) } ?? ""))
        }
        return .success(())
    }

    static func createPlaylistResult(
        data: Data?, response: URLResponse?, error: Error?
    ) -> Result<String, JellyfinError> {
        result(as: NewPlaylistAnswer.self, data: data, response: response, error: error).map(\.id)
    }

    static func canEditPlaylistResult(data: Data?, response: URLResponse?, error: Error?) -> Bool {
        let answer = try? result(as: PlaylistUserAnswer.self, data: data, response: response, error: error).get()
        return answer?.canEdit ?? false
    }

    /// Builds a `URLRequest` for the album art of a given item at a given
    /// size, with the `Authorization: MediaBrowser` header set so the
    /// server accepts the user's JWT.
    ///
    /// This replaces the old `albumArtURL` approach of baking `api_key=<token>`
    /// into the query string. Jellyfin 12.x treats `api_key` as a *server-level
    /// API key*, not a user JWT, so the query-param approach silently fails on
    /// image endpoints. `URLRequest` lets `AuthenticatedImage` send the proper
    /// `Authorization` header.
    ///
    /// Returns `nil` when `userId` is unset.
    public func imageURLRequest(for itemId: String, size: Int = 500) -> URLRequest? {
        guard let url = makeURL(path: "Items/\(itemId)/Images/Primary",
                                queryItems: [
                                    URLQueryItem(name: "maxWidth", value: String(size)),
                                    URLQueryItem(name: "quality", value: "90"),
                                ])
        else { return nil }
        var request = URLRequest(url: url)
        // Jellyfin accepts both the modern `Authorization: MediaBrowser` header
        // and the legacy `X-Emby-Token` header. Set both to be safe — some
        // image endpoints or proxy layers might only check one.
        request.setValue(mediaBrowserHeader(token: accessToken),
                         forHTTPHeaderField: "Authorization")
        if let token = accessToken {
            request.setValue(token, forHTTPHeaderField: "X-Emby-Token")
        }
        return request
    }

    /// Legacy: returns a bare URL with `api_key` in the query string.
    ///
    /// Kept for `PlaybackTrack.albumArtURL` and any non-SwUI consumers
    /// (e.g. `StreamURLBuilder`-style use with `AVURLAsset`, which also
    /// can't add headers). Prefer `imageURLRequest` for SwiftUI views.
    public func albumArtURL(for itemId: String, size: Int = 500) -> URL? {
        var query: [URLQueryItem] = [
            URLQueryItem(name: "maxWidth", value: String(size)),
            URLQueryItem(name: "quality", value: "90"),
        ]
        if let token = accessToken {
            query.append(URLQueryItem(name: "api_key", value: token))
        }
        return makeURL(path: "Items/\(itemId)/Images/Primary",
                       queryItems: query)
    }

    /// Searches the user's library for albums matching `term`.
    ///
    /// Jellyfin `SearchTerm` matches album names, artists, and tags.
    /// Returns an `APIResponse<JellyfinAlbum>` like the other browse calls.
    public func searchForAlbums(
        term: String,
        limit: Int = 50,
        completion: @escaping (Result<[JellyfinAlbum], JellyfinError>) -> Void
    ) {
        guard let userId, !term.trimmingCharacters(in: .whitespaces).isEmpty else {
            completion(.success([]))
            return
        }
        get("Users/\(userId)/Items",
            queryItems: [
                URLQueryItem(name: "SearchTerm", value: term),
                URLQueryItem(name: "IncludeItemTypes", value: "MusicAlbum"),
                URLQueryItem(name: "Recursive", value: "true"),
                URLQueryItem(name: "limit", value: String(limit)),
                URLQueryItem(name: "SortBy", value: "Name"),
                URLQueryItem(name: "SortOrder", value: "Ascending"),
                URLQueryItem(name: "Fields", value: "AlbumArtists,Album,ProductionYear,PremiereDate"),
                URLQueryItem(name: "ImageTypeLimit", value: "1"),
            ],
            as: APIResponse<JellyfinAlbum>.self
        ) { result in
            switch result {
            case .success(let resp): completion(.success(resp.items))
            case .failure(let e):    completion(.failure(e))
            }
        }
    }
}

// MARK: - API Models (placeholders — flesh out in Phase 1)

public struct AuthenticationRequest: Encodable {
    public let username: String
    public let password: String

    public init(username: String, password: String) {
        self.username = username
        self.password = password
    }

    // Jellyfin uses PascalCase and expects the password field to be "Pw"
    enum CodingKeys: String, CodingKey {
        case username = "Username"
        case password = "Pw"
    }
}

public struct AuthenticationResponse: Decodable {
    public let userId: String?
    public let accessToken: String?
    public let serverId: String?
    public struct SessionInfo: Decodable {
        public let userId: String?
        public let id: String?
        enum CodingKeys: String, CodingKey {
            case userId = "UserId"
            case id     = "Id"
        }
    }
    public let sessionInfo: SessionInfo?
    public struct UserInfo: Decodable {
        public let name: String?
        enum CodingKeys: String, CodingKey {
            case name = "Name"
        }
    }
    /// The signed-in user (for their name).
    public let user: UserInfo?

    /// Resolved userId — top-level first, falls back to SessionInfo.
    public var resolvedUserId: String? {
        userId ?? sessionInfo?.userId ?? sessionInfo?.id
    }

    enum CodingKeys: String, CodingKey {
        case userId      = "UserId"
        case accessToken = "AccessToken"
        case serverId    = "ServerId"
        case sessionInfo = "SessionInfo"
        case user        = "User"
    }
}

// MARK: - Quick Connect models

/// Body for `POST /Users/AuthenticateWithQuickConnect`.
struct QuickConnectAuthRequest: Encodable {
    let secret: String
    enum CodingKeys: String, CodingKey { case secret = "Secret" }
}

/// Response of `POST /QuickConnect/Initiate`. `code` is the 6-digit value the
/// user enters on another device; `secret` is used for polling and the final
/// token exchange.
public struct QuickConnectStart: Decodable {
    public let code: String?
    public let secret: String?
    public let authenticated: Bool?

    enum CodingKeys: String, CodingKey {
        case code          = "Code"
        case secret        = "Secret"
        case authenticated = "Authenticated"
    }
}

/// Response of `GET /QuickConnect/Connect`. `authenticated` becomes true once
/// the user approves the code on another device.
public struct QuickConnectPoll: Decodable {
    public let authenticated: Bool?

    enum CodingKeys: String, CodingKey {
        case authenticated = "Authenticated"
    }
}

// MARK: - Quick Connect poll result

/// Outcome of a single `/QuickConnect/Connect` poll.
public enum QuickConnectStatus: Equatable {
    case pending
    case approved
}

/// Maps a decoded `QuickConnectPoll` to a `QuickConnectStatus`.
public func quickConnectStatus(from poll: QuickConnectPoll) -> QuickConnectStatus {
    poll.authenticated == true ? .approved : .pending
}

public enum JellyfinError: LocalizedError {
    case invalidURL
    case network(underlying: Error)
    case noData
    case decode(underlying: Error)
    case http(Int, String)          // statusCode, body
    case expiredToken
    case missingCredentials
    case quickConnectDisabled
    case quickConnectRejected
    case quickConnectExpired

    public var errorDescription: String? {
        switch self {
        case .invalidURL:
            "Invalid URL — check the server address."
        case .network(let e):
            "Network error: \(e.localizedDescription)"
        case .noData:
            "No data returned from server."
        case .decode(let e):
            "Failed to parse response: \(e.localizedDescription)"
        case .http(let code, let body):
            "HTTP \(code)" + (body.isEmpty ? "" : " — \(body.prefix(200))")
        case .expiredToken:
            "Session expired — please sign in again."
        case .missingCredentials:
            "No server configured. Please set up a connection."
        case .quickConnectDisabled:
            "Quick Connect isn't available on this server — use username & password."
        case .quickConnectRejected:
            "The login request was rejected. Try again or use username & password."
        case .quickConnectExpired:
            "The Quick Connect code expired — try again or use username & password."
        }
    }
}
