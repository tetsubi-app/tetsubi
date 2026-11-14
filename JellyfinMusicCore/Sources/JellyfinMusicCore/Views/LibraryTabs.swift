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
/// Width kept at the right of the Library tab row for the options button.
let libraryTabAccessoryWidth: CGFloat = 36

struct LibraryTabBar<Accessory: View>: View {
    @Binding var selection: LibraryTab
    /// Right-hand control (the Albums and Playlists options menus). Its
    /// width is always kept, so the tab labels don't move when it's hidden.
    @ViewBuilder var accessory: () -> Accessory

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
                    selection = tab
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
                }
                .buttonStyle(.plain)
            }
            accessory()
                .frame(width: libraryTabAccessoryWidth.scaled)
                .padding(.bottom, 9.scaled)   // line up with the labels, above the underline
        }
    }
}

/// Square-art album grid shared by the Favourites tab and artist pages.
struct AlbumGridView: View {
    let api: JellyfinAPI?
    let albums: [JellyfinAlbum]
    /// Shown centred under the grid (Random Albums' "Shake it").
    var footer: String? = nil
    /// Lines for each album title (Random Albums uses 1 so four rows fit).
    var titleLines: Int = 2

    /// Same layout as Library ▸ Albums: fixed 120pt covers, 3 across on a
    /// phone (`LibraryView.gridLayout`), downloaded tick on the cover.
    var body: some View {
        GeometryReader { geo in
            let edge = LibraryView.gridEdgePadding
            let layout = LibraryView.gridLayout(width: geo.size.width - 2 * edge,
                                                scale: UIScale.shared.factor)
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: layout.spacing, alignment: .top),
                                         count: layout.columns),
                          spacing: 16.scaled) {
                    ForEach(albums) { album in
                        NavigationLink(value: album) {
                            VStack(spacing: 4.scaled) {
                                AlbumArtView(
                                    request: api?.imageURLRequest(for: album.id, size: 300),
                                    size: LibraryView.scaledGridArtSize, cornerRadius: 8.scaled
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
                                    .lineLimit(titleLines)
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
                    }
                }
                .padding(.horizontal, edge)
                .padding(.vertical)
                if let footer {
                    Text(footer)
                        .font(.plex(.footnote))
                        .foregroundStyle(Color.albumYearText)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.bottom, 24)
                }
            }
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

/// Favourite albums, most recently favourited first; offline, the
/// downloaded ones only.
struct FavouritesTabView: View {
    let api: JellyfinAPI?
    @State private var all: [JellyfinAlbum] = []
    @State private var loaded = false

    private var albums: [JellyfinAlbum] {
        LibraryOffline.favourites(all, offline: !NetworkMonitor.shared.isConnected)
    }

    var body: some View {
        Group {
            if !loaded {
                ProgressView().padding()
            } else if albums.isEmpty {
                Text("No favourite albums yet.\nTap the heart on an album to add one.")
                    .font(.plex(.footnote))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding()
            } else {
                AlbumGridView(api: api, albums: albums)
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
struct ArtistsTabView: View {
    let api: JellyfinAPI?
    @State private var artists: [JellyfinArtist] = []
    @State private var loaded = false

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

/// One artist's albums; offline, their downloaded albums only.
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
                AlbumGridView(api: api, albums: albums)
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
