// MARK: - ServerAddress — turn what the user typed into a working server address

import Foundation

/// Makes the typed server address forgiving. People type "192.168.1.20",
/// "jellyfin-box:8096" or "http://192.168.1.20" (no port); this works out the
/// likely full addresses and uses the first one where a Jellyfin server
/// answers (`/System/Info/Public`, which needs no sign-in).
///
/// - No `http://` or `https://`: `http://` is added.
/// - No port: the address as typed is tried first (a server behind a web
///   proxy, e.g. https://music.example.com, uses the standard port), then
///   Jellyfin's own port: 8096 for http, 8920 for https. With no scheme
///   either, http on 8096 comes first, as that's how a home server usually
///   runs.
public enum ServerAddress {

    public static let httpPort = 8096
    public static let httpsPort = 8920

    public enum ResolveError: LocalizedError, Equatable {
        /// Not an address at all (empty, spaces, no host).
        case invalid
        /// Nothing that answered was a Jellyfin server.
        case notFound(String)

        public var errorDescription: String? {
            switch self {
            case .invalid:
                return "Enter your server's address, e.g. http://192.168.1.20:8096"
            case .notFound(let input):
                return "Couldn't find a Jellyfin server at \(input). Check the address, and the port (usually 8096)."
            }
        }
    }

    /// The addresses to try, most likely first. Empty if `input` can't be
    /// an address.
    public static func candidates(for input: String) -> [URL] {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        while text.hasSuffix("/") { text.removeLast() }
        guard !text.isEmpty, !text.contains(" ") else { return [] }

        let lower = text.lowercased()
        let hadScheme = lower.hasPrefix("http://") || lower.hasPrefix("https://")
        if !hadScheme {
            // "jellyfin-box:8096" would otherwise read as scheme "jellyfin-box".
            guard !text.contains("://") else { return [] }
            text = "http://" + text
        }
        guard let parts = URLComponents(string: text),
              let scheme = parts.scheme?.lowercased(),
              let host = parts.host, !host.isEmpty
        else { return [] }

        func url(scheme: String, port: Int?) -> URL? {
            var p = parts
            p.scheme = scheme
            p.port = port
            return p.url
        }

        var list: [URL?]
        if let port = parts.port {
            // Port given: use it; without a scheme, https is the fallback.
            list = [url(scheme: scheme, port: port)]
            if !hadScheme { list.append(url(scheme: "https", port: port)) }
        } else if !hadScheme {
            list = [url(scheme: "http", port: httpPort),
                    url(scheme: "https", port: nil),
                    url(scheme: "http", port: nil),
                    url(scheme: "https", port: httpsPort)]
        } else if scheme == "https" {
            list = [url(scheme: "https", port: nil), url(scheme: "https", port: httpsPort)]
        } else {
            list = [url(scheme: "http", port: nil), url(scheme: "http", port: httpPort)]
        }
        var seen = Set<URL>()
        return list.compactMap { $0 }.filter { seen.insert($0).inserted }
    }

    /// Fetches a URL's body, or nil on any failure. Swappable for tests.
    public typealias Fetch = @Sendable (URL) async -> Data?

    /// The first candidate address where a Jellyfin server answers.
    public static func resolve(_ input: String, fetch: Fetch = fetchPublicInfo) async -> Result<URL, ResolveError> {
        let list = candidates(for: input)
        guard !list.isEmpty else { return .failure(.invalid) }
        for base in list {
            if Task.isCancelled { break }
            let infoURL = base.appendingPathComponent("System/Info/Public")
            if let data = await fetch(infoURL), isJellyfinInfo(data) { return .success(base) }
        }
        let shown = input.trimmingCharacters(in: .whitespacesAndNewlines)
        return .failure(.notFound(shown))
    }

    /// Whether `/System/Info/Public` came from a Jellyfin server (it has the
    /// server's ID and version; the product name says Jellyfin when present).
    static func isJellyfinInfo(_ data: Data) -> Bool {
        struct Info: Decodable { let Id: String?; let Version: String?; let ProductName: String? }
        guard let info = try? JSONDecoder().decode(Info.self, from: data),
              let id = info.Id, !id.isEmpty, info.Version != nil
        else { return false }
        return info.ProductName.map { $0.localizedCaseInsensitiveContains("jellyfin") } ?? true
    }

    /// Short timeout: a wrong port usually fails fast, but an address that
    /// doesn't exist shouldn't keep the user waiting long per guess.
    public static let fetchPublicInfo: Fetch = { url in
        var request = URLRequest(url: url, timeoutInterval: 4)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200
        else { return nil }
        return data
    }
}
