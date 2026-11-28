// MARK: - SearchViewModel — debounced server-side album search

import Foundation
import Observation

/// The chips under the search field: which kinds of result are shown.
public enum SearchFilter: String, CaseIterable, Identifiable, Sendable {
    case all = "All"
    case albums = "Albums"
    case artists = "Artists"
    case playlists = "Playlists"
    public var id: String { rawValue }

    public var showsAlbums: Bool { self == .all || self == .albums }
    public var showsArtists: Bool { self == .all || self == .artists }
    public var showsPlaylists: Bool { self == .all || self == .playlists }
}

/// Fetches album results for a query string with a 400 ms debounce.
/// When the user stops typing for 0.4 s the view model issues a
/// `searchForAlbums` call and updates `results`.
@Observable
@MainActor
public final class SearchViewModel {

    // MARK: Published state

    public private(set) var results: [JellyfinAlbum] = []
    /// Playlists whose name matches.
    public private(set) var playlistResults: [JellyfinPlaylist] = []
    /// Album artists whose name matches (the ones the album search also took albums from).
    public private(set) var artistResults: [JellyfinArtist] = []
    public private(set) var isLoading = false
    public private(set) var hasSearched = false  // true after first non-empty search
    /// Downloaded albums matching the query, used when the server can't be
    /// reached (offline, or the search failed).
    public private(set) var localResults: [LocalAlbum] = []
    /// Downloaded playlists matching the query, used with `localResults`.
    public private(set) var localPlaylistResults: [JellyfinPlaylist] = []
    /// True when the results shown come from downloads only.
    public private(set) var isOfflineResults = false
    /// Recent searches, shown while the box is empty.
    public private(set) var history: [String] = SearchHistory.terms

    // User-controlled
    public var query: String = "" {
        didSet { scheduleSearch() }
    }

    // MARK: Dependencies

    private var pendingWork: DispatchWorkItem?
    /// Supplies the downloaded albums for offline search.
    private let downloadedAlbums: @MainActor () -> [LocalAlbum]
    private let isOnline: @MainActor () -> Bool
    private let search: (String) async throws -> [JellyfinAlbum]
    /// Supplies the playlists listed offline, for offline search.
    private let downloadedPlaylists: @MainActor () -> [JellyfinPlaylist]
    private let searchPlaylists: (String) async throws -> [JellyfinPlaylist]
    private let searchArtists: (String) async -> [JellyfinArtist]

    // MARK: Init

    public convenience init(api: JellyfinAPI, downloadedAlbums: @escaping @MainActor () -> [LocalAlbum] = { [] },
                            downloadedPlaylists: @escaping @MainActor () -> [JellyfinPlaylist] = { [] }) {
        self.init(downloadedAlbums: downloadedAlbums,
                  isOnline: { NetworkMonitor.shared.isConnected },
                  search: { try await AlbumSearch.run(api: api, term: $0) },
                  downloadedPlaylists: downloadedPlaylists,
                  searchPlaylists: { term in
                      try await withCheckedThrowingContinuation { cont in
                          api.searchPlaylists(term: term) { cont.resume(with: $0) }
                      }
                  },
                  searchArtists: { term in
                      await withCheckedContinuation { cont in
                          api.searchArtists(term: term, limit: 3) { cont.resume(returning: (try? $0.get()) ?? []) }
                      }
                  })
    }

    /// `isOnline` and the searches are replaceable for tests.
    init(downloadedAlbums: @escaping @MainActor () -> [LocalAlbum],
         isOnline: @escaping @MainActor () -> Bool,
         search: @escaping (String) async throws -> [JellyfinAlbum],
         downloadedPlaylists: @escaping @MainActor () -> [JellyfinPlaylist] = { [] },
         searchPlaylists: @escaping (String) async throws -> [JellyfinPlaylist] = { _ in [] },
         searchArtists: @escaping (String) async -> [JellyfinArtist] = { _ in [] }) {
        self.downloadedAlbums = downloadedAlbums
        self.isOnline = isOnline
        self.search = search
        self.downloadedPlaylists = downloadedPlaylists
        self.searchPlaylists = searchPlaylists
        self.searchArtists = searchArtists
    }

    /// Back online with only downloads shown: asks the server now.
    public func networkChanged(online: Bool) {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard online, isOfflineResults, !term.isEmpty else { return }
        pendingWork?.cancel()
        performSearch(term: term)
    }

    /// Re-runs a recent search.
    public func useHistory(_ term: String) {
        query = term
    }

    public func clearHistory() {
        SearchHistory.clear()
        history = []
    }

    // MARK: Public

    /// Called automatically when `query` changes.
    private func scheduleSearch() {
        pendingWork?.cancel()
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else {
            results = []
            playlistResults = []
            artistResults = []
            localResults = []
            localPlaylistResults = []
            isOfflineResults = false
            hasSearched = false
            isLoading = false
            history = SearchHistory.terms
            return
        }
        let item = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                self?.performSearch(term: term)
            }
        }
        pendingWork = item
        DispatchQueue.global(qos: .userInitiated)
            .asyncAfter(deadline: .now() + 0.4, execute: item)
    }

    private func performSearch(term: String) {
        guard isOnline() else {
            searchDownloads(term: term)
            return
        }
        isLoading = true
        Task { [weak self, search, searchPlaylists, searchArtists] in
            // All at once. Playlists and artists that can't be searched just
            // don't show; the albums decide whether the server was reached.
            async let playlistsFound = (try? await searchPlaylists(term)) ?? []
            async let artistsFound = await searchArtists(term)
            let result = await Result { try await search(term) }
            let playlists = await playlistsFound
            let artists = await artistsFound
            guard let self, self.query.trimmingCharacters(in: .whitespacesAndNewlines) == term else { return }
            switch result {
            case .success(let albums):
                self.results = albums
                self.playlistResults = playlists
                self.artistResults = artists
                self.localResults = []
                self.localPlaylistResults = []
                self.isOfflineResults = false
                self.hasSearched = true
                SearchHistory.record(term)
            case .failure(let e):
                print("[SearchViewModel] Failed: \(e) — searching downloads instead")
                self.searchDownloads(term: term)
            }
            self.isLoading = false
        }
    }

    private func searchDownloads(term: String) {
        results = []
        playlistResults = []
        artistResults = []
        localResults = LocalSearch.matches(term, in: downloadedAlbums())
        localPlaylistResults = LocalSearch.matches(term, in: downloadedPlaylists())
        isOfflineResults = true
        hasSearched = true
        isLoading = false
    }
}
