// MARK: - LibraryView — browse the full album library

import SwiftUI

/// The Library: a page header with the Sort, Grid / List, card size and
/// Filter controls (one "Sort & view" button on a narrow window), then
/// Albums / Artists / Favourites / Playlists as tabs, then the grid or list.
///
///   - Albums: sort (Name, Artist, Year, Date Added, Random), filter (All,
///     Downloaded), the A to Z strip when sorted by name or artist; Random
///     shows 24 at a time, pull down or shake for a new shuffle
///   - "Downloaded" tick on locally-downloaded albums
///
/// Each album is navigable to `AlbumDetailView` via a
/// `navigationDestination(for: JellyfinAlbum.self)` modifier.
public struct LibraryView: View {

    // MARK: Inputs

    private let api: JellyfinAPI?
    private let player: PlayerManager?
    private let downloadManager: DownloadManager?

    @State private var viewModel: LibraryViewModel?
    @State private var playlistsModel: PlaylistsViewModel?
    /// Set from outside (Home's Playlists ›) to show a tab; cleared once shown.
    @Binding private var tabRequest: LibraryTab?

    /// The tab shown (remembered by the view model).
    private var tab: LibraryTab { viewModel?.tab ?? .albums }

    // MARK: Init

    public init(
        api: JellyfinAPI? = nil,
        player: PlayerManager? = nil,
        downloadManager: DownloadManager? = nil,
        tabRequest: Binding<LibraryTab?> = .constant(nil)
    ) {
        _tabRequest        = tabRequest
        self.api           = api
        self.player        = player
        self.downloadManager = downloadManager
        if let api = api {
            _viewModel = State(initialValue: LibraryViewModel(api: api, downloadManager: downloadManager))
            _playlistsModel = State(initialValue: PlaylistsViewModel(api: api, downloads: downloadManager?.playlists))
        }
    }

    // MARK: Body

    public var body: some View {
        VStack(spacing: 0) {
            PageHeader("Library") { controls }
            TetsubiTabs(LibraryTab.allCases.map(\.rawValue),
                        selection: Binding(get: { tab.index },
                                           set: { viewModel?.tab = LibraryTab.allCases[$0] }))
            if !NetworkMonitor.shared.isConnected { Banner.offline }
            Group {
                if let model = viewModel {
                    switch tab {
                    case .albums:     content(model)
                    case .artists:    ArtistsTabView(api: api, mode: model.artistsDisplayMode)
                    case .favourites: FavouritesTabView(api: api)
                    case .playlists:
                        if let playlistsModel { PlaylistsTabView(api: api, model: playlistsModel) }
                    }
                } else {
                    Text("No server connected.")
                        .foregroundStyle(TetsubiColors.textSecondary)
                        .padding()
                }
            }
            // Fill the viewport (content is small while loading) so the tabs
            // stay at the top instead of floating mid-screen.
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        // Offline: the downloads only. Reload once the connection comes back.
        .onChange(of: NetworkMonitor.shared.isConnected) { _, connected in
            viewModel?.networkChanged(online: connected)
        }
        .onChange(of: downloadManager?.completedAlbumIDs) { _, _ in
            viewModel?.downloadsChanged()
        }
        .onChange(of: tabRequest, initial: true) { _, _ in showRequestedTab() }
        .ownsItsHeader()
        .task {
            viewModel?.downloadsChanged()
            viewModel?.networkChanged(online: NetworkMonitor.shared.isConnected)
        }
    }

    private func showRequestedTab() {
        guard let requested = tabRequest else { return }
        viewModel?.tab = requested
        tabRequest = nil
    }

    // MARK: - Header controls

    /// The open tab's Sort, Filter, Grid / List and card size.
    @ViewBuilder
    private var controls: some View {
        if let model = viewModel {
            switch tab {
            case .albums:
                PageControls(
                    sort: controlMenu("Sort", options: LibrarySortOption.allCases, selected: model.sortOption,
                                      label: \.rawValue) { model.sortOption = $0 },
                    filter: controlMenu("Filter", options: LibraryFilter.allCases, selected: model.filter,
                                        label: \.rawValue) { model.filter = $0 },
                    view: model.displayMode, onView: { model.displayMode = $0 },
                    density: model.displayMode == .grid,
                    shuffle: model.sortOption == .random ? { model.sortOption = .random } : nil)
            case .artists:
                PageControls(view: model.artistsDisplayMode, onView: { model.artistsDisplayMode = $0 },
                             density: model.artistsDisplayMode == .grid)
            case .favourites:
                LibraryView.favouritesControls(FavouritesOptions.shared)
            case .playlists:
                if let playlists = playlistsModel {
                    PageControls(
                        sort: controlMenu("Sort", options: PlaylistSortOption.allCases, selected: playlists.sortOption,
                                          label: \.rawValue) { playlists.sortOption = $0 },
                        view: playlists.displayMode, onView: { playlists.displayMode = $0 },
                        density: playlists.displayMode == .grid)
                }
            }
        }
    }

    // MARK: - Albums tab

    @ViewBuilder
    private func content(_ model: LibraryViewModel) -> some View {
        if model.isLoading {
            LoadingSpinner().frame(maxHeight: .infinity, alignment: .top)
        } else if let error = model.errorMessage {
            EmptyState("Couldn't load library", message: error, systemImage: "exclamationmark.triangle",
                       actionTitle: "Retry") { model.loadLibrary() }
                .frame(maxHeight: .infinity, alignment: .top)
        } else if model.visibleCount == 0 {
            emptyState(model: model)
        } else {
            AlbumCollection(api: api, albums: model.sortedAlbums, mode: model.displayMode,
                            indexKey: model.indexKey, isDownloaded: isDownloaded,
                            onAppear: { model.loadMoreIfNeeded(after: $0) },
                            resetTrigger: model.sortOption.rawValue)
                .modifier(PullToShuffle(model: model))
        }
    }

    /// Random only: pull down for a fresh shuffle (and any new albums), or
    /// shake the device for a fresh shuffle (as "Shuffle again"; not shown
    /// anywhere). Shakes only count while the Library is on screen.
    private struct PullToShuffle: ViewModifier {
        let model: LibraryViewModel
        @State private var isVisible = false
        @State private var shakes = 0

        func body(content: Content) -> some View {
            Group {
                if model.sortOption == .random {
                    content.refreshable { await model.refreshRandom() }
                } else {
                    content
                }
            }
            .onAppear { isVisible = true }
            .onDisappear { isVisible = false }
            .onShake {
                guard isVisible, model.sortOption == .random else { return }
                model.sortOption = .random   // re-picking deals a new shuffle
                shakes += 1
            }
            .sensoryFeedback(.impact(weight: .light), trigger: shakes)
        }
    }

    // MARK: - States

    @ViewBuilder
    private func emptyState(model: LibraryViewModel) -> some View {
        let downloadedOnly = model.filter == .downloaded || model.isOffline
        EmptyState(downloadedOnly ? "No downloaded albums yet" : "Library is empty",
                   message: downloadedOnly
                       ? "Download an album from its detail page to play it offline."
                       : "Connect to a server with music to get started.",
                   systemImage: "music.note.list",
                   actionTitle: model.filter == .downloaded && !model.isOffline ? "Show all albums" : nil) {
            model.filter = .all
        }
        .frame(maxHeight: .infinity, alignment: .top)
    }

    // MARK: - Helpers

    /// For a multi-disc album, every disc must be downloaded.
    private func isDownloaded(_ album: JellyfinAlbum) -> Bool {
        album.allDiscs.allSatisfy { disc in
            downloadManager?.isDownloaded(disc.id) ?? DownloadPaths.exists(albumID: disc.id)
        }
    }

    static func symbol(for mode: LibraryDisplayMode) -> String {
        mode == .grid ? "square.grid.2x2" : "list.bullet"
    }
}
