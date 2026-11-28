// MARK: - Library tabs — the album, artist and favourite collections (docs/redesign.md sections 3 to 5)

import SwiftUI

/// The four top-level Library sections.
public enum LibraryTab: String, CaseIterable, Identifiable {
    case albums = "Albums"
    case artists = "Artists"
    case favourites = "Favourites"
    case playlists = "Playlists"
    public var id: String { rawValue }

    public var index: Int { Self.allCases.firstIndex(of: self) ?? 0 }
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


// MARK: - Albums

/// Albums as the app's grid or list, with the A to Z strip when `indexKey` is
/// set (the alphabetical sorts). Used by Library's Albums and Favourites tabs
/// and Home's full lists. `resetTrigger` changing scrolls back to the top (a new sort).
struct AlbumCollection: View {
    let api: JellyfinAPI?
    let albums: [JellyfinAlbum]
    var mode: LibraryDisplayMode = .grid
    var indexKey: ((JellyfinAlbum) -> String)?
    var isDownloaded: (JellyfinAlbum) -> Bool = LibraryOffline.isDownloaded
    /// Runs as each album appears (Random's next page).
    var onAppear: (JellyfinAlbum) -> Void = { _ in }
    /// The release year under each title instead of the artist (an artist's page).
    var showsYear = false
    /// Centred under the grid, scrolling with it ("Shake it").
    var footer: String?
    var resetTrigger: String = ""

    var body: some View {
        let firstIDs = indexKey.map { key in AlphaIndex.firstIDs(albums, key: key, id: \.id) } ?? [:]
        let strip = indexKey != nil ? AlphaIndexBar.width : 0
        ScrollViewReader { proxy in
            ZStack(alignment: .trailing) {
                switch mode {
                case .grid:
                    CardGrid(endInset: strip, footer: footer.map { text in
                        AnyView(Text(text).font(TetsubiType.body).foregroundStyle(TetsubiColors.textSecondary))
                    }) { metrics in
                        ForEach(albums) { album in
                            AlbumCardLink(api: api, album: album, width: metrics.cardWidth,
                                          isDownloaded: isDownloaded(album), showsYear: showsYear)
                                .id(album.id)
                                .onAppear { onAppear(album) }
                        }
                    }
                case .list:
                    ListGrid(endInset: strip) {
                        ForEach(albums) { album in
                            AlbumListRow(api: api, album: album, isDownloaded: isDownloaded(album))
                                .id(album.id)
                                .onAppear { onAppear(album) }
                        }
                    }
                }
                if indexKey != nil {
                    AlphaIndexBar(available: Set(firstIDs.keys)) { label in
                        if let id = AlphaIndex.target(for: label, in: firstIDs) { proxy.scrollTo(id, anchor: .top) }
                    }
                }
            }
            // A new sort starts at the top. Otherwise the list keeps the album that
            // was at the top in view, wherever it now sorts.
            .onChange(of: resetTrigger) { _, _ in
                if let first = albums.first?.id { proxy.scrollTo(first, anchor: .top) }
            }
        }
    }
}

/// One album as a list row: small cover, title, artist, year at the right, the downloaded tick.
struct AlbumListRow: View {
    let api: JellyfinAPI?
    let album: JellyfinAlbum
    let isDownloaded: Bool

    var body: some View {
        NavigationLink(value: album) {
            ListRow(title: album.displayName, subtitle: album.artistName,
                    trailingMeta: album.productionYear.map { String($0) }, art: {
                AlbumArtView(request: coverRequest(api: api, id: album.id, size: 100), size: 40.scaled,
                             cornerRadius: Radii.thumb, placeholderColor: TetsubiColors.art)
            }, trailing: {
                if isDownloaded {
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 20.scaled))
                        .foregroundStyle(TetsubiColors.green)
                }
            })
        }
        .buttonStyle(.plain)
        .albumQueueMenu(album)
    }
}

// MARK: - Favourites

/// Favourite albums as a grid or list, sorted and filtered by
/// `FavouritesOptions` (most recently favourited first by default), with
/// the A to Z strip for the Name and Artist sorts. Offline, the downloaded
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
                LoadingSpinner().frame(maxHeight: .infinity, alignment: .top)
            } else if albums.isEmpty, options.filter == .downloaded, !isOffline, !all.isEmpty {
                EmptyState("No downloaded favourite albums yet", systemImage: "star",
                           actionTitle: "Show all favourites") { options.filter = .all }
                    .frame(maxHeight: .infinity, alignment: .top)
            } else if albums.isEmpty {
                EmptyState("No favourite albums yet", message: "Tap the star on an album to add one.",
                           systemImage: "star")
                    .frame(maxHeight: .infinity, alignment: .top)
            } else {
                AlbumCollection(api: api, albums: albums, mode: options.displayMode, indexKey: options.indexKey,
                                resetTrigger: options.sortOption.rawValue)
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

extension LibraryView {
    /// Sort, Filter, Grid / List and card size for Favourites (the Library tab and Home's full list share them).
    static func favouritesControls(_ options: FavouritesOptions) -> PageControls {
        PageControls(
            sort: controlMenu("Sort", options: FavouritesSortOption.allCases, selected: options.sortOption,
                              label: \.rawValue) { options.sortOption = $0 },
            filter: controlMenu("Filter", options: LibraryFilter.allCases, selected: options.filter,
                                label: \.rawValue) { options.filter = $0 },
            view: options.displayMode, onView: { options.displayMode = $0 },
            density: options.displayMode == .grid)
    }
}

// MARK: - Artists

/// All album artists with the A to Z strip; tap one to see their albums.
/// A grid of round pictures (the album cards' size) or rows.
struct ArtistsTabView: View {
    let api: JellyfinAPI?
    var mode: LibraryDisplayMode = .grid
    @State private var artists: [JellyfinArtist] = []
    @State private var loaded = false

    var body: some View {
        Group {
            if !loaded {
                LoadingSpinner().frame(maxHeight: .infinity, alignment: .top)
            } else if artists.isEmpty {
                EmptyState("No artists found", systemImage: "music.mic").frame(maxHeight: .infinity, alignment: .top)
            } else {
                let firstIDs = AlphaIndex.firstIDs(artists, key: \.name, id: \.id)
                ScrollViewReader { proxy in
                    ZStack(alignment: .trailing) {
                        switch mode {
                        case .grid:
                            CardGrid(endInset: AlphaIndexBar.width) { metrics in
                                ForEach(artists) { artist in
                                    ArtistCardLink(api: api, artist: artist, width: metrics.cardWidth).id(artist.id)
                                }
                            }
                        case .list:
                            ListGrid(endInset: AlphaIndexBar.width) {
                                ForEach(artists) { artist in
                                    NavigationLink(value: artist) {
                                        ListRow(title: artist.name, artCircle: true, art: {
                                            AlbumArtView(request: api?.imageURLRequest(for: artist.id, size: 100),
                                                         size: 40.scaled, cornerRadius: 20.scaled,
                                                         placeholderColor: TetsubiColors.art)
                                        })
                                    }
                                    .buttonStyle(.plain)
                                    .id(artist.id)
                                }
                            }
                        }
                        AlphaIndexBar(available: Set(firstIDs.keys)) { label in
                            if let id = AlphaIndex.target(for: label, in: firstIDs) { proxy.scrollTo(id, anchor: .top) }
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Again when the connection comes back, if it came back empty.
        .task(id: NetworkMonitor.shared.isConnected) { load() }
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

// MARK: - Artist page

/// One artist's albums, newest first, each with its year; offline, their
/// downloaded albums only. A detail page (`DetailPage`): back control, then the
/// round picture and name with "About" (bio, genres, attribution) in the info
/// pane, the album grid on the right (stacked on compact). There is no Play
/// all / Shuffle: nothing plays "an artist".
struct ArtistAlbumsView: View {
    let api: JellyfinAPI?
    let artist: JellyfinArtist
    @State private var fetched: [JellyfinAlbum] = []
    @State private var loaded = false
    @State private var artistInfo: ArtistInfo?

    @Environment(\.dismiss) private var dismiss

    private var isOffline: Bool { !NetworkMonitor.shared.isConnected }

    private var albums: [JellyfinAlbum] {
        LibraryOffline.artistAlbums(fetched, artist: artist.name, offline: isOffline) {
            LibraryViewModel.downloadedAlbums(ids: DownloadPaths.newestFirst(DownloadPaths.downloadedAlbumIDs()))
        }
    }

    var body: some View {
        let albums = ArtistPage.ordered(albums)
        VStack(spacing: 0) {
            PageHeader(nil, onBack: { dismiss() }) { PageControls(density: true) }
            DetailPage { metrics in
                // Smaller than an album's cover and a lighter placeholder: many
                // artists have no picture, and a big grey disc dominated the page.
                let artSize = min(metrics.artSize, metrics.twoPane ? 240 : 200)
                AlbumArtView(request: api?.imageURLRequest(for: artist.id, size: 600), size: artSize,
                             cornerRadius: artSize / 2, placeholderColor: TetsubiColors.surface)
                DetailTitleBlock(metrics: metrics, title: artist.name,
                                 meta: loaded || isOffline ? Self.albumCount(albums.count) : nil) { EmptyView() }
            } list: { _ in
                if !loaded, !isOffline {
                    LoadingSpinner()
                } else if albums.isEmpty {
                    EmptyState("No albums found", systemImage: "music.note.list")
                } else {
                    CardGridContent { metrics in
                        ForEach(albums) { album in
                            AlbumCardLink(api: api, album: album, width: metrics.cardWidth,
                                          isDownloaded: LibraryOffline.isDownloaded(album), showsYear: true)
                        }
                    }
                    .padding(.vertical, Spacing.s)
                }
            } after: {
                if !isOffline, let info = artistInfo {
                    ArtistAboutView(artistName: artist.name, info: info)
                }
            }
        }
        .ownsItsHeader(isDetail: true)
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
        .task(id: NetworkMonitor.shared.isConnected) { await loadAbout() }
    }

    /// MusicBrainz and Wikipedia, using the server's MusicBrainz ID when it has one.
    private func loadAbout() async {
        guard !isOffline, artistInfo == nil, !artist.name.isEmpty, let api else { return }
        let mbid = await api.musicBrainzArtistID(artistId: artist.id)
        artistInfo = await ArtistInfoClient.shared.info(name: artist.name, musicBrainzID: mbid)
    }

    /// "12 albums".
    static func albumCount(_ count: Int) -> String? {
        count == 0 ? nil : count == 1 ? "1 album" : "\(count) albums"
    }
}
