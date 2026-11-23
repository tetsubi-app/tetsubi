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

    @Environment(\.isWideLayout) private var isWide
    @Environment(\.displayScale) private var displayScale

    /// Whether the tab shown is a grid the +/− buttons resize.
    private var showsZoomableGrid: Bool {
        guard let model = viewModel else { return false }
        switch tab {
        case .albums:     return model.displayMode == .grid
        case .artists:    return model.artistsDisplayMode == .grid
        case .favourites: return FavouritesOptions.shared.displayMode == .grid
        case .playlists:  return false
        }
    }

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
                case .artists:    ArtistsTabView(api: api, mode: model.artistsDisplayMode)
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
                LibraryTabBar(
                    selection: Binding(get: { tab }, set: { viewModel?.tab = $0 }),
                    // Albums tapped while it's showing: the next sort (as the menu's Sort).
                    reselect: { tab in
                        guard tab == .albums, let model = viewModel else { return nil }
                        return model.cycleSort().rawValue
                    }
                ) {
                    // iPad: − and + before the options menu (the Mac has
                    // them in the title bar instead).
                    if GridZoom.buttonsInTabRow(wide: isWide), showsZoomableGrid {
                        GridZoomButtons()
                    }
                    if tab == .albums, let model = viewModel {
                        optionsMenu(model: model)
                    } else if tab == .artists, let model = viewModel {
                        artistsOptionsMenu(model: model)
                    } else if tab == .favourites {
                        favouritesOptionsMenu(options: FavouritesOptions.shared)
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
        #else
        // Mac: − and + at the right of the title bar.
        .gridZoomToolbar(showsZoomableGrid)
        #endif
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
                    Group {
                        switch model.displayMode {
                        case .grid: gridView(model: model, proxy: proxy)
                        case .list: listView(model: model, proxy: proxy)
                        }
                    }
                    // A new sort starts at the top. Otherwise the list keeps the
                    // album that was at the top in view, wherever it now sorts.
                    .onChange(of: model.sortOption) { _, _ in
                        if let first = model.sortedAlbums.first?.id { proxy.scrollTo(first, anchor: .top) }
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
        AlphaIndexedAlbums(albums: model.sortedAlbums, key: model.indexKey, content: content)
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

    /// Cards per row the +/− buttons allow on wide layouts.
    nonisolated static let gridColumnRange = 3...10
    /// Cover sizes asked of the server, in pixels. Few, so the image cache
    /// still gets hits as the cards change size.
    nonisolated static let gridImageSizes = [300, 600, 1000, 1500]

    /// A grid's layout: columns, the gap between them, each card's width
    /// (the cover's side), how much its text grows, and the cover size to
    /// ask the server for.
    struct GridMetrics: Equatable, Sendable {
        var columns: Int
        var spacing: CGFloat
        var cardWidth: CGFloat
        var textScale: CGFloat
        var imageSize: Int
    }

    /// The grid for `width`. Not wide (phones): `gridLayout` with fixed
    /// 120pt covers, as always. Wide (Mac, iPad full width): `chosen`
    /// columns (the +/− setting) or the automatic count kept to 3…10, each
    /// cover filling its column, so a wider window means bigger cards
    /// rather than more of them. The text grows with the card (never
    /// smaller than on a phone, at most 3×) and the cover is asked for at
    /// the next size up from its pixels on this display.
    nonisolated static func gridMetrics(width: CGFloat, scale: CGFloat = 1, wide: Bool,
                                        chosen: Int? = nil, displayScale: CGFloat = 2) -> GridMetrics {
        let auto = gridLayout(width: width, scale: scale)
        let art = gridArtSize * scale
        guard wide else {
            return GridMetrics(columns: auto.columns, spacing: auto.spacing, cardWidth: art,
                               textScale: 1, imageSize: gridImageSizes[0])
        }
        let range = gridColumnRange
        let columns = min(max(chosen ?? auto.columns, range.lowerBound), range.upperBound)
        return gridMetrics(width: width, scale: scale, columns: columns, displayScale: displayScale)
    }

    /// Exactly `columns` across on any device (Recently Played: as many as
    /// the export's size), each cover filling its column with the wide
    /// layout's 12pt gap. The text grows with the card as on wide layouts,
    /// but never below a phone's (narrow cards keep the phone's text and
    /// cut it short), at most 3×.
    nonisolated static func gridMetrics(width: CGFloat, scale: CGFloat = 1, columns: Int,
                                        displayScale: CGFloat = 2) -> GridMetrics {
        let columns = max(1, columns)
        let art = gridArtSize * scale
        let gap = 12 * scale
        let card = max(1, (width - gap * CGFloat(columns - 1)) / CGFloat(columns))
        let textScale = min(max(card / art, 1), 3)
        return GridMetrics(columns: columns, spacing: gap, cardWidth: card,
                           textScale: textScale, imageSize: imageSize(forPixels: card * displayScale))
    }

    /// The smallest of `gridImageSizes` that covers `pixels`, or the largest.
    nonisolated static func imageSize(forPixels pixels: CGFloat) -> Int {
        gridImageSizes.first { CGFloat($0) >= pixels } ?? gridImageSizes.last!
    }

    /// Home's album card at scale 1 (`AlbumCardView.width`), wider than
    /// the grid's.
    nonisolated static let homeCardSize: CGFloat = 140
    /// Space before the first card in a Home row (the section titles'
    /// inset), which the row's width is measured after.
    nonisolated static let homeRowLeading: CGFloat = 16

    /// The cards in Home's sideways rows for a row `width` wide (the whole
    /// screen, `homeRowLeading` included). Not wide (phones): today's fixed
    /// 140pt cards. Wide: `chosen` cards per row (the Library's +/−
    /// setting) or as many of today's cards as fit whole, kept to 3…10;
    /// they're sized so that many fit with a third of the next one peeking
    /// in at the edge, which shows the row scrolls. Text grows with the
    /// card as in the Library (from 140pt rather than 120), and the cover
    /// size asked for follows its pixels.
    nonisolated static func homeRowMetrics(width: CGFloat, scale: CGFloat = 1, wide: Bool,
                                           chosen: Int? = nil, displayScale: CGFloat = 2) -> GridMetrics {
        let art = homeCardSize * scale
        let gap = 12 * scale
        let visible = width - homeRowLeading
        let fits = max(1, Int((visible + gap) / (art + gap)))
        guard wide else {
            return GridMetrics(columns: fits, spacing: gap, cardWidth: art,
                               textScale: 1, imageSize: gridImageSizes[0])
        }
        let range = gridColumnRange
        let count = min(max(chosen ?? fits, range.lowerBound), range.upperBound)
        let card = max(1, (visible - CGFloat(count) * gap) / (CGFloat(count) + 1.0 / 3))
        let textScale = min(max(card / art, 1), 3)
        return GridMetrics(columns: count, spacing: gap, cardWidth: card,
                           textScale: textScale, imageSize: imageSize(forPixels: card * displayScale))
    }

    /// The count after a zoom step from `current`: in is one fewer per row
    /// (bigger cards), out one more. nil at the limit (the button is off).
    nonisolated static func zoomed(from current: Int, in zoomIn: Bool) -> Int? {
        let clamped = min(max(current, gridColumnRange.lowerBound), gridColumnRange.upperBound)
        let next = clamped + (zoomIn ? -1 : 1)
        return gridColumnRange.contains(next) ? next : nil
    }

    private func gridView(model: LibraryViewModel, proxy: ScrollViewProxy) -> some View {
        // The A–Z strip already leaves room on the right, so no trailing
        // padding when it's showing — that's what lets 3 covers fit on a phone.
        let trailing: CGFloat = model.indexKey != nil ? 0 : Self.gridEdgePadding
        return GeometryReader { geo in
            let metrics = Self.gridMetrics(width: geo.size.width - Self.gridEdgePadding - trailing,
                                           scale: UIScale.shared.factor, wide: isWide,
                                           chosen: GridZoom.shared.columns, displayScale: displayScale)
            ScrollView {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: metrics.spacing, alignment: .top),
                                     count: metrics.columns),
                      spacing: 16.scaled * metrics.textScale) {
                ForEach(model.sortedAlbums) { album in
                    NavigationLink(value: album) {
                        AlbumGridCard(album: album,
                                      request: api?.imageURLRequest(for: album.id, size: metrics.imageSize),
                                      metrics: metrics, isDownloaded: isDownloaded(album))
                    }
                    .buttonStyle(.plain)
                    .albumQueueMenu(album)
                    .id(album.id)
                    .onAppear { model.loadMoreIfNeeded(after: album) }
                }
            }
            .padding(.leading, Self.gridEdgePadding)
            .padding(.trailing, trailing)
            .padding(.vertical)
            }
            .reportsGridColumns(metrics.columns, active: isWide)
        }
    }

    // MARK: - List

    private func listView(model: LibraryViewModel, proxy: ScrollViewProxy) -> some View {
        AlbumListView(api: api, albums: model.sortedAlbums, isDownloaded: isDownloaded) {
            model.loadMoreIfNeeded(after: $0)
        }
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
    /// Albums / Artists / Favourites / Playlists row (Albums).
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

    /// View, Sort and Filter for the Favourites tab, in the same place and
    /// wording as the Albums one. Home ▸ Favourites follows these too.
    func favouritesOptionsMenu(options: FavouritesOptions) -> some View {
        Menu {
            Picker("View", selection: Binding(get: { options.displayMode }, set: { options.displayMode = $0 })) {
                ForEach(LibraryDisplayMode.allCases) { mode in
                    Label(mode.rawValue, systemImage: LibraryView.symbol(for: mode)).tag(mode)
                }
            }
            .pickerStyle(.menu)
            Picker("Sort", selection: Binding(get: { options.sortOption }, set: { options.sortOption = $0 })) {
                ForEach(FavouritesSortOption.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.menu)
            Picker("Filter", selection: Binding(get: { options.filter }, set: { options.filter = $0 })) {
                ForEach(LibraryFilter.allCases) { Text($0.rawValue).tag($0) }
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
        .accessibilityLabel("View, sort and filter")
        .accessibilityIdentifier("LibraryView.favouritesOptions")
    }

    /// Grid or list for the Artists tab, in the same place and wording.
    func artistsOptionsMenu(model: LibraryViewModel) -> some View {
        Menu {
            Picker("View", selection: Binding(get: { model.artistsDisplayMode },
                                              set: { model.artistsDisplayMode = $0 })) {
                ForEach(LibraryDisplayMode.allCases) { mode in
                    Label(mode.rawValue, systemImage: LibraryView.symbol(for: mode)).tag(mode)
                }
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
        .accessibilityLabel("View")
        .accessibilityIdentifier("LibraryView.artistsOptions")
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

