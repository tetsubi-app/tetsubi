// MARK: - HomeViewModel — fetches and manages home screen data

import Foundation
import Observation

/// Holds the four home screen data sources and exposes async/await
/// or completion-based refresh. Designed for `@Observable` usage
/// (iOS 17+ / macOS 14+).
@Observable
public final class HomeViewModel {

    // MARK: Published data

    public private(set) var randomAlbums: [JellyfinAlbum] = []
    public private(set) var recentlyAdded: [JellyfinAlbum] = []
    public private(set) var recentlyPlayed: [JellyfinAlbum] = []
    public private(set) var favorites: [JellyfinAlbum] = []
    /// The server's playlists, by name. Home shows their row only when
    /// there is at least one.
    public private(set) var playlists: [JellyfinPlaylist] = []
    public private(set) var onThisDay: [OnThisDayEntry] = []

    public private(set) var isLoading = false
    public private(set) var errorMessage: String?

    // MARK: Depends on

    private let api: JellyfinAPI
    /// Downloaded playlists, compared with the server on each refresh.
    private let downloads: PlaylistDownloads?

    // MARK: Init

    public init(api: JellyfinAPI, downloads: PlaylistDownloads? = nil) {
        self.api = api
        self.downloads = downloads
    }

    // MARK: - Fetch

    /// Fetch all four home sections. Used on app launch and pull-to-refresh.
    public func refresh() {
        isLoading = true
        errorMessage = nil

        let groups = DispatchGroup()

        // 1. Random albums
        groups.enter()
        api.randomAlbums(limit: 12) { [weak self] result in
            if let self {
                switch result {
                case .success(let albums):
                    let merged = DiscGrouping.merge(albums)
                    DispatchQueue.main.async { self.randomAlbums = merged }
                case .failure(let e):
                    print("[HomeVM] randomAlbums failed: \(e.errorDescription ?? "\(e)")")
                    DispatchQueue.main.async { self.errorMessage = e.errorDescription }
                }
            }
            groups.leave()
        }

        // 2. Recently added — 12 most recently added albums
        groups.enter()
        api.recentItems(limit: 12, itemTypes: "MusicAlbum") { [weak self] result in
            if let self {
                switch result {
                case .success(let items):
                    let merged = DiscGrouping.merge(items)
                    DispatchQueue.main.async { self.recentlyAdded = merged }
                case .failure(let e):
                    print("[HomeVM] recentItems failed: \(e.errorDescription ?? "\(e)")")
                }
            }
            groups.leave()
        }

        // 3. Recently played — distinct albums grouped from DatePlayed, most
        //    recent first (includes tracks played to completion).
        groups.enter()
        api.recentlyPlayedAlbums(limit: 12) { [weak self] result in
            if let self {
                switch result {
                case .success(let items):
                    let merged = DiscGrouping.merge(items)
                    DispatchQueue.main.async { self.recentlyPlayed = merged }
                case .failure(let e):
                    print("[HomeVM] recentlyPlayedAlbums failed: \(e.errorDescription ?? "\(e)")")
                }
            }
            groups.leave()
        }

        // Favourites — most recently favourited first.
        groups.enter()
        api.favoriteAlbums { [weak self] result in
            if let self {
                switch result {
                case .success(let items):
                    let ordered = DiscGrouping.merge(FavoritesStore.ordered(items))
                    DispatchQueue.main.async { self.favorites = ordered }
                case .failure(let e):
                    print("[HomeVM] favoriteAlbums failed: \(e.errorDescription ?? "\(e)")")
                }
            }
            groups.leave()
        }

        // Playlists — by name.
        groups.enter()
        api.playlists { [weak self] result in
            if let self {
                switch result {
                case .success(let items):
                    let sorted = PlaylistsViewModel.sorted(items, by: .name)
                    DispatchQueue.main.async {
                        self.playlists = sorted
                        // Downloaded playlists follow the server.
                        MainActor.assumeIsolated { self.downloads?.sync(with: items) }
                    }
                case .failure(let e):
                    print("[HomeVM] playlists failed: \(e.errorDescription ?? "\(e)")")
                }
            }
            groups.leave()
        }

        // 4. On This Day — the server can't match "this month and day in any
        //    year", so: every album's release date only (a small answer),
        //    matched here; then the details of just the matches, at most 12
        //    shown. Discs of one album share a date; they're shown once.
        groups.enter()
        api.albumReleaseDates { [weak self] result in
            guard let self else { groups.leave(); return }
            guard case .success(let dated) = result else {
                if case .failure(let e) = result {
                    print("[HomeVM] albumReleaseDates failed: \(e.errorDescription ?? "\(e)")")
                }
                groups.leave()
                return
            }
            // Room for several discs of one album before the cap.
            let ids = DateFilterHelper.onThisDayMatches(dated)
                .prefix(DateFilterHelper.onThisDayLimit * 4)
                .map(\.album.id)
            self.api.albums(ids: Array(ids)) { [weak self] details in
                let full = (try? details.get()) ?? []
                let entries = DateFilterHelper.onThisDayMatches(DiscGrouping.merge(full))
                    .prefix(DateFilterHelper.onThisDayLimit)
                    .map { match in
                        OnThisDayEntry(
                            album: match.album,
                            yearsAgo: match.yearsAgo,
                            label: DateFilterHelper.anniversaryLabel(yearsAgo: match.yearsAgo),
                            releaseDate: match.releaseDate
                        )
                    }
                DispatchQueue.main.async { self?.onThisDay = Array(entries) }
                groups.leave()
            }
        }

        groups.notify(queue: .main) { [weak self] in
            self?.isLoading = false
        }
    }

    /// Fetches the full track list for a given album.
    /// Returns the tracks array via completion on main queue.
    public func fetchTracks(
        for album: JellyfinAlbum,
        completion: @escaping (Result<[JellyfinTrack], JellyfinError>) -> Void
    ) {
        api.tracksForAlbum(album.id) { [weak self] result in
            DispatchQueue.main.async {
                self?.refreshTracksFinished()
                completion(result)
            }
        }
    }

    private func refreshTracksFinished() {
        // No-op for now — will be wired when track list is on screen
    }

    /// Just the playlists again, after one was made, changed or deleted.
    public func refreshPlaylists() {
        api.playlists { [weak self] result in
            guard let self, case .success(let items) = result else { return }
            let sorted = PlaylistsViewModel.sorted(items, by: .name)
            DispatchQueue.main.async { self.playlists = sorted }
        }
    }
}

// MARK: - OnThisDayEntry

/// A single "anniversary" entry — an album released on today's date in a past year.
/// `album` carries the full `JellyfinAlbum` so the card can navigate to its
/// detail view; `albumTitle`/`albumArtist` are computed for display.
public struct OnThisDayEntry: Identifiable, Sendable {
    public let id = UUID()
    public let album: JellyfinAlbum
    public let yearsAgo: Int
    public let label: String       // "30 years ago"
    public let releaseDate: Date

    public var albumTitle: String { album.displayName }
    public var albumArtist: String { album.artistName }

    public init(album: JellyfinAlbum, yearsAgo: Int, label: String, releaseDate: Date) {
        self.album = album
        self.yearsAgo = yearsAgo
        self.label = label
        self.releaseDate = releaseDate
    }
}
