import SwiftUI

/// The four top-level Library sections.
public enum LibraryTab: String, CaseIterable, Identifiable {
    case albums = "Albums"
    case artists = "Artists"
    case favourites = "Favourites"
    case playlists = "Playlists"
    public var id: String { rawValue }
}

/// Large tab strip pinned to the top of the Library.
/// Width kept at the right of the Library tab row for the options button
/// (and, on iPad, for each of the grid's − and + buttons).
let libraryTabAccessoryWidth: CGFloat = 36

struct LibraryTabBar<Accessory: View>: View {
    @Binding var selection: LibraryTab
    /// The selected tab's label tapped again: returns a word to show under
    /// it for a moment (Albums: the new sort's name), or nil for nothing.
    var reselect: (LibraryTab) -> String? = { _ in nil }
    /// Right-hand controls (the options menus; on iPad the grid's − and +
    /// too). Their width is always kept, so the tab labels don't move when
    /// some are hidden; they sit at the right end.
    @ViewBuilder var accessory: () -> Accessory

    @Environment(\.isWideLayout) private var isWide

    /// What `reselect` last said, shown under its tab and faded out.
    @State private var flash: Flash?
    @State private var flashCount = 0

    private struct Flash: Equatable {
        let tab: LibraryTab
        let text: String
        let id: Int
    }

    private func tapped(_ tab: LibraryTab) {
        guard selection == tab, let text = reselect(tab) else {
            selection = tab
            return
        }
        flashCount += 1
        let id = flashCount
        withAnimation(.easeOut(duration: 0.15)) { flash = Flash(tab: tab, text: text, id: id) }
        AccessibilityNotification.Announcement("Sorted by \(text)").post()
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1.5))
            guard flash?.id == id else { return }
            withAnimation(.easeOut(duration: 0.4)) { flash = nil }
        }
    }

    /// One button's width, or three where the − and + buttons join it.
    private var accessoryWidth: CGFloat {
        (GridZoom.buttonsInTabRow(wide: isWide) ? 3 : 1) * libraryTabAccessoryWidth.scaled
    }

    /// The least space between two labels (four are tight on a phone).
    private static var labelGap: CGFloat { 12.scaled }

    /// The labels at their full size where the four fit with that space
    /// between them (iPad, Mac), else the next size down that does.
    var body: some View {
        ViewThatFits(in: .horizontal) {
            row(font: .plex(.title3))
            row(font: .plex(.headline))
            row(font: .plex(.subheadline))
            row(font: .plex(.footnote), shrinks: true)
        }
        .padding(.horizontal)
        .padding(.top, 8.scaled)
        // Frosted, so the background picture shows through and albums
        // scrolling underneath are softened.
        .background(.ultraThinMaterial)
    }

    private func row(font: Font, shrinks: Bool = false) -> some View {
        HStack(spacing: 0) {
            ForEach(LibraryTab.allCases) { tab in
                Button {
                    tapped(tab)
                } label: {
                    VStack(spacing: 6.scaled) {
                        Text(tab.rawValue)
                            .font(font.weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(shrinks ? 0.7 : 1)
                            // Full width or not at all, so a size that
                            // would cut a label short counts as not fitting.
                            .fixedSize(horizontal: !shrinks, vertical: false)
                            // Room each side, so a size only counts as
                            // fitting when the labels aren't touching.
                            .padding(.horizontal, shrinks ? 0 : Self.labelGap / 2)
                            .foregroundStyle(selection == tab ? Color.white : Color.white.opacity(0.5))
                        Capsule()
                            .fill(selection == tab ? Color.appPink : Color.clear)
                            .frame(height: 3.scaled)
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                    // Just below the row, over the content: the labels don't move.
                    .overlay(alignment: .bottom) {
                        if let flash, flash.tab == tab {
                            Text(flash.text)
                                .font(.plex(.caption).weight(.semibold))
                                .foregroundStyle(.white)
                                .lineLimit(1)
                                .fixedSize()
                                .padding(.horizontal, 8.scaled)
                                .padding(.vertical, 2.scaled)
                                .background(.ultraThinMaterial, in: Capsule())
                                .alignmentGuide(.bottom) { $0[.top] - 6.scaled }
                                .transition(.opacity)
                                .allowsHitTesting(false)
                                .accessibilityHidden(true)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
            HStack(spacing: 0) { accessory() }
                .frame(width: accessoryWidth, alignment: .trailing)
                .padding(.bottom, 9.scaled)   // line up with the labels, above the underline
        }
    }
}

/// One album in a grid: cover, downloaded tick, title and artist. On a
/// wide layout the cover fills its column and the text and gaps grow with
/// it (`LibraryView.GridMetrics`); on a phone it's the fixed 120pt card.
struct AlbumGridCard: View {
    let album: JellyfinAlbum
    let request: URLRequest?
    let metrics: LibraryView.GridMetrics
    let isDownloaded: Bool
    var titleLines: Int = 2
    /// The release year under the title instead of the artist (an
    /// artist's page, where the name would only repeat).
    var showsYear: Bool = false

    var body: some View {
        let t = metrics.textScale
        VStack(spacing: 4.scaled * t) {
            AlbumArtView(request: request, size: metrics.cardWidth, cornerRadius: 8.scaled * t)
                .overlay(alignment: .bottomTrailing) {
                    if isDownloaded {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.plex(.caption, times: t))
                            .foregroundStyle(.green)
                            .padding(4 * t)
                    }
                }
            Text(album.displayName)
                .font(.plex(.caption, times: t).bold())
                .lineLimit(titleLines)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, alignment: .center)
            if showsYear {
                if let year = ArtistPage.yearText(album) {
                    Text(year)
                        .font(.plex(.caption2, times: t))
                        .foregroundStyle(Color.albumYearText)
                        .lineLimit(1)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity, alignment: .center)
                }
            } else {
                Text(album.artistName)
                    .font(.plex(.caption2, times: t))
                    .foregroundStyle(Color.albumArtistText)
                    .lineLimit(1)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
        }
    }
}

/// An artist's album page: newest first, with each album's year where
/// other grids show the artist.
enum ArtistPage {
    /// Newest release first (Library's Year sort: full release date, else
    /// Jan 1 of the year, unknown last); the same date goes by title.
    static func ordered(_ albums: [JellyfinAlbum]) -> [JellyfinAlbum] {
        LibraryViewModel.sort(LibraryViewModel.sort(albums, by: .name), by: .year)
    }

    /// The year under an album's title, as "More by" shows it: the
    /// production year, else the year of the release date; nil if unknown.
    static func yearText(_ album: JellyfinAlbum) -> String? {
        if let year = album.productionYear { return String(year) }
        guard let date = album.releaseDate else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        return String(utc.component(.year, from: date))
    }
}

/// Square-art album grid shared by the Favourites tab and artist pages.
struct AlbumGridView: View {
    let api: JellyfinAPI?
    let albums: [JellyfinAlbum]
    /// Shown centred under the grid (Random Albums' "Shake it").
    var footer: String? = nil
    /// Anything else under the grid, scrolling with it (Recently Played's
    /// Export grid section).
    var footerContent: AnyView? = nil
    /// Lines for each album title (Random Albums uses 1 so four rows fit).
    var titleLines: Int = 2
    /// Follows the +/− card size on wide layouts. Off for Random Albums,
    /// whose 9-or-12 count is worked out from the fixed card size.
    var zooms: Bool = false
    /// Exactly this many across on every device, covers filling their
    /// columns, no +/− (Recently Played: the export's size). Overrides `zooms`.
    var fixedColumns: Int? = nil
    /// Without `zooms`: still the wide layout's automatic count and
    /// column-filling covers on wide layouts, just not the +/− setting
    /// (Recently Played with too few albums to export).
    var widensWithoutZoom: Bool = false
    /// Padding on the right too; off while the A–Z strip leaves that room
    /// (as Library ▸ Albums, so three covers still fit on a phone).
    var trailingEdge: Bool = true
    /// Each album's year in place of the artist (an artist's page).
    var showsYear: Bool = false

    @Environment(\.isWideLayout) private var isWideLayout
    @Environment(\.displayScale) private var displayScale

    /// Same layout as Library ▸ Albums: fixed 120pt covers, 3 across on a
    /// phone (`LibraryView.gridLayout`), downloaded tick on the cover; on
    /// a wide layout the shared cards-per-row setting.
    var body: some View {
        let wide = fixedColumns == nil && zooms && isWideLayout
        GeometryReader { geo in
            let edge = LibraryView.gridEdgePadding
            let trailing = trailingEdge ? edge : 0
            let width = geo.size.width - edge - trailing
            let metrics: LibraryView.GridMetrics = if let fixedColumns {
                LibraryView.gridMetrics(width: width, scale: UIScale.shared.factor, columns: fixedColumns,
                                        displayScale: displayScale)
            } else {
                // The +/− setting is read only by grids that follow it.
                LibraryView.gridMetrics(width: width, scale: UIScale.shared.factor,
                                        wide: wide || (widensWithoutZoom && isWideLayout),
                                        chosen: wide ? GridZoom.shared.columns : nil, displayScale: displayScale)
            }
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: metrics.spacing, alignment: .top),
                                         count: metrics.columns),
                          spacing: 16.scaled * metrics.textScale) {
                    ForEach(albums) { album in
                        NavigationLink(value: album) {
                            AlbumGridCard(album: album,
                                          request: api?.imageURLRequest(for: album.id, size: metrics.imageSize),
                                          metrics: metrics, isDownloaded: isDownloaded(album),
                                          titleLines: titleLines, showsYear: showsYear)
                        }
                        .buttonStyle(.plain)
                        .albumQueueMenu(album)
                        .id(album.id)   // for the A–Z strip's jumps
                    }
                }
                .padding(.leading, edge)
                .padding(.trailing, trailing)
                .padding(.vertical)
                if let footer {
                    Text(footer)
                        .font(.plex(.footnote))
                        .foregroundStyle(Color.albumYearText)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.bottom, 24)
                }
                if let footerContent {
                    footerContent
                        .padding(.leading, edge)
                        .padding(.trailing, trailing)
                        .padding(.bottom, 24)
                }
            }
            .reportsGridColumns(metrics.columns, active: wide)
        }
    }

    private func isDownloaded(_ album: JellyfinAlbum) -> Bool {
        LibraryOffline.isDownloaded(album)
    }
}

/// What the Favourites tab and artist pages show offline.
enum LibraryOffline {
    /// For a multi-disc album, every disc must be downloaded.
    static func isDownloaded(_ album: JellyfinAlbum) -> Bool {
        album.allDiscs.allSatisfy { DownloadPaths.exists(albumID: $0.id) }
    }

    /// Favourites; offline, the downloaded ones only.
    static func favourites(_ albums: [JellyfinAlbum], offline: Bool,
                           isDownloaded: (JellyfinAlbum) -> Bool = isDownloaded) -> [JellyfinAlbum] {
        offline ? albums.filter(isDownloaded) : albums
    }

    /// An artist's albums; offline, only the downloaded ones (matched by
    /// artist name from the downloads when the list never loaded).
    static func artistAlbums(_ albums: [JellyfinAlbum], artist: String, offline: Bool,
                             downloaded: () -> [JellyfinAlbum],
                             isDownloaded: (JellyfinAlbum) -> Bool = isDownloaded) -> [JellyfinAlbum] {
        if !offline { return albums }
        if !albums.isEmpty { return albums.filter(isDownloaded) }
        return downloaded().filter { $0.artistName.caseInsensitiveCompare(artist) == .orderedSame }
    }
}

/// Album rows: small cover, title, artist, year, the downloaded tick at
/// the right. Library ▸ Albums' and Favourites' list view.
struct AlbumListView: View {
    let api: JellyfinAPI?
    let albums: [JellyfinAlbum]
    let isDownloaded: (JellyfinAlbum) -> Bool
    /// Runs as each album appears (Random's next page).
    var onAppear: (JellyfinAlbum) -> Void = { _ in }

    var body: some View {
        List(albums) { album in
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
            .albumQueueMenu(album)
            .id(album.id)
            .listRowBackground(Color.clear)
            .onAppear { onAppear(album) }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }
}

/// An album grid or list with a scroll proxy and, when `key` is set (the
/// alphabetical sorts), the A–Z strip on the right that jumps to the
/// first album under each letter.
struct AlphaIndexedAlbums<Content: View>: View {
    let albums: [JellyfinAlbum]
    let key: ((JellyfinAlbum) -> String)?
    @ViewBuilder let content: (ScrollViewProxy) -> Content

    var body: some View {
        ScrollViewReader { proxy in
            let firstByBucket = Self.firstAlbumIDs(albums, key: key)
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

    /// The first album's ID under each letter of the strip.
    static func firstAlbumIDs(_ albums: [JellyfinAlbum], key: ((JellyfinAlbum) -> String)?) -> [String: String] {
        guard let key else { return [:] }
        var result: [String: String] = [:]
        for album in albums {
            let bucket = AlphaIndex.bucket(for: key(album))
            if result[bucket] == nil { result[bucket] = album.id }
        }
        return result
    }
}

/// Favourite albums as a grid or list, sorted and filtered by
/// `FavouritesOptions` (most recently favourited first by default), with
/// the A–Z strip for the Name and Artist sorts. Offline, the downloaded
/// ones only. Library ▸ Favourites and Home ▸ Favourites.
struct FavouritesTabView: View {
    let api: JellyfinAPI?
    @State private var all: [JellyfinAlbum] = []
    @State private var loaded = false

    private var options: FavouritesOptions { .shared }
    private var isOffline: Bool { !NetworkMonitor.shared.isConnected }

    var body: some View {
        let albums = FavouritesOptions.arrange(all, sort: options.sortOption, filter: options.filter,
                                               offline: isOffline, isDownloaded: LibraryOffline.isDownloaded)
        Group {
            if !loaded {
                ProgressView().padding()
            } else if albums.isEmpty, options.filter == .downloaded, !isOffline, !all.isEmpty {
                VStack(spacing: 12) {
                    Text("No downloaded favourite albums yet.")
                        .font(.plex(.footnote))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button("Show all favourites") { options.filter = .all }
                        .font(.plex(.footnote))
                }
                .padding()
            } else if albums.isEmpty {
                Text("No favourite albums yet.\nTap the heart on an album to add one.")
                    .font(.plex(.footnote))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding()
            } else {
                let key = options.indexKey
                AlphaIndexedAlbums(albums: albums, key: key) { _ in
                    switch options.displayMode {
                    case .grid:
                        AlbumGridView(api: api, albums: albums, zooms: true, trailingEdge: key == nil)
                    case .list:
                        AlbumListView(api: api, albums: albums, isDownloaded: LibraryOffline.isDownloaded)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Again when the connection comes back.
        .task(id: NetworkMonitor.shared.isConnected) { load() }
    }

    /// Each time the tab shows (favourites change on album pages). Nothing
    /// is asked of the server offline.
    private func load() {
        guard NetworkMonitor.shared.isConnected, let api else {
            loaded = true
            return
        }
        api.favoriteAlbums { result in
            DispatchQueue.main.async {
                if case .success(let items) = result { all = DiscGrouping.merge(FavoritesStore.ordered(items)) }
                loaded = true
            }
        }
    }
}

/// All album artists with the A–Z strip; tap one to see their albums.
/// A grid of round pictures (sized like the album grid) or a list.
struct ArtistsTabView: View {
    let api: JellyfinAPI?
    var mode: LibraryDisplayMode = .grid
    @State private var artists: [JellyfinArtist] = []
    @State private var loaded = false

    @Environment(\.isWideLayout) private var isWide
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        Group {
            if !loaded {
                ProgressView().padding()
            } else if artists.isEmpty {
                Text("No artists found.")
                    .font(.plex(.footnote)).foregroundStyle(.secondary).padding()
            } else {
                ScrollViewReader { proxy in
                    let firstByBucket = firstIDs()
                    Group {
                        switch mode {
                        case .grid: grid
                        case .list: list
                        }
                    }
                    .padding(.trailing, AlphaIndexBar.width)
                    .overlay(alignment: .trailing) {
                        AlphaIndexBar(available: Set(firstByBucket.keys)) { label in
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
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Again when the connection comes back, if it came back empty.
        .task(id: NetworkMonitor.shared.isConnected) { load() }
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(artists) { artist in
                    NavigationLink(value: artist) {
                        HStack(spacing: 12.scaled) {
                            AlbumArtView(
                                request: api?.imageURLRequest(for: artist.id, size: 100),
                                size: 48.scaled, cornerRadius: 24.scaled
                            )
                            Text(artist.name).lineLimit(1)
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal)
                        .padding(.vertical, 6.scaled)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .id(artist.id)
                }
            }
        }
    }

    /// Same columns and card size as the album grid (the A–Z strip leaves
    /// the room on the right, as in Library ▸ Albums).
    private var grid: some View {
        GeometryReader { geo in
            let edge = LibraryView.gridEdgePadding
            let metrics = LibraryView.gridMetrics(width: geo.size.width - edge,
                                                  scale: UIScale.shared.factor, wide: isWide,
                                                  chosen: GridZoom.shared.columns, displayScale: displayScale)
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: metrics.spacing, alignment: .top),
                                         count: metrics.columns),
                          spacing: 16.scaled * metrics.textScale) {
                    ForEach(artists) { artist in
                        NavigationLink(value: artist) {
                            ArtistGridCard(artist: artist,
                                           request: api?.imageURLRequest(for: artist.id, size: metrics.imageSize),
                                           metrics: metrics)
                        }
                        .buttonStyle(.plain)
                        .id(artist.id)
                    }
                }
                .padding(.leading, edge)
                .padding(.vertical)
            }
            .reportsGridColumns(metrics.columns, active: isWide)
        }
    }

    private func firstIDs() -> [String: String] {
        var result: [String: String] = [:]
        for artist in artists {
            let bucket = AlphaIndex.bucket(for: artist.name)
            if result[bucket] == nil { result[bucket] = artist.id }
        }
        return result
    }

    /// Once (again if it came back empty). Nothing is asked of the server offline.
    private func load() {
        guard !loaded || artists.isEmpty else { return }
        guard NetworkMonitor.shared.isConnected, let api else {
            loaded = true
            return
        }
        api.albumArtists { result in
            DispatchQueue.main.async {
                if case .success(let items) = result {
                    // Re-sort so the order matches the index buckets.
                    artists = items.sorted {
                        let (a, b) = (AlphaIndex.rank(for: $0.name), AlphaIndex.rank(for: $1.name))
                        return a != b ? a < b : AlphaIndex.folded($0.name) < AlphaIndex.folded($1.name)
                    }
                }
                loaded = true
            }
        }
    }
}

/// One artist in the grid: a round picture filling the card's width (the
/// music-note placeholder when there's none), name centred underneath in
/// the album title's style.
struct ArtistGridCard: View {
    let artist: JellyfinArtist
    let request: URLRequest?
    let metrics: LibraryView.GridMetrics

    var body: some View {
        let t = metrics.textScale
        VStack(spacing: 4.scaled * t) {
            AlbumArtView(request: request, size: metrics.cardWidth, cornerRadius: metrics.cardWidth / 2)
            Text(artist.name)
                .font(.plex(.caption, times: t).bold())
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, alignment: .center)
        }
    }
}

/// One artist's albums, newest first, each with its year; offline, their
/// downloaded albums only.
struct ArtistAlbumsView: View {
    let api: JellyfinAPI?
    let artist: JellyfinArtist
    @State private var fetched: [JellyfinAlbum] = []
    @State private var loaded = false

    private var isOffline: Bool { !NetworkMonitor.shared.isConnected }

    private var albums: [JellyfinAlbum] {
        LibraryOffline.artistAlbums(fetched, artist: artist.name, offline: isOffline) {
            LibraryViewModel.downloadedAlbums(ids: DownloadPaths.newestFirst(DownloadPaths.downloadedAlbumIDs()))
        }
    }

    var body: some View {
        Group {
            if !loaded, !isOffline {
                ProgressView().padding()
            } else if albums.isEmpty {
                Text("No albums found.").font(.plex(.footnote)).foregroundStyle(.secondary).padding()
            } else {
                AlbumGridView(api: api, albums: ArtistPage.ordered(albums), zooms: true, showsYear: true)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle(artist.name)
        // Once, and again if it came back empty (say, after being offline).
        .task(id: NetworkMonitor.shared.isConnected) {
            guard !isOffline, !loaded || fetched.isEmpty else { return }
            api?.albums(forArtist: artist.id) { result in
                DispatchQueue.main.async {
                    if case .success(let items) = result { fetched = DiscGrouping.merge(items) }
                    loaded = true
                }
            }
        }
    }
}
