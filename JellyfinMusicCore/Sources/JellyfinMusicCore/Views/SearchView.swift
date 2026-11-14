// MARK: - SearchView — server-side album and playlist search

import SwiftUI

/// A search bar at the top with a 400 ms debounce. Results appear as
/// a list of album rows with art + title + artist. Each result is
/// navigable to `AlbumDetailView`. Playlists whose name matches come
/// first, in their own section; each opens the playlist's page.
public struct SearchView: View {

    // MARK: Inputs

    private let api: JellyfinAPI?
    private let player: PlayerManager?
    private let downloadManager: DownloadManager?

    @State private var viewModel: SearchViewModel?

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
                // The standard search field. (A TextField in the toolbar's
                // `.principal` slot couldn't be tapped to focus on iOS.)
                content(model)
                    .searchable(
                        text: Binding(get: { model.query }, set: { model.query = $0 }),
                        placement: Self.searchPlacement,
                        prompt: "Albums, artists or playlists"
                    )
                    .autocorrectionDisabled()
                    // Downloads shown: back online, ask the server again.
                    .onChange(of: NetworkMonitor.shared.isConnected) { _, connected in
                        model.networkChanged(online: connected)
                    }
            } else {
                // No API available yet.
                VStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 44.scaled))
                        .foregroundStyle(.secondary)
                    Text("No server connected")
                        .font(.plex(.caption))
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle("Search")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    /// Always visible under the title on iPhone; in the toolbar on Mac.
    private static var searchPlacement: SearchFieldPlacement {
        #if os(iOS)
        .navigationBarDrawer(displayMode: .always)
        #else
        .automatic
        #endif
    }

    // MARK: - Content

    @ViewBuilder
    private func content(_ model: SearchViewModel) -> some View {
        List {
            if model.isLoading {
                HStack {
                    ProgressView()
                    Text("Searching…")
                        .foregroundStyle(.secondary)
                }
                .listRowBackground(Color.clear)
            } else if model.isOfflineResults {
                offlineResults(model)
            } else if model.results.isEmpty, model.playlistResults.isEmpty, model.hasSearched {
                noResultsRow(searchTerm: model.query)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            } else if !model.hasSearched, !model.history.isEmpty {
                historySection(model)
            } else if !model.hasSearched {
                hintRow
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            } else {
                if !model.playlistResults.isEmpty {
                    Section("Playlists (\(model.playlistResults.count))") {
                        ForEach(model.playlistResults) { playlist in
                            playlistRow(playlist)
                                .listRowBackground(Color.clear)
                        }
                    }
                }
                if !model.results.isEmpty {
                    Section(Self.albumsHeader(albums: model.results.count,
                                              playlists: model.playlistResults.count)) {
                        ForEach(model.results) { album in
                            resultRow(album: album, model: model)
                                .listRowBackground(Color.clear)
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .readableWidthOnMac()
    }

    @ViewBuilder
    private func resultRow(album: JellyfinAlbum, model: SearchViewModel) -> some View {
        NavigationLink(value: album) {
            HStack(spacing: 12.scaled) {
                AlbumArtView(
                    request: api?.imageURLRequest(for: album.id, size: 100),
                    size: 56.scaled, cornerRadius: 4.scaled
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(album.displayName)
                        .font(.plex(.body))
                        .lineLimit(1)
                    Text(album.artistName)
                        .font(.plex(.caption))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    if let year = album.productionYear {
                        Text(String(year))
                            .font(.plex(.caption2))
                            .foregroundStyle(.tertiary)
                    }
                }
                Spacer()
                if isDownloaded(album) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.plex(.caption))
                        .foregroundStyle(.green)
                }
            }
        }
    }

    /// The albums' section title: "Results (3)" on their own, "Albums (3)"
    /// under a Playlists section.
    static func albumsHeader(albums: Int, playlists: Int) -> String {
        playlists == 0 ? "Results (\(albums))" : "Albums (\(albums))"
    }

    /// What the offline results say when nothing matches.
    static func noDownloadsMessage(_ query: String) -> String {
        "No downloaded albums or playlists match “\(query)”."
    }

    /// As a row of Library ▸ Playlists; opens the playlist's page.
    private func playlistRow(_ playlist: JellyfinPlaylist) -> some View {
        NavigationLink(value: playlist) {
            HStack(spacing: 12.scaled) {
                PlaylistArtView(api: api, playlist: playlist, size: 56.scaled, cornerRadius: 4.scaled, requestSize: 100)
                VStack(alignment: .leading, spacing: 2) {
                    Text(playlist.name)
                        .font(.plex(.body))
                        .lineLimit(1)
                    Text(playlist.summary)
                        .font(.plex(.caption))
                        .foregroundStyle(Color.albumArtistText)
                        .lineLimit(1)
                }
                Spacer()
            }
        }
        .accessibilityIdentifier("SearchView.playlist.\(playlist.id)")
    }

    @ViewBuilder
    private func historySection(_ model: SearchViewModel) -> some View {
        Section {
            ForEach(model.history, id: \.self) { term in
                Button {
                    model.useHistory(term)
                } label: {
                    Label(term, systemImage: "clock.arrow.circlepath")
                        .foregroundStyle(.primary)
                }
                .listRowBackground(Color.clear)
            }
        } header: {
            HStack {
                Text("Recent searches")
                Spacer()
                Button("Clear") { model.clearHistory() }
                    .textCase(nil)
                    .accessibilityIdentifier("SearchView.clearHistory")
            }
        }
    }

    /// Offline / server unreachable: matching downloads. A playlist opens
    /// its page; an album plays.
    @ViewBuilder
    private func offlineResults(_ model: SearchViewModel) -> some View {
        let playlists = model.localPlaylistResults
        Section {
            if model.localResults.isEmpty, playlists.isEmpty {
                Text(Self.noDownloadsMessage(model.query))
                    .foregroundStyle(.secondary)
                    .listRowBackground(Color.clear)
            }
            if playlists.isEmpty { offlineAlbumRows(model) }
        } header: {
            Label("Can't reach the server — searching downloads", systemImage: "wifi.slash")
                .textCase(nil)
        }
        if !playlists.isEmpty {
            Section("Playlists (\(playlists.count))") {
                ForEach(playlists) { playlist in
                    playlistRow(playlist)
                        .listRowBackground(Color.clear)
                }
            }
            if !model.localResults.isEmpty {
                Section("Albums (\(model.localResults.count))") { offlineAlbumRows(model) }
            }
        }
    }

    @ViewBuilder
    private func offlineAlbumRows(_ model: SearchViewModel) -> some View {
            ForEach(model.localResults) { album in
                Button {
                    player?.playLocalDownloadedAlbum(albumID: album.id, albumTitle: album.title,
                                                     artistName: album.artist)
                    player?.isNowPlayingPresented = true
                } label: {
                    HStack(spacing: 12.scaled) {
                        AlbumArtView(request: api?.imageURLRequest(for: album.id, size: 100),
                                     size: 56.scaled, cornerRadius: 4.scaled)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(album.title.isEmpty ? "Album" : album.title).lineLimit(1)
                            Text(album.artist).font(.plex(.caption)).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        Image(systemName: "play.fill").foregroundStyle(Color.appBlue)
                    }
                }
                .buttonStyle(.plain)
                .listRowBackground(Color.clear)
            }
    }

    @ViewBuilder
    private func noResultsRow(searchTerm: String) -> some View {
        ContentUnavailableView(
            "No results",
            systemImage: "magnifyingglass",
            description: Text(Self.noResultsMessage(searchTerm))
        )
        // Full row width, so it's centred on the Mac too.
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var hintRow: some View {
        ContentUnavailableView(
            "Search your library",
            systemImage: "magnifyingglass",
            description: Text("Type an album, artist or playlist name.")
        )
        // Full row width, so it's centred on the Mac too.
        .frame(maxWidth: .infinity)
    }

    // MARK: - Helpers

    static func noResultsMessage(_ searchTerm: String) -> String {
        "No albums or playlists found for “\(searchTerm)”."
    }

    private func isDownloaded(_ album: JellyfinAlbum) -> Bool {
        downloadManager?.isDownloaded(album.id) ?? false
            || DownloadPaths.exists(albumID: album.id)
    }
}
