// MARK: - SearchView — server-side album and playlist search

import SwiftUI

/// A "Search" header, a pill field with a 400 ms debounce, filter chips (All,
/// Albums, Artists, Playlists), then the results in the app's grid: albums and
/// artists as cards, playlists as rows (two columns when wide). Each album is
/// navigable to `AlbumDetailView`, each playlist and artist to its page.
/// Recent searches show while the box is empty.
public struct SearchView: View {

    // MARK: Inputs

    private let api: JellyfinAPI?
    private let player: PlayerManager?
    private let downloadManager: DownloadManager?

    @State private var viewModel: SearchViewModel?
    @State private var filter: SearchFilter = .all
    @FocusState private var fieldFocused: Bool

    @Environment(\.windowWidthClass) private var windowClass

    // MARK: Init

    public init(
        api: JellyfinAPI? = nil,
        player: PlayerManager? = nil,
        downloadManager: DownloadManager? = nil
    ) {
        self.api           = api
        self.player        = player
        self.downloadManager = downloadManager
        if let api = api {
            // Read downloads from disk: the manager may not exist yet when
            // this view model is created.
            _viewModel = State(initialValue: SearchViewModel(api: api, downloadedAlbums: {
                DownloadPaths.downloadedAlbumIDs().sorted().map { id in
                    let info = DownloadManager.storedAlbumInfo(for: id)
                    // Track titles in play order; older downloads only have them by file.
                    let titles = DownloadedTitles.titles(for: id).sorted { $0.key < $1.key }.map(\.value)
                    return LocalAlbum(id: id, title: info?.title ?? "", artist: info?.artist ?? "",
                                      trackTitles: titles.isEmpty
                                          ? DownloadedTitles.titlesByFile(for: id).values.sorted()
                                          : titles)
                }
            }, downloadedPlaylists: { PlaylistDownloadPaths.offlinePlaylists() }))
        }
    }

    // MARK: Body

    public var body: some View {
        Group {
            if let model = viewModel {
                VStack(spacing: 0) {
                    PageHeader("Search")
                    searchField(model)
                    if !model.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        FilterChips(SearchFilter.allCases, selected: filter, label: \.rawValue) { filter = $0 }
                            .padding(.top, Spacing.m)
                    }
                    content(model)
                }
                // Downloads shown: back online, ask the server again.
                .onChange(of: NetworkMonitor.shared.isConnected) { _, connected in
                    model.networkChanged(online: connected)
                }
            } else {
                // No API available yet.
                EmptyState("No server connected", systemImage: "magnifyingglass")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .ownsItsHeader()
    }

    // MARK: - Field

    /// The search box: a pill with a magnifying glass, "Albums, artists or
    /// playlists", and an x to empty it.
    private func searchField(_ model: SearchViewModel) -> some View {
        HStack(spacing: Spacing.s) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 16.scaled))
                .foregroundStyle(TetsubiColors.textSecondary)
            TextField("", text: Binding(get: { model.query }, set: { model.query = $0 }),
                      prompt: Text("Albums, artists or playlists").foregroundStyle(TetsubiColors.textTertiary))
                .textFieldStyle(.plain)
                .font(.plex(size: 16))
                .foregroundStyle(TetsubiColors.textPrimary)
                .tint(TetsubiColors.blue)
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                #endif
                .submitLabel(.search)
                .focused($fieldFocused)
                .accessibilityLabel("Search")
                .accessibilityIdentifier("SearchView.field")
            if !model.query.isEmpty {
                Button { model.query = "" } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 18.scaled))
                        .foregroundStyle(TetsubiColors.textSecondary)
                        .frame(width: 32.scaled, height: 32.scaled)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear text")
            }
        }
        .padding(.leading, Spacing.l)
        .padding(.trailing, Spacing.xs)
        .frame(height: 44.scaled)
        .background(TetsubiColors.surface, in: Capsule())
        .padding(.horizontal, windowClass.pageMargin)
    }

    // MARK: - Content

    @ViewBuilder
    private func content(_ model: SearchViewModel) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if model.isLoading {
                    LoadingSpinner()
                } else if model.isOfflineResults {
                    offlineResults(model)
                } else if !model.hasSearched, !model.history.isEmpty {
                    historySection(model)
                } else if !model.hasSearched {
                    EmptyState("Search your library", message: "Type an album, artist or playlist name.",
                               systemImage: "magnifyingglass")
                } else {
                    onlineResults(model)
                }
            }
            .padding(.top, Spacing.s)
            .padding(.bottom, Spacing.xl)
        }
        .scrollDismissesKeyboard(.immediately)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder
    private func onlineResults(_ model: SearchViewModel) -> some View {
        let albums = filter.showsAlbums ? model.results : []
        let artists = filter.showsArtists ? model.artistResults : []
        let playlists = filter.showsPlaylists ? model.playlistResults : []
        if albums.isEmpty, artists.isEmpty, playlists.isEmpty {
            EmptyState("No results", message: Self.noResultsMessage(model.query, filter: filter),
                       systemImage: "magnifyingglass")
        } else {
            let others = [albums.count, artists.count, playlists.count].filter { $0 > 0 }.count - 1
            if !albums.isEmpty {
                SectionHeader(Self.albumsHeader(albums: albums.count, playlists: others))
                CardGridContent { metrics in
                    ForEach(albums) { album in
                        AlbumCardLink(api: api, album: album, width: metrics.cardWidth, isDownloaded: isDownloaded(album))
                    }
                }
            }
            if !artists.isEmpty {
                SectionHeader(Self.artistsHeader(artists.count)).padding(.top, albums.isEmpty ? 0 : Spacing.s)
                CardGridContent { metrics in
                    ForEach(artists) { artist in
                        ArtistCardLink(api: api, artist: artist, width: metrics.cardWidth)
                    }
                }
            }
            playlistSection(playlists, topGap: !albums.isEmpty || !artists.isEmpty)
        }
    }

    /// "Playlists (2)": rows as the Library's playlist list, each opening its page. Nothing when there are none.
    @ViewBuilder
    private func playlistSection(_ playlists: [JellyfinPlaylist], topGap: Bool = false) -> some View {
        if !playlists.isEmpty {
            SectionHeader(Self.playlistsHeader(playlists.count)).padding(.top, topGap ? Spacing.s : 0)
            ListGridContent {
                ForEach(playlists) { playlist in
                    PlaylistListRow(api: api, playlist: playlist)
                        .accessibilityIdentifier("SearchView.playlist.\(playlist.id)")
                }
            }
        }
    }

    @ViewBuilder
    private func historySection(_ model: SearchViewModel) -> some View {
        HStack {
            SectionHeader("Recent searches")
            ActionChip("Clear") { model.clearHistory() }
                .padding(.trailing, windowClass.pageMargin)
                .accessibilityIdentifier("SearchView.clearHistory")
        }
        ForEach(model.history, id: \.self) { term in
            Button { model.useHistory(term) } label: {
                HStack(spacing: Spacing.m) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 18.scaled))
                        .foregroundStyle(TetsubiColors.blue)
                    Text(term).font(TetsubiType.rowTitle).foregroundStyle(TetsubiColors.textPrimary).lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, windowClass.pageMargin)
                .padding(.vertical, Spacing.m)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    /// Offline / server unreachable: matching downloads. A playlist opens
    /// its page; an album plays.
    @ViewBuilder
    private func offlineResults(_ model: SearchViewModel) -> some View {
        let playlists = filter.showsPlaylists ? model.localPlaylistResults : []
        let albums = filter.showsAlbums ? model.localResults : []
        Banner("Can't reach the server. Searching downloads.", kind: .offline)
        if albums.isEmpty, playlists.isEmpty {
            Text(filter == .all ? Self.noDownloadsMessage(model.query)
                                : Self.noResultsMessage(model.query, filter: filter))
                .font(TetsubiType.body)
                .foregroundStyle(TetsubiColors.textSecondary)
                .padding(.horizontal, windowClass.pageMargin)
                .padding(.vertical, Spacing.s)
        }
        playlistSection(playlists)
        if !albums.isEmpty {
            if !playlists.isEmpty {
                SectionHeader(Self.albumsHeader(albums: albums.count, playlists: 1)).padding(.top, Spacing.s)
            }
            ListGridContent {
                ForEach(albums) { album in
                    ListRow(title: album.title.isEmpty ? "Album" : album.title, subtitle: album.artist, onTap: {
                        fieldFocused = false
                        QueueActions.playDownloaded(id: album.id, title: album.title, artist: album.artist)
                    }, art: {
                        AlbumArtView(request: coverRequest(api: api, id: album.id, size: 100), size: 40.scaled,
                                     cornerRadius: Radii.thumb, placeholderColor: TetsubiColors.art)
                    }, trailing: {
                        Image(systemName: "play.fill").font(.system(size: 16.scaled)).foregroundStyle(TetsubiColors.blue)
                    })
                    .downloadedAlbumQueueMenu(id: album.id, title: album.title, artist: album.artist)
                }
            }
        }
    }

    // MARK: - Helpers

    /// The albums' section title: "Results (3)" on their own, "Albums (3)"
    /// beside other sections (`playlists` is how many other sections there are).
    static func albumsHeader(albums: Int, playlists: Int) -> String {
        playlists == 0 ? "Results (\(albums))" : "Albums (\(albums))"
    }

    static func artistsHeader(_ artists: Int) -> String { "Artists (\(artists))" }

    static func playlistsHeader(_ playlists: Int) -> String { "Playlists (\(playlists))" }

    /// What the offline results say when nothing matches.
    static func noDownloadsMessage(_ query: String) -> String {
        "No downloaded albums or playlists match “\(query)”."
    }

    static func noResultsMessage(_ searchTerm: String) -> String {
        "No albums or playlists found for “\(searchTerm)”."
    }

    /// As `noResultsMessage`, for the kind of result a chip narrowed the search to.
    static func noResultsMessage(_ searchTerm: String, filter: SearchFilter) -> String {
        filter == .all ? noResultsMessage(searchTerm) : "No \(filter.rawValue.lowercased()) found for “\(searchTerm)”."
    }

    private func isDownloaded(_ album: JellyfinAlbum) -> Bool {
        downloadManager?.isDownloaded(album.id) ?? false
            || DownloadPaths.exists(albumID: album.id)
    }
}
