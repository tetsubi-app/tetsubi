// MARK: - Search history and offline (downloaded-only) search

import Foundation

/// Recent search terms, most recent first (UserDefaults, max 10).
public enum SearchHistory {

    /// Used by tests so they never touch the app's real defaults.
    nonisolated(unsafe) static var defaults: UserDefaults = .standard
    private static let key = "search.history"
    static let limit = 10

    public static var terms: [String] {
        defaults.stringArray(forKey: key) ?? []
    }

    /// Adds `term` to the top. Typing refines a search ("mel" → "mellon"),
    /// so a newest entry that the new term extends is replaced, not kept.
    public static func record(_ term: String) {
        let term = term.trimmingCharacters(in: .whitespacesAndNewlines)
        guard term.count >= 2 else { return }
        var list = terms.filter { $0.caseInsensitiveCompare(term) != .orderedSame }
        if let newest = list.first,
           term.lowercased().hasPrefix(newest.lowercased()) || newest.lowercased().hasPrefix(term.lowercased()) {
            list.removeFirst()
        }
        list.insert(term, at: 0)
        defaults.set(Array(list.prefix(limit)), forKey: key)
    }

    public static func clear() {
        defaults.removeObject(forKey: key)
    }
}

/// A downloaded album, as known without the server.
public struct LocalAlbum: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let artist: String
    /// The downloaded tracks' titles.
    public let trackTitles: [String]

    public init(id: String, title: String, artist: String, trackTitles: [String] = []) {
        self.id = id
        self.title = title
        self.artist = artist
        self.trackTitles = trackTitles
    }
}

/// Matches downloaded albums by title / artist / track title, for
/// searching while offline.
public enum LocalSearch {
    /// Every word of `query` must appear in the title, artist or a track
    /// title (case- and accent-insensitive). Results keep the input order.
    public static func matches(_ query: String, in albums: [LocalAlbum]) -> [LocalAlbum] {
        let words = fold(query).split(whereSeparator: \.isWhitespace).map(String.init)
        guard !words.isEmpty else { return [] }
        return albums.filter { album in
            let haystack = fold(([album.title, album.artist] + album.trackTitles).joined(separator: " "))
            return words.allSatisfy { haystack.contains($0) }
        }
    }

    /// Downloaded playlists whose name has every word of `query`
    /// (case- and accent-insensitive). Results keep the input order.
    public static func matches(_ query: String, in playlists: [JellyfinPlaylist]) -> [JellyfinPlaylist] {
        let words = fold(query).split(whereSeparator: \.isWhitespace).map(String.init)
        guard !words.isEmpty else { return [] }
        return playlists.filter { playlist in
            let name = fold(playlist.name)
            return words.allSatisfy { name.contains($0) }
        }
    }

    private static func fold(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }
}
