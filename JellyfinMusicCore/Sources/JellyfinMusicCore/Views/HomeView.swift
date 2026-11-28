// MARK: - HomeView — main dashboard

import SwiftUI
import Observation

/// The main home screen: a page header (Refresh) over shelves of the app's own
/// cards, each with a section header and "See all". The cards are the grid's, so
/// the card size setting changes them too; on a narrow window the next card
/// peeks in at the edge. Pull down to reload. Tapping a card pushes
/// `AlbumDetailView`.
///
/// Offline: a banner, the downloaded albums and the downloaded playlists.
public struct HomeView: View {

    @State private var viewModel: HomeViewModel?
    private let api: JellyfinAPI?
    private let player: PlayerManager?
    private let downloadManager: DownloadManager?
    /// Opens Library ▸ Playlists (the Playlists shelf's "See all").
    private let onShowPlaylists: (() -> Void)?

    // MARK: Init

    public init(api: JellyfinAPI? = nil, player: PlayerManager? = nil, downloadManager: DownloadManager? = nil,
                onShowPlaylists: (() -> Void)? = nil) {
        self.onShowPlaylists = onShowPlaylists
        self.api = api
        self.player = player
        self.downloadManager = downloadManager
        if let api = api {
            _viewModel = State(initialValue: HomeViewModel(api: api, downloads: downloadManager?.playlists))
        }
    }

    @Environment(\.windowWidthClass) private var windowClass

    private var isOffline: Bool { !NetworkMonitor.shared.isConnected }

    // MARK: Body

    public var body: some View {
        VStack(spacing: 0) {
            PageHeader("Home") {
                if !isOffline {
                    HeaderIconButton("Refresh", systemImage: "arrow.clockwise") { viewModel?.refresh() }
                }
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if isOffline {
                        Banner.offline
                        offlineShelves
                    } else {
                        errorBanner
                        onlineShelves
                    }
                }
                .padding(.bottom, Spacing.xl)
            }
            .refreshable { viewModel?.refresh() }
        }
        .navigationDestination(for: HomeMore.self) { more in
            switch more {
            case .favourites:
                FavouritesPage(api: api).appTheme()
            case .random:
                RandomAlbumsView(api: api).appTheme()
            case .recentlyPlayed:
                RecentlyPlayedView(api: api, initial: viewModel?.recentlyPlayed ?? []).appTheme()
            }
        }
        .task { viewModel?.refresh() }
        // Reload once the connection comes back.
        .onChange(of: NetworkMonitor.shared.isConnected) { _, connected in
            if connected { viewModel?.refresh() }
        }
        // A playlist was made, changed or deleted: its row again.
        .onChange(of: PlaylistEvents.shared.version) { _, _ in
            viewModel?.refreshPlaylists()
        }
        .ownsItsHeader()
    }

    // MARK: - Shelves

    @ViewBuilder
    private var onlineShelves: some View {
        // "On this day" first: the most distinctive shelf. Hidden when nothing
        // was released on today's date.
        if showsOnThisDay {
            SectionHeader("On this day")
            if let entries = viewModel?.onThisDay, !entries.isEmpty {
                CardShelf(items: entries) { entry, width in
                    AlbumCardLink(api: api, album: entry.album, width: width, isDownloaded: isDownloaded(entry.album),
                                  badge: entry.label.isEmpty ? nil : entry.label)
                }
            } else {
                CardShelfPlaceholder()
            }
        }

        SectionHeader("Favourites", seeAllValue: HomeMore.favourites)
        albumShelf(viewModel?.favorites, empty: "No favourite albums yet.")

        SectionHeader("Random albums", seeAllValue: HomeMore.random)
        albumShelf(viewModel?.randomAlbums, empty: "No random albums available.")

        // Only when there's at least one playlist.
        playlistsShelf

        SectionHeader("Recently played", seeAllValue: HomeMore.recentlyPlayed)
        albumShelf(viewModel?.recentlyPlayed, empty: "No recently played music yet.")

        SectionHeader("Recently added")
        albumShelf(viewModel?.recentlyAdded, empty: "No recently added music.")
    }

    @ViewBuilder
    private var offlineShelves: some View {
        SectionHeader("Downloaded")
        let downloaded = downloadedRow
        if downloaded.isEmpty {
            emptyLine("No downloaded albums yet.")
        } else {
            CardShelf(items: downloaded) { album, width in
                AlbumCardLink(api: api, album: album, width: width, isDownloaded: true)
            }
        }
        playlistsShelf
    }

    /// The downloaded albums, newest first, as Home's offline row.
    private var downloadedRow: [JellyfinAlbum] {
        let ids = downloadManager.map { DownloadPaths.newestFirst($0.completedAlbumIDs) }
            ?? DownloadPaths.newestFirst(DownloadPaths.downloadedAlbumIDs())
        return Array(LibraryViewModel.downloadedAlbums(ids: ids).prefix(12))
    }

    @ViewBuilder
    private var playlistsShelf: some View {
        if !shownPlaylists.isEmpty {
            SectionHeader("Playlists", onSeeAll: onShowPlaylists)
            CardShelf(items: shownPlaylists) { playlist, width in
                PlaylistCardLink(api: api, playlist: playlist, width: width)
            }
        }
    }

    /// The server's playlists; offline, only the downloaded ones.
    private var shownPlaylists: [JellyfinPlaylist] {
        guard NetworkMonitor.shared.isConnected else {
            return PlaylistsViewModel.sorted(downloadManager?.playlists.offlinePlaylists ?? [], by: .name)
        }
        return viewModel?.playlists ?? []
    }

    /// The server couldn't be reached — otherwise every shelf would just say it's empty.
    @ViewBuilder
    private var errorBanner: some View {
        if let error = viewModel?.errorMessage, !(viewModel?.isLoading ?? false) {
            Banner("Can't reach your Jellyfin server. \(error)", actionTitle: "Retry") { viewModel?.refresh() }
        }
    }

    /// While Home loads (placeholder cards), or when something matched.
    private var showsOnThisDay: Bool {
        guard let viewModel else { return false }
        return viewModel.isLoading || !viewModel.onThisDay.isEmpty
    }

    @ViewBuilder
    private func albumShelf(_ albums: [JellyfinAlbum]?, empty: String) -> some View {
        let shown = Self.distinct(albums ?? [])
        if !shown.isEmpty {
            CardShelf(items: shown) { album, width in
                AlbumCardLink(api: api, album: album, width: width, isDownloaded: isDownloaded(album))
            }
        } else if viewModel?.isLoading ?? false {
            CardShelfPlaceholder()
        } else {
            emptyLine(empty)
        }
    }

    private func emptyLine(_ message: String) -> some View {
        Text(message)
            .font(TetsubiType.body)
            .foregroundStyle(TetsubiColors.textSecondary)
            .padding(.horizontal, windowClass.pageMargin)
            .padding(.vertical, Spacing.xs)
    }

    private func isDownloaded(_ album: JellyfinAlbum) -> Bool {
        album.allDiscs.allSatisfy { downloadManager?.isDownloaded($0.id) ?? DownloadPaths.exists(albumID: $0.id) }
    }

    /// Each album once: a repeated ID would confuse the row (IDs must be unique).
    static func distinct(_ albums: [JellyfinAlbum]) -> [JellyfinAlbum] {
        var seen = Set<String>()
        return albums.filter { seen.insert($0.id).inserted }
    }
}

// MARK: - View all

/// The full lists Home's "See all" buttons open.
enum HomeMore: Hashable {
    case favourites
    case random
    case recentlyPlayed
}

/// Home ▸ Favourites: Library ▸ Favourites' view with a header (Back and the
/// same Sort, Filter, Grid / List and card size) and its title in the content.
struct FavouritesPage: View {
    let api: JellyfinAPI?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            PageHeader(nil, onBack: { dismiss() }) { LibraryView.favouritesControls(FavouritesOptions.shared) }
            PageTitle("Favourites")
            FavouritesTabView(api: api)
        }
        .ownsItsHeader(isDetail: true)
    }
}

/// A page of random albums (Home ▸ Random albums, See all): what the server
/// dealt, in the app's grid. "Shuffle again" in the header, pull down or
/// shake the device for a fresh set.
struct RandomAlbumsView: View {
    let api: JellyfinAPI?
    @State private var albums: [JellyfinAlbum] = []
    @State private var loaded = false
    @State private var isVisible = false
    @State private var shakes = 0

    @Environment(\.dismiss) private var dismiss

    /// Fetched each time, all of them shown.
    static let fetchCount = 12

    var body: some View {
        VStack(spacing: 0) {
            PageHeader(nil, onBack: { dismiss() }) {
                PageControls(density: true, shuffle: { Task { await load() } })
            }
            PageTitle("Random albums")
            Group {
                if !loaded {
                    LoadingSpinner().frame(maxHeight: .infinity, alignment: .top)
                } else if albums.isEmpty {
                    EmptyState("No albums available").frame(maxHeight: .infinity, alignment: .top)
                } else {
                    AlbumCollection(api: api, albums: albums, footer: Self.footer)
                        .refreshable { await load() }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .ownsItsHeader(isDetail: true)
        .task { if !loaded { await load() } }
        .onAppear { isVisible = true }
        .onDisappear { isVisible = false }
        .onShake {
            guard isVisible else { return }
            shakes += 1
            Task { await load() }
        }
        .sensoryFeedback(.impact(weight: .light), trigger: shakes)
    }

    /// Where the device can be shaken.
    private static var footer: String? {
        #if os(iOS)
        "Shake it"
        #else
        nil
        #endif
    }

    private func load() async {
        guard let api else { return }
        let result: Result<[JellyfinAlbum], JellyfinError> = await withCheckedContinuation { c in
            api.randomAlbums(limit: Self.fetchCount) { c.resume(returning: $0) }
        }
        if case .success(let items) = result { albums = DiscGrouping.merge(items) }
        loaded = true
    }
}
