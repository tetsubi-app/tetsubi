// MARK: - FavouritesOptions — Library ▸ Favourites' view, sort and filter

import Foundation
import Observation

/// How Library ▸ Favourites is ordered. Date Favourited is the order
/// `FavoritesStore.ordered` gives (most recently favourited first); the
/// rest are the Albums tab's sorts.
public enum FavouritesSortOption: String, CaseIterable, Identifiable, Sendable {
    case dateFavourited = "Date Favourited"
    case name   = "Name"
    case artist = "Artist"
    case year   = "Year"
    public var id: String { rawValue }

    /// The Albums sort this one is, or nil for Date Favourited.
    var librarySort: LibrarySortOption? {
        switch self {
        case .dateFavourited: return nil
        case .name:           return .name
        case .artist:         return .artist
        case .year:           return .year
        }
    }
}

/// Library ▸ Favourites' View, Sort and Filter, remembered across launches
/// with the Library's other choices. One for the app, so Home ▸ Favourites
/// (the same view) follows the choices made in the Library.
@MainActor
@Observable
public final class FavouritesOptions {
    public static let shared = FavouritesOptions()

    // Same store and naming as the Library's sort and view; values are the
    // raw values, e.g. "Date Favourited", as Android stores them.
    static let viewModeKey = "library.favouritesViewMode"
    static let sortKey = "library.favouritesSort"
    static let filterKey = "library.favouritesFilter"

    public var displayMode: LibraryDisplayMode {
        didSet { defaults.set(displayMode.rawValue, forKey: Self.viewModeKey) }
    }
    public var sortOption: FavouritesSortOption {
        didSet { defaults.set(sortOption.rawValue, forKey: Self.sortKey) }
    }
    public var filter: LibraryFilter {
        didSet { defaults.set(filter.rawValue, forKey: Self.filterKey) }
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        // Unknown saved values fall back to the defaults.
        displayMode = defaults.string(forKey: Self.viewModeKey).flatMap(LibraryDisplayMode.init(rawValue:)) ?? .grid
        sortOption = defaults.string(forKey: Self.sortKey).flatMap(FavouritesSortOption.init(rawValue:)) ?? .dateFavourited
        filter = defaults.string(forKey: Self.filterKey).flatMap(LibraryFilter.init(rawValue:)) ?? .all
    }

    /// The key the A–Z strip is built from, or nil when the sort isn't
    /// alphabetical (as the Albums tab).
    public var indexKey: ((JellyfinAlbum) -> String)? {
        switch sortOption {
        case .name:   return { $0.displayName }
        case .artist: return { $0.artistName }
        default:      return nil
        }
    }

    /// What the tab shows, from the favourites in favourited order:
    /// offline the downloaded ones only (whatever the filter), Downloaded
    /// likewise, then sorted.
    nonisolated static func arrange(_ favourites: [JellyfinAlbum], sort: FavouritesSortOption,
                                    filter: LibraryFilter, offline: Bool,
                                    isDownloaded: (JellyfinAlbum) -> Bool) -> [JellyfinAlbum] {
        let downloadedOnly = offline || filter == .downloaded
        let shown = downloadedOnly ? favourites.filter(isDownloaded) : favourites
        return Self.sort(shown, by: sort)
    }

    /// Date Favourited keeps the incoming order; the others are exactly the
    /// Albums tab's sorts.
    nonisolated static func sort(_ albums: [JellyfinAlbum], by option: FavouritesSortOption) -> [JellyfinAlbum] {
        guard let library = option.librarySort else { return albums }
        return LibraryViewModel.sort(albums, by: library)
    }
}
