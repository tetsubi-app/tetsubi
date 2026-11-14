// MARK: - PlaylistsViewModel — the Library's Playlists tab

import Foundation
import Observation

public enum PlaylistSortOption: String, CaseIterable, Identifiable, Sendable {
    case name      = "Name"
    case dateAdded = "Date Added"
    public var id: String { rawValue }
}

/// Fetches the server's playlists and sorts them for Library ▸ Playlists.
/// The sort and view are remembered across launches. Offline, nothing is
/// asked of the server: the downloaded playlists are listed.
@Observable
@MainActor
public final class PlaylistsViewModel {

    // MARK: Published state

    /// The playlists in the chosen order.
    public private(set) var playlists: [JellyfinPlaylist] = []
    public private(set) var isLoading = false
    /// The list has been fetched at least once.
    public private(set) var hasLoaded = false
    public private(set) var errorMessage: String?
    /// Last loaded offline: only downloaded playlists are listed.
    public private(set) var isOffline = false

    public var sortOption: PlaylistSortOption {
        didSet {
            defaults.set(sortOption.rawValue, forKey: Self.sortKey)
            playlists = Self.sorted(playlists, by: sortOption)
        }
    }

    public var displayMode: LibraryDisplayMode {
        didSet { defaults.set(displayMode.rawValue, forKey: Self.viewModeKey) }
    }

    static let sortKey = "playlists.sort"
    static let viewModeKey = "playlists.viewMode"

    // MARK: Dependencies

    private let api: JellyfinAPI
    private let defaults: UserDefaults
    private let downloads: PlaylistDownloads?

    // MARK: Init

    public init(api: JellyfinAPI, defaults: UserDefaults = .standard, downloads: PlaylistDownloads? = nil) {
        self.api = api
        self.defaults = defaults
        self.downloads = downloads
        // Unknown saved values fall back to the defaults.
        sortOption = defaults.string(forKey: Self.sortKey).flatMap(PlaylistSortOption.init(rawValue:)) ?? .name
        displayMode = defaults.string(forKey: Self.viewModeKey).flatMap(LibraryDisplayMode.init(rawValue:)) ?? .grid
    }

    // MARK: - Fetch

    /// Downloaded playlists the server lists differently from what's
    /// saved (name, song count or length) are brought in step. Each is
    /// compared by its real rows, read again from the server, so a
    /// listing that lags behind an edit can't remove a needed file.
    func syncDownloads(with listed: [JellyfinPlaylist]) {
        guard let downloads else { return }
        let changed = listed.filter { downloads.manifests[$0.id]?.differs(from: $0) == true }
        if !changed.isEmpty { downloads.sync(with: changed) }
    }

    /// Each time the tab shows, and on Retry. Offline, the playlists that
    /// have finished downloading at least once are listed.
    public func load(online: Bool) {
        isOffline = !online
        guard online else {
            isLoading = false
            errorMessage = nil
            playlists = Self.sorted(downloads?.offlinePlaylists ?? [], by: sortOption)
            hasLoaded = true
            return
        }
        isLoading = true
        errorMessage = nil
        api.playlists { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                self.isLoading = false
                switch result {
                case .success(let items):
                    self.playlists = Self.sorted(items, by: self.sortOption)
                    self.hasLoaded = true
                    self.syncDownloads(with: items)
                case .failure(let error):
                    self.errorMessage = error.errorDescription ?? "\(error)"
                }
            }
        }
    }

    // MARK: - Sorting

    /// Name: A–Z, ignoring case and accents. Date Added: newest first,
    /// undated ones last. Ties fall back to the name, then the ID.
    nonisolated static func sorted(_ playlists: [JellyfinPlaylist], by option: PlaylistSortOption) -> [JellyfinPlaylist] {
        func byName(_ a: JellyfinPlaylist, _ b: JellyfinPlaylist) -> Bool {
            let order = a.name.compare(b.name, options: [.caseInsensitive, .diacriticInsensitive, .numeric])
            return order == .orderedSame ? a.id < b.id : order == .orderedAscending
        }
        switch option {
        case .name:
            return playlists.sorted(by: byName)
        case .dateAdded:
            return playlists.sorted { a, b in
                switch (a.dateCreated, b.dateCreated) {
                case let (x?, y?) where x != y: return x > y
                case (.some, nil): return true
                case (nil, .some): return false
                default: return byName(a, b)
                }
            }
        }
    }
}
