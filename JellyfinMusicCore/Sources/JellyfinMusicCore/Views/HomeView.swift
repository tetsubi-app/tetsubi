// MARK: - HomeView — main dashboard

import SwiftUI
import Observation

/// The main home screen. Holds a `HomeViewModel` and displays the four sections.
///
/// Layout (PlexAmp-inspired): a vertical `LazyVStack` of titled, horizontally
/// scrolling rows over `AppBackgroundView` (blurred album art while playing). Each row is a `HomeSectionRow`
/// (or `OnThisDayRow`); tapping a card pushes `AlbumDetailView`. On a wide
/// layout every row's cards follow the Library's cards-per-row setting
/// (`LibraryView.homeRowMetrics`) and the −/+ buttons sit in the top bar.
public struct HomeView: View {

    @State private var viewModel: HomeViewModel?
    private let api: JellyfinAPI?
    private let player: PlayerManager?
    private let downloadManager: DownloadManager?
    /// Opens Library ▸ Playlists (the Playlists row's ›).
    private let onShowPlaylists: (() -> Void)?

    @Environment(\.isWideLayout) private var isWide
    @Environment(\.displayScale) private var displayScale

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

    // MARK: Body

    public var body: some View {
        ZStack {
            GeometryReader { geo in
            // Wide layouts: every row's cards follow the Library's
            // cards-per-row setting; phones keep today's fixed cards (nil).
            let metrics = LibraryView.homeRowMetrics(width: geo.size.width, scale: UIScale.shared.factor,
                                                     wide: isWide, chosen: GridZoom.shared.columns,
                                                     displayScale: displayScale)
            let cards = isWide ? metrics : nil
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    statusBanner

                    // "On This Day" first — the most distinctive feature. Hidden
                    // when nothing was released on today's date.
                    if showsOnThisDay {
                        sectionHeader("On This Day")
                        onThisDaySection(cards)
                    }

                    sectionHeader("Favourites", more: .favourites)
                    albumRow("Favourites", viewModel?.favorites, empty: "No favourite albums yet.", cards: cards)

                    sectionHeader("Random Albums", more: .random)
                    albumRow("Random Albums", viewModel?.randomAlbums, empty: "No random albums available.", cards: cards)

                    // Only when there's at least one playlist.
                    if !shownPlaylists.isEmpty {
                        sectionHeader("Playlists", action: onShowPlaylists)
                        HomePlaylistsRow(api: api, playlists: shownPlaylists, metrics: cards)
                    }

                    sectionHeader("Recently Played", more: .recentlyPlayed)
                    albumRow("Recently Played", viewModel?.recentlyPlayed, empty: "No recently played music yet.", cards: cards)

                    sectionHeader("Recently Added")
                    albumRow("Recently Added", viewModel?.recentlyAdded, empty: "No recently added music.", cards: cards)
                }
                .padding(.bottom, 24)
            }
            // The first −/+ press starts from Home's count while it shows.
            .reportsGridColumns(metrics.columns, active: isWide)
            .navigationDestination(for: HomeMore.self) { more in
                switch more {
                case .favourites:
                    FavouritesTabView(api: api)
                        .navigationTitle("Favourites")
                        .appTheme()
                        // The Library's Favourites choices; no −/+ in List.
                        .gridZoomToolbar(FavouritesOptions.shared.displayMode == .grid)
                case .random:
                    RandomAlbumsView(api: api)
                        .appTheme()
                case .recentlyPlayed:
                    // No −/+: its columns follow the export's size.
                    RecentlyPlayedView(api: api, initial: viewModel?.recentlyPlayed ?? [])
                        .appTheme()
                }
            }
            .refreshable { viewModel?.refresh() }
            .task { viewModel?.refresh() }
            // Reload once the connection comes back.
            .onChange(of: NetworkMonitor.shared.isConnected) { _, connected in
                if connected { viewModel?.refresh() }
            }
            // A playlist was made, changed or deleted: its row again.
            .onChange(of: PlaylistEvents.shared.version) { _, _ in
                viewModel?.refreshPlaylists()
            }
            }
        }
        // Mac: − and + at the right of the title bar; iPad: in Home's slim bar.
        .gridZoomToolbar()
        .untitledTab()
    }

    /// The server's playlists; offline, only the downloaded ones.
    private var shownPlaylists: [JellyfinPlaylist] {
        guard NetworkMonitor.shared.isConnected else {
            return PlaylistsViewModel.sorted(downloadManager?.playlists.offlinePlaylists ?? [], by: .name)
        }
        return viewModel?.playlists ?? []
    }

    /// Offline, or the server couldn't be reached — otherwise every row
    /// would just say it's empty.
    @ViewBuilder
    private var statusBanner: some View {
        if !NetworkMonitor.shared.isConnected {
            StatusBanner.offline
        } else if let error = viewModel?.errorMessage, !(viewModel?.isLoading ?? false) {
            StatusBanner(systemImage: "exclamationmark.triangle",
                         message: "Can't reach your Jellyfin server. \(error)",
                         actionTitle: "Retry") { viewModel?.refresh() }
        }
    }

    // MARK: - Sections

    /// A row title; with `more`, a › on the right opens the full list
    /// (with `action`, the › runs that instead).
    private func sectionHeader(_ title: String, more: HomeMore? = nil, action: (() -> Void)? = nil) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.plex(.title3)).bold()
                .foregroundStyle(.primary)
            Spacer()
            if let more {
                NavigationLink(value: more) { moreChevron }
                    .buttonStyle(.plain)
                    .accessibilityLabel("View all \(title)")
            } else if let action {
                Button(action: action) { moreChevron }
                    .buttonStyle(.plain)
                    .accessibilityLabel("View all \(title)")
            }
        }
        .padding(.horizontal)
        .padding(.top, 20.scaled)
        .padding(.bottom, 8.scaled)
    }

    private var moreChevron: some View {
        Image(systemName: "chevron.right")
            .font(.plex(.title3).weight(.semibold))
            .foregroundStyle(Color.appBlue)
            .frame(minWidth: 44.scaled, minHeight: 32.scaled, alignment: .trailing)
            .contentShape(Rectangle())
    }

    /// While Home loads (placeholder cards), or when something matched.
    private var showsOnThisDay: Bool {
        guard let viewModel else { return false }
        return viewModel.isLoading || !viewModel.onThisDay.isEmpty
    }

    @ViewBuilder
    private func onThisDaySection(_ cards: LibraryView.GridMetrics?) -> some View {
        if let entries = viewModel?.onThisDay, !entries.isEmpty {
            OnThisDayRow(api: api, entries: entries, downloadManager: downloadManager, metrics: cards)
        } else {
            AlbumRowPlaceholder(metrics: cards)
        }
    }

    private func albumRow(_ title: String, _ albums: [JellyfinAlbum]?, empty: String,
                          cards: LibraryView.GridMetrics?) -> some View {
        HomeSectionRow(
            api: api,
            title: title,
            albums: albums ?? [],
            isLoading: viewModel?.isLoading ?? false,
            emptyMessage: empty,
            downloadManager: downloadManager,
            metrics: cards
        )
    }
}

// MARK: - HomeSectionRow

/// A horizontal scroll row of album cards. Tapping a card pushes
/// `AlbumDetailView` via `navigationDestination(for: JellyfinAlbum.self)`.
struct HomeSectionRow: View {

    let api: JellyfinAPI?
    let title: String
    let albums: [JellyfinAlbum]
    let isLoading: Bool
    let emptyMessage: String
    let downloadManager: DownloadManager?
    /// Wide layouts: the cards' size (`LibraryView.homeRowMetrics`); nil
    /// for today's fixed cards.
    var metrics: LibraryView.GridMetrics? = nil

    var body: some View {
        Group {
            if albums.isEmpty {
                if isLoading {
                    AlbumRowPlaceholder(metrics: metrics)
                } else {
                    Text(emptyMessage)
                        .font(.plex(.footnote))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                }
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: metrics?.spacing ?? 12.scaled) {
                        ForEach(albums) { album in
                            NavigationLink(value: album) {
                                AlbumCardView(
                                    album: album,
                                    artRequest: api?.imageURLRequest(for: album.id, size: metrics?.imageSize ?? 300),
                                    isDownloaded: album.allDiscs.allSatisfy {
                                        downloadManager?.isDownloaded($0.id) ?? DownloadPaths.exists(albumID: $0.id)
                                    },
                                    width: metrics?.cardWidth,
                                    textScale: metrics?.textScale ?? 1
                                )
                            }
                            .buttonStyle(.plain)
                            .albumQueueMenu(album)
                        }
                    }
                    .homeRowPadding(wide: metrics != nil)
                    .padding(.bottom, 8.scaled)
                }
            }
        }
    }
}

// MARK: - OnThisDayRow

/// Horizontal scroll row of "On This Day" entries — each card shows an
/// "X years ago" badge and the album's display name + artist.
struct OnThisDayRow: View {

    let api: JellyfinAPI?
    let entries: [OnThisDayEntry]
    let downloadManager: DownloadManager?
    /// As `HomeSectionRow`'s.
    var metrics: LibraryView.GridMetrics? = nil

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: metrics?.spacing ?? 12.scaled) {
                ForEach(entries) { entry in
                    NavigationLink(value: entry.album) {
                        AlbumCardView(
                            album: entry.album,
                            artRequest: api?.imageURLRequest(for: entry.album.id, size: metrics?.imageSize ?? 300),
                            isDownloaded: entry.album.allDiscs.allSatisfy {
                                downloadManager?.isDownloaded($0.id) ?? DownloadPaths.exists(albumID: $0.id)
                            },
                            badge: entry.label.isEmpty ? nil : entry.label,
                            width: metrics?.cardWidth,
                            textScale: metrics?.textScale ?? 1
                        )
                    }
                    .buttonStyle(.plain)
                    .albumQueueMenu(entry.album)
                }
            }
            .homeRowPadding(wide: metrics != nil)
            .padding(.bottom, 8.scaled)
        }
    }
}

// MARK: - Untitled tab

extension View {
    /// No title in the bar (Home, Library, Downloads). The bar stays for
    /// toolbar buttons, in its slim style so no space is kept for a title.
    @ViewBuilder
    func untitledTab() -> some View {
        #if os(iOS)
        self.navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }
}

// MARK: - View all

/// The full lists Home's › buttons open.
enum HomeMore: Hashable {
    case favourites
    case random
    case recentlyPlayed
}

/// A page of random albums (Home ▸ Random Albums ›): three rows of three
/// (9), or four (12) when four rows fit the screen above "Shake it". Pull
/// down or shake the device for a fresh set.
struct RandomAlbumsView: View {
    let api: JellyfinAPI?
    @State private var albums: [JellyfinAlbum] = []
    @State private var loaded = false
    @State private var isVisible = false
    @State private var shakes = 0

    /// Title (one line here) and artist under each cover, with the gaps:
    /// grows with the text size setting.
    @ScaledMetric(relativeTo: .caption) private var cardText: CGFloat = 37

    /// Fetched each time; 9 or 12 of them are shown.
    static let fetchCount = 12

    /// 12 when four rows of cards, the grid's padding and the "Shake it"
    /// line all fit in `height` (the space above the tab bar and Now
    /// Playing bar), else 9. `scale` is the Mac's `UIScale` factor (the row
    /// gaps and footer grow with it).
    static func count(forHeight height: CGFloat, rowHeight: CGFloat, scale: CGFloat = 1) -> Int {
        let rowGap: CGFloat = 16 * scale, padding: CGFloat = 32, footer: CGFloat = 26 * scale
        return padding + 4 * rowHeight + 3 * rowGap + footer <= height ? 12 : 9
    }

    var body: some View {
        GeometryReader { geo in
            let count = Self.count(forHeight: geo.size.height,
                                   rowHeight: LibraryView.scaledGridArtSize + cardText.scaled,
                                   scale: UIScale.shared.factor)
            Group {
                if !loaded {
                    ProgressView().padding()
                } else if albums.isEmpty {
                    Text("No albums available.").font(.plex(.footnote)).foregroundStyle(.secondary).padding()
                } else {
                    AlbumGridView(api: api, albums: Array(albums.prefix(count)), footer: "Shake it", titleLines: 1)
                        .refreshable { await load() }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .navigationTitle("Random Albums")
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

    private func load() async {
        guard let api else { return }
        let result: Result<[JellyfinAlbum], JellyfinError> = await withCheckedContinuation { c in
            api.randomAlbums(limit: Self.fetchCount) { c.resume(returning: $0) }
        }
        if case .success(let items) = result { albums = DiscGrouping.merge(items) }
        loaded = true
    }
}
