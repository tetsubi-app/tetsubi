// MARK: - LibraryView — browse the full album library

import SwiftUI

/// Shows the user's full album library with:
///   - Grid / List toggle
///   - Sort menu (Name, Artist, Year, Date Added, Random)
///   - Filter (All, Downloaded)
///   - Album art, title, artist for each album
///   - "Downloaded" badge on locally-downloaded albums
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
        Group {
            if let model = viewModel {
                switch tab {
                case .albums:     content(model)
                case .artists:    ArtistsTabView(api: api)
                case .favourites: FavouritesTabView(api: api)
                case .playlists:
                    if let playlistsModel { PlaylistsTabView(api: api, model: playlistsModel) }
                }
            } else {
                Text("No server connected.")
                    .foregroundStyle(.secondary)
                    .padding()
            }
        }
        // Fill the viewport (content is small while loading) so the tab bar
        // stays at the top instead of floating mid-screen.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        // Pinned above the scrolling content.
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(spacing: 0) {
                LibraryTabBar(selection: Binding(get: { tab }, set: { viewModel?.tab = $0 })) {
                    if tab == .albums, let model = viewModel {
                        optionsMenu(model: model)
                    } else if tab == .playlists, let playlistsModel {
                        playlistsOptionsMenu(model: playlistsModel)
                    }
                }
                if !NetworkMonitor.shared.isConnected { StatusBanner.offline }
            }
        }
        // Offline: the downloads only. Reload once the connection comes back.
        .onChange(of: NetworkMonitor.shared.isConnected) { _, connected in
            viewModel?.networkChanged(online: connected)
        }
        .onChange(of: downloadManager?.completedAlbumIDs) { _, _ in
            viewModel?.downloadsChanged()
        }
        .onChange(of: tabRequest, initial: true) { _, _ in showRequestedTab() }
        .untitledTab()
        // No top bar at all: the Albums / Artists / Favourites / Playlists
        // row is the header, so it sits in the same place on every tab.
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        #endif
        .task {
            viewModel?.downloadsChanged()
            viewModel?.networkChanged(online: NetworkMonitor.shared.isConnected)
        }
        .navigationDestination(for: JellyfinArtist.self) { artist in
            ArtistAlbumsView(api: api, artist: artist)
                .appTheme()
        }
    }

    private func showRequestedTab() {
        guard let requested = tabRequest else { return }
        viewModel?.tab = requested
        tabRequest = nil
    }

    // MARK: - Content

    @ViewBuilder
    private func content(_ model: LibraryViewModel) -> some View {
        Group {
            if model.isLoading {
                ProgressView().padding()
            } else if let error = model.errorMessage {
                errorState(error)
            } else if model.visibleCount == 0 {
                emptyState(model: model)
            } else {
                indexed(model: model) { proxy in
                    switch model.displayMode {
                    case .grid: gridView(model: model, proxy: proxy)
                    case .list: listView(model: model, proxy: proxy)
                    }
                }
                .modifier(PullToShuffle(model: model))
            }
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

    // MARK: - A-Z index

    /// Wraps the grid/list with a scroll proxy and, for alphabetical sorts,
    /// the A–Z strip on the right.
    private func indexed<Content: View>(
        model: LibraryViewModel,
        @ViewBuilder content: @escaping (ScrollViewProxy) -> Content
    ) -> some View {
        ScrollViewReader { proxy in
            let key = model.indexKey
            let firstByBucket = firstAlbumIDs(model.sortedAlbums, key: key)
            // The strip floats over the trailing edge; the content reserves its
            // width so the grid keeps the full remaining width and padding.
            content(proxy)
                .padding(.trailing, key != nil ? AlphaIndexBar.width : 0)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .overlay(alignment: .trailing) {
                    if key != nil {
                        AlphaIndexBar(available: Set(firstByBucket.keys)) { label in
                            // Jump to the letter, or the next one that has albums.
                            let start = AlphaIndex.labels.firstIndex(of: label) ?? 0
                            let target = AlphaIndex.labels[start...].first { firstByBucket[$0] != nil }
                                ?? AlphaIndex.labels.last { firstByBucket[$0] != nil }
                            if let target, let id = firstByBucket[target] {
                                proxy.scrollTo(id, anchor: .top)
                            }
                        }
                    }
                }
        }
    }

    private func firstAlbumIDs(_ albums: [JellyfinAlbum], key: ((JellyfinAlbum) -> String)?) -> [String: String] {
        guard let key else { return [:] }
        var result: [String: String] = [:]
        for album in albums {
            let bucket = AlphaIndex.bucket(for: key(album))
            if result[bucket] == nil { result[bucket] = album.id }
        }
        return result
    }

    // MARK: - Grid

    /// Album art size in the grid at scale 1. Fixed: columns adapt around it.
    static let gridArtSize: CGFloat = 120
    /// The grid's art size now: grows with a big Mac window (`UIScale`).
    static var scaledGridArtSize: CGFloat { gridArtSize.scaled }
    static let gridEdgePadding: CGFloat = 4

    /// Column count and gap for the available width. Prefers a comfortable
    /// 12pt gap; when that only allows two columns, squeezes in a third if
    /// three full-size covers fit with at least a 2pt gap (e.g. 3-up on an
    /// iPhone even with the A–Z strip showing). `scale` (the Mac's `UIScale`
    /// factor) grows the covers and gaps, so a bigger window gets bigger
    /// cells rather than only more columns.
    nonisolated static func gridLayout(width: CGFloat, scale: CGFloat = 1) -> (columns: Int, spacing: CGFloat) {
        let art = gridArtSize * scale
        let gap = 12 * scale
        let comfortable = max(1, Int((width + gap) / (art + gap)))
        if comfortable >= 3 { return (comfortable, gap) }
        if 3 * art + 2 * 2 <= width {
            return (3, min(gap, (width - 3 * art) / 2))
        }
        return (max(1, comfortable), gap)
    }

    private func gridView(model: LibraryViewModel, proxy: ScrollViewProxy) -> some View {
        // The A–Z strip already leaves room on the right, so no trailing
        // padding when it's showing — that's what lets 3 covers fit on a phone.
        let trailing: CGFloat = model.indexKey != nil ? 0 : Self.gridEdgePadding
        return GeometryReader { geo in
            let layout = Self.gridLayout(width: geo.size.width - Self.gridEdgePadding - trailing,
                                         scale: UIScale.shared.factor)
            ScrollView {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: layout.spacing, alignment: .top),
                                     count: layout.columns),
                      spacing: 16.scaled) {
                ForEach(model.sortedAlbums) { album in
                    NavigationLink(value: album) {
                        VStack(spacing: 4.scaled) {
                            AlbumArtView(
                                request: api?.imageURLRequest(for: album.id, size: 300),
                                size: Self.scaledGridArtSize, cornerRadius: 8.scaled
                            )
                            .overlay(alignment: .bottomTrailing) {
                                if isDownloaded(album) {
                                    Image(systemName: "checkmark.circle.fill")
                                        .font(.plex(.caption))
                                        .foregroundStyle(.green)
                                        .padding(4)
                                }
                            }
                            Text(album.displayName)
                                .font(.plex(.caption).bold())
                                .lineLimit(2)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity, alignment: .center)
                            Text(album.artistName)
                                .font(.plex(.caption2))
                                .foregroundStyle(Color.albumArtistText)
                                .lineLimit(1)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity, alignment: .center)
                        }
                    }
                    .buttonStyle(.plain)
                    .id(album.id)
                    .onAppear { model.loadMoreIfNeeded(after: album) }
                }
            }
            .padding(.leading, Self.gridEdgePadding)
            .padding(.trailing, trailing)
            .padding(.vertical)
            }
        }
    }

    // MARK: - List

    private func listView(model: LibraryViewModel, proxy: ScrollViewProxy) -> some View {
        List(model.sortedAlbums) { album in
            NavigationLink(value: album) {
                HStack(spacing: 12.scaled) {
                    AlbumArtView(
                        request: api?.imageURLRequest(for: album.id, size: 100),
                        size: 60.scaled, cornerRadius: 4.scaled
                    )
                    VStack(alignment: .leading, spacing: 2) {
                        Text(album.displayName)
                            .font(.plex(.body))
                            .lineLimit(1)
                        Text(album.artistName)
                            .font(.plex(.caption))
                            .foregroundStyle(Color.albumArtistText)
                            .lineLimit(1)
                        if let year = album.productionYear {
                            Text(String(year))
                                .font(.plex(.caption2))
                                .foregroundStyle(Color.albumYearText)
                        }
                    }
                    Spacer()
                    if isDownloaded(album) {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.plex(.title3))
                            .foregroundStyle(.green)
                    }
                }
            }
            .id(album.id)
            .listRowBackground(Color.clear)
            .onAppear { model.loadMoreIfNeeded(after: album) }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    // MARK: - States

    @ViewBuilder
    private func emptyState(model: LibraryViewModel) -> some View {
        let downloadedOnly = model.filter == .downloaded || model.isOffline
        VStack(spacing: 12) {
            Image(systemName: "music.note.list")
                .font(.system(size: 48.scaled))
                .foregroundStyle(.secondary)
            Text(downloadedOnly
                 ? "No downloaded albums yet"
                 : "Library is empty")
                .font(.plex(.headline))
                .foregroundStyle(.secondary)
            Text(downloadedOnly
                 ? "Download an album from its detail page to play it offline."
                 : "Connect to a server with music to get started.")
                .font(.plex(.caption))
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
            if model.filter == .downloaded, !model.isOffline {
                Button("Show all albums") {
                    model.filter = .all
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding()
    }

    @ViewBuilder
    private func errorState(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 48.scaled))
                .foregroundStyle(.orange)
            Text("Couldn't load library")
                .font(.plex(.headline))
            Text(message)
                .font(.plex(.caption))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Retry") { viewModel?.loadLibrary() }
                .buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
        .padding()
    }

    // MARK: - Helpers

    /// For a multi-disc album, every disc must be downloaded.
    private func isDownloaded(_ album: JellyfinAlbum) -> Bool {
        album.allDiscs.allSatisfy { disc in
            downloadManager?.isDownloaded(disc.id) ?? DownloadPaths.exists(albumID: disc.id)
        }
    }

    // MARK: - Options menu

    /// View, Sort and Filter in one menu, shown at the right end of the
    /// Albums / Artists / Favourites row (Albums only).
    func optionsMenu(model: LibraryViewModel) -> some View {
        Menu {
            Picker("View", selection: Binding(get: { model.displayMode }, set: { model.displayMode = $0 })) {
                ForEach(LibraryDisplayMode.allCases) { mode in
                    Label(mode.rawValue, systemImage: LibraryView.symbol(for: mode)).tag(mode)
                }
            }
            .pickerStyle(.menu)
            Picker("Sort", selection: Binding(get: { model.sortOption }, set: { model.sortOption = $0 })) {
                ForEach(LibrarySortOption.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.menu)
            Picker("Filter", selection: Binding(get: { model.filter }, set: { model.filter = $0 })) {
                ForEach(LibraryFilter.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.menu)
            if model.sortOption == .random {
                Divider()
                Button {
                    model.sortOption = .random   // re-picking deals a new shuffle
                } label: {
                    Label("Shuffle again", systemImage: "shuffle")
                }
            }
        } label: {
            Image(systemName: "slider.horizontal.3")
                .font(.plex(.title3))
                .foregroundStyle(.white)
                .frame(width: libraryTabAccessoryWidth.scaled, height: 32.scaled)
                .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .accessibilityLabel("View, sort and filter")
        .accessibilityIdentifier("LibraryView.options")
    }

    /// View and Sort for the Playlists tab, in the same place.
    func playlistsOptionsMenu(model: PlaylistsViewModel) -> some View {
        Menu {
            Picker("View", selection: Binding(get: { model.displayMode }, set: { model.displayMode = $0 })) {
                ForEach(LibraryDisplayMode.allCases) { mode in
                    Label(mode.rawValue, systemImage: LibraryView.symbol(for: mode)).tag(mode)
                }
            }
            .pickerStyle(.menu)
            Picker("Sort", selection: Binding(get: { model.sortOption }, set: { model.sortOption = $0 })) {
                ForEach(PlaylistSortOption.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.menu)
        } label: {
            Image(systemName: "slider.horizontal.3")
                .font(.plex(.title3))
                .foregroundStyle(.white)
                .frame(width: libraryTabAccessoryWidth.scaled, height: 32.scaled)
                .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .accessibilityLabel("View and sort")
        .accessibilityIdentifier("LibraryView.playlistsOptions")
    }

    static func symbol(for mode: LibraryDisplayMode) -> String {
        mode == .grid ? "square.grid.2x2" : "list.bullet"
    }
}

