import Foundation

/// Remembers when albums were favourited from this app, so the Home
/// "Favourites" row can be ordered most-recent-first. Jellyfin does not
/// expose a favourited date; favourites made elsewhere keep server order
/// and follow the ones recorded here.
public enum FavoritesStore {
    private static let key = "favorites.dates"

    public static func record(_ id: String, isFavorite: Bool, defaults: UserDefaults = .standard) {
        var dates = defaults.dictionary(forKey: key) as? [String: Double] ?? [:]
        if isFavorite {
            dates[id] = Date().timeIntervalSince1970
        } else {
            dates[id] = nil
        }
        defaults.set(dates, forKey: key)
    }

    /// Stable sort: albums favourited in this app first (newest first),
    /// then the rest in their incoming order.
    public static func ordered(_ albums: [JellyfinAlbum], defaults: UserDefaults = .standard) -> [JellyfinAlbum] {
        let dates = defaults.dictionary(forKey: key) as? [String: Double] ?? [:]
        let indexed = albums.enumerated().sorted { a, b in
            switch (dates[a.element.id], dates[b.element.id]) {
            case let (x?, y?): return x != y ? x > y : a.offset < b.offset
            case (_?, nil): return true
            case (nil, _?): return false
            case (nil, nil): return a.offset < b.offset
            }
        }
        return indexed.map(\.element)
    }
}
