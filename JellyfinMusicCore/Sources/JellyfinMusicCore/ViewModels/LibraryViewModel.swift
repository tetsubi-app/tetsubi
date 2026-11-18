// MARK: - LibraryViewModel — browse, sort, and filter the full album library

import Foundation
import Observation

public enum LibrarySortOption: String, CaseIterable, Identifiable, Sendable {
    case name     = "Name"
    case artist   = "Artist"
    case year     = "Year"
    case dateAdded = "Date Added"
    case random   = "Random"
    public var id: String { rawValue }
}

public enum LibraryFilter: String, CaseIterable, Identifiable, Sendable {
    case all      = "All"
    case downloaded = "Downloaded"
    public var id: String { rawValue }
}

public enum LibraryDisplayMode: String, CaseIterable, Identifiable, Sendable {
    case grid = "Grid"
    case list = "List"
    public var id: String { rawValue }
}

/// Fetches the full album library, applies client-side filter + sort,
/// and exposes the resulting `sortedAlbums` for the `LibraryView`.
///
/// Filter/sort are applied *client-side* on the fetched set: re-sorting
/// or re-filtering does not re-query the server. Offline: the downloaded
/// albums only. The tab, sort and view are remembered across launches.
@Observable
@MainActor
public final class LibraryViewModel {

    // MARK: Published state (observed by SwiftUI)

    /// All albums, as fetched from the server. Not re-sorted or filtered.
    public private(set) var albums: [JellyfinAlbum] = []
    /// Filtered + sorted view shown to the user.
    public private(set) var sortedAlbums: [JellyfinAlbum] = []
    public private(set) var isLoading = false
    public private(set) var errorMessage: String?

    /// No network: the downloaded albums only, nothing asked of the server.
    public private(set) var isOffline = false
    /// The downloaded albums (discs merged), newest download first.
    public private(set) var downloadedAlbums: [JellyfinAlbum] = []

    // User-controlled
    public var tab: LibraryTab {
        didSet { defaults.set(tab.rawValue, forKey: Self.tabKey) }
    }
    public var sortOption: LibrarySortOption {
        didSet {
            defaults.set(sortOption.rawValue, forKey: Self.sortKey)
            // Picking Random (even again) deals a fresh shuffle.
            if sortOption == .random { reshuffle() }
            applyFilterAndSort()
        }
    }
    public var filter: LibraryFilter = .all {
        didSet {
            defaults.set(filter.rawValue, forKey: Self.filterKey)
            randomLimit = Self.randomPageSize
            applyFilterAndSort()
        }
    }
    public var displayMode: LibraryDisplayMode {
        didSet { defaults.set(displayMode.rawValue, forKey: Self.viewModeKey) }
    }
    /// Library ▸ Artists: grid (round pictures) or list. Kept apart from the
    /// Albums view, and a grid unless the list was picked.
    public var artistsDisplayMode: LibraryDisplayMode {
        didSet { defaults.set(artistsDisplayMode.rawValue, forKey: Self.artistsViewModeKey) }
    }

    // Remembered choices; values are the raw values,
    // e.g. "Date Added", as Android stores them.
    static let tabKey = "library.tab"
    static let sortKey = "library.sort"
    static let filterKey = "library.filter"
    static let viewModeKey = "library.viewMode"
    static let artistsViewModeKey = "library.artistsViewMode"

    // MARK: Random

    /// Random shows this many albums, then this many more each time the
    /// last one scrolls into view.
    public static let randomPageSize = 24
    /// Shuffled album IDs. Fixed until Random is picked again, so returning
    /// from an album or a library refresh keeps the order.
    private var randomOrder: [String: Int] = [:]
    private var randomLimit = LibraryViewModel.randomPageSize
    /// Random, before the page limit is applied.
    private var randomFull: [JellyfinAlbum] = []

    /// Call as each album appears. Reveals the next page when the last
    /// shown album is reached.
    public func loadMoreIfNeeded(after album: JellyfinAlbum) {
        guard sortOption == .random,
              album.id == sortedAlbums.last?.id,
              sortedAlbums.count < randomFull.count else { return }
        randomLimit += Self.randomPageSize
        sortedAlbums = Array(randomFull.prefix(randomLimit))
    }

    /// Pull to refresh in Random: fetch the library again, then deal a new
    /// shuffle starting from one page. Keeps the old albums if offline.
    public func refreshRandom() async {
        if !isOffline {
            let result = await withCheckedContinuation { continuation in
                api.allMusicAlbums { continuation.resume(returning: $0) }
            }
            if case .success(let fetched) = result { receive(fetched) }
        }
        reshuffle()
        applyFilterAndSort()
    }

    private func reshuffle() {
        randomLimit = Self.randomPageSize
        randomOrder = Dictionary(uniqueKeysWithValues:
            source.map(\.id).shuffled().enumerated().map { ($1, $0) })
    }

    /// Albums in the shuffled order; any new since the shuffle go at the end.
    private func randomised(_ albums: [JellyfinAlbum]) -> [JellyfinAlbum] {
        albums.sorted { (randomOrder[$0.id] ?? .max) < (randomOrder[$1.id] ?? .max) }
    }

    // MARK: Dependencies

    private let api: JellyfinAPI
    private let downloadManager: DownloadManager?
    private let defaults: UserDefaults

    // MARK: Init

    public init(api: JellyfinAPI, downloadManager: DownloadManager? = nil, defaults: UserDefaults = .standard) {
        self.api           = api
        self.downloadManager = downloadManager
        self.defaults      = defaults
        // Unknown saved values fall back to the defaults.
        tab = defaults.string(forKey: Self.tabKey).flatMap(LibraryTab.init(rawValue:)) ?? .albums
        sortOption = defaults.string(forKey: Self.sortKey).flatMap(LibrarySortOption.init(rawValue:)) ?? .name
        displayMode = defaults.string(forKey: Self.viewModeKey).flatMap(LibraryDisplayMode.init(rawValue:)) ?? .grid
        filter = defaults.string(forKey: Self.filterKey).flatMap(LibraryFilter.init(rawValue:)) ?? .all
        artistsDisplayMode = defaults.string(forKey: Self.artistsViewModeKey)
            .flatMap(LibraryDisplayMode.init(rawValue:)) ?? .grid
    }

    // MARK: Public

    /// The network now (call as the Library appears, then on each change).
    /// Online, the library is fetched again (quietly, keeping what's
    /// shown); offline, the downloads are shown and nothing is fetched.
    public func networkChanged(online: Bool) {
        isOffline = !online
        if !online {
            errorMessage = nil
            isLoading = false
        }
        applyFilterAndSort()
        if online { loadLibrary() }
    }

    /// Re-reads the downloaded albums (call when downloads change).
    public func downloadsChanged() {
        let ids = downloadManager.map { DownloadPaths.newestFirst($0.completedAlbumIDs) }
            ?? DownloadPaths.newestFirst(DownloadPaths.downloadedAlbumIDs())
        downloadsChanged(Self.downloadedAlbums(ids: ids))
    }

    func downloadsChanged(_ downloaded: [JellyfinAlbum]) {
        downloadedAlbums = downloaded
        applyFilterAndSort()
    }

    /// Downloaded albums as library entries, from the title and artist
    /// saved with each download; discs of one album merged. Keeps the order.
    nonisolated static func downloadedAlbums(
        ids: [String],
        info: (String) -> (title: String, artist: String)? = DownloadManager.storedAlbumInfo(for:)
    ) -> [JellyfinAlbum] {
        DiscGrouping.merge(ids.map { id in
            let saved = info(id)
            return JellyfinAlbum(
                id: id, name: saved.flatMap { $0.title.isEmpty ? nil : $0.title }, serverId: nil,
                premiereDate: nil, productionYear: nil,
                albumArtists: saved.map { [JellyfinPerson(id: nil, name: $0.artist, serverId: nil)] },
                album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil
            )
        })
    }

    /// Fetches the full catalog. Call on `.onAppear`. Does nothing offline.
    public func loadLibrary() {
        guard !isOffline else { return }
        // Only show the spinner on the first load. Re-running on every appear
        // (e.g. returning from an album) would swap the list for a spinner and
        // reset its scroll position.
        let isFirstLoad = albums.isEmpty
        if isFirstLoad { isLoading = true }
        errorMessage = nil
        api.allMusicAlbums { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                switch result {
                case .success(let albums):
                    self.receive(albums)
                case .failure(let e):
                    if isFirstLoad, !self.isOffline {
                        self.errorMessage = "Failed to load library: \(e.errorDescription ?? "\(e)")"
                    }
                }
                self.isLoading = false
            }
        }
    }

    // MARK: Internal

    /// New albums from the server. Multi-disc albums ("… (Disc 1)",
    /// "… (Disc 2)") show as one.
    func receive(_ fetched: [JellyfinAlbum]) {
        let merged = DiscGrouping.merge(fetched)
        if merged.map(\.id) != albums.map(\.id) {
            albums = merged
            applyFilterAndSort()
        }
    }

    /// What's filtered and sorted: the library, or offline the downloads.
    private var source: [JellyfinAlbum] { isOffline ? downloadedAlbums : albums }

    private func applyFilterAndSort() {
        let filtered: [JellyfinAlbum]
        switch filter {
        case .all:
            filtered = source
        case .downloaded:
            filtered = source.filter { album in
                album.allDiscs.allSatisfy { disc in
                    downloadManager?.isDownloaded(disc.id) ?? DownloadPaths.exists(albumID: disc.id)
                }
            }
        }
        if sortOption == .random {
            if randomOrder.isEmpty { reshuffle() }
            randomFull = randomised(filtered)
            sortedAlbums = Array(randomFull.prefix(randomLimit))
        } else {
            sortedAlbums = sort(filtered)
        }
    }

    /// Sorts a list of albums by the user's chosen option.
    ///
    /// Pure function over `Sendable` inputs, so it's `nonisolated` — callable
    /// from tests (and anywhere) without a main-actor hop.
    nonisolated static func sort(_ albums: [JellyfinAlbum], by option: LibrarySortOption) -> [JellyfinAlbum] {
        switch option {
        case .name:
            return albums.sorted { indexOrder($0.displayName, $1.displayName) }
        case .artist:
            return albums.sorted { indexOrder($0.artistName, $1.artistName) }
        case .year:
            // Newest release first, by full release date (`PremiereDate`),
            // else Jan 1 of the year; no date at all goes last. Ties keep
            // the incoming order (newest added first). Dates are read once
            // per album, not per comparison.
            let keyed = albums.enumerated().map {
                (album: $0.element, index: $0.offset,
                 date: $0.element.releaseDate?.timeIntervalSince1970 ?? -.infinity)
            }
            return keyed.sorted { $0.date != $1.date ? $0.date > $1.date : $0.index < $1.index }
                .map(\.album)
        case .dateAdded:
            // The server returns items sorted by DateAdded desc; keep that order.
            return albums
        case .random:
            return albums.shuffled()
        }
    }

    /// Orders by A-Z index bucket first (# … A–Z … other scripts), then
    /// alphabetically within the bucket, so the index strip matches the list.
    nonisolated private static func indexOrder(_ a: String, _ b: String) -> Bool {
        let (ra, rb) = (AlphaIndex.rank(for: a), AlphaIndex.rank(for: b))
        if ra != rb { return ra < rb }
        return AlphaIndex.folded(a) < AlphaIndex.folded(b)
    }

    /// The key the index strip is built from, or nil when the current sort
    /// isn't alphabetical.
    public var indexKey: ((JellyfinAlbum) -> String)? {
        switch sortOption {
        case .name:   return { $0.displayName }
        case .artist: return { $0.artistName }
        default:      return nil
        }
    }

    private func sort(_ albums: [JellyfinAlbum]) -> [JellyfinAlbum] {
        LibraryViewModel.sort(albums, by: sortOption)
    }

    /// Number of albums currently visible after filtering.
    public var visibleCount: Int { sortedAlbums.count }
}
