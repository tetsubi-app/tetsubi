// MARK: - PlaylistsTabView — Library ▸ Playlists, and the playlist art and card

import SwiftUI

// MARK: - Art

/// Which picture stands for a playlist: the server's own when it has one
/// (its collage), otherwise the first track's album art, otherwise none
/// (the placeholder).
@MainActor
enum PlaylistArt {
    nonisolated static func itemId(for playlist: JellyfinPlaylist, firstEntry: PlaylistEntry?) -> String? {
        playlist.hasArt ? playlist.id : firstEntry?.artItemId
    }

    /// First-track art already looked up for playlists without a picture,
    /// by playlist ID, so lists don't ask again as they scroll.
    private static var firstTrackArt: [String: String] = [:]

    static func remembered(for playlist: JellyfinPlaylist) -> String? {
        firstTrackArt[playlist.id]
    }

    static func remember(firstEntry: PlaylistEntry?, of playlist: JellyfinPlaylist) {
        firstTrackArt[playlist.id] = firstEntry?.artItemId
    }
}

/// A playlist's picture (see `PlaylistArt`). A playlist without one of its
/// own has its tracks looked up for the first track's art, unless the
/// caller already has them (`firstEntry`).
struct PlaylistArtView: View {
    let api: JellyfinAPI?
    let playlist: JellyfinPlaylist
    let size: CGFloat
    var cornerRadius: CGFloat = 8
    var requestSize: Int = 300
    var firstEntry: PlaylistEntry? = nil

    @State private var lookedUp: String?

    private var itemId: String? {
        PlaylistArt.itemId(for: playlist, firstEntry: firstEntry)
            ?? lookedUp ?? PlaylistArt.remembered(for: playlist)
    }

    /// Offline, a downloaded playlist shows its saved picture.
    private var request: URLRequest? {
        if !NetworkMonitor.shared.isConnected, let file = PlaylistDownloadPaths.savedCover(playlistID: playlist.id) {
            return URLRequest(url: file)
        }
        return itemId.flatMap { api?.imageURLRequest(for: $0, size: requestSize) }
    }

    var body: some View {
        let request = request
        return AlbumArtView(request: request, size: size, cornerRadius: cornerRadius)
            // Re-made when the picture changes, so the new one loads.
            .id(request?.url)
            // Again when its song count changes: an empty playlist got its first song.
            .task(id: playlist) { lookUpFirstTrack() }
    }

    private func lookUpFirstTrack() {
        guard itemId == nil, playlist.songCount > 0, NetworkMonitor.shared.isConnected, let api else { return }
        api.playlistTracks(playlist.id) { result in
            guard case .success(let entries) = result else { return }
            DispatchQueue.main.async {
                PlaylistArt.remember(firstEntry: entries.first, of: playlist)
                lookedUp = entries.first?.artItemId
            }
        }
    }
}

// MARK: - Card

/// Art, name and "12 songs · 1 h 12 min", for grids (`artSize` 120) and
/// Home's row (140). `artSize` is at scale 1; the card scales it (`UIScale`).
struct PlaylistCardView: View {
    let api: JellyfinAPI?
    let playlist: JellyfinPlaylist
    var artSize: CGFloat = LibraryView.gridArtSize
    /// Home's row: the card is as wide as its art.
    var fixedWidth = false

    var body: some View {
        let art = artSize.scaled
        return VStack(spacing: 4.scaled) {
            PlaylistArtView(api: api, playlist: playlist, size: art, cornerRadius: 8.scaled)
            Text(playlist.name)
                .font(.plex(.caption).bold())
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, alignment: .center)
            Text(playlist.summary)
                .font(.plex(.caption2))
                .foregroundStyle(Color.albumArtistText)
                .lineLimit(1)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, alignment: .center)
        }
        .frame(width: fixedWidth ? art : nil)
    }
}

// MARK: - Tab

/// The server's playlists as a grid or list; tap one for its page.
struct PlaylistsTabView: View {
    let api: JellyfinAPI?
    let model: PlaylistsViewModel

    var body: some View {
        // A ZStack, not a Group: with nothing to show yet a Group is no view
        // at all, so the load below would never start and the Library's
        // header (hung on this view) would vanish with it.
        ZStack {
            if let error = model.errorMessage, !model.isLoading {
                VStack(spacing: 0) {
                    StatusBanner(systemImage: "exclamationmark.triangle",
                                 message: "Couldn't load your playlists. \(error)",
                                 actionTitle: "Retry") { load() }
                    Spacer(minLength: 0)
                }
            } else if model.playlists.isEmpty {
                if model.hasLoaded {
                    Text(model.isOffline
                         ? "No downloaded playlists yet."
                         : "No playlists yet. Add songs from an album's track menu.")
                        .font(.plex(.footnote))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding()
                } else if model.isLoading {
                    ProgressView().padding()
                }
            } else {
                switch model.displayMode {
                case .grid: grid
                case .list: list
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Each time the tab shows, and again when the connection comes back.
        .task(id: NetworkMonitor.shared.isConnected) { load() }
        // And when a playlist was made, changed or deleted.
        .onChange(of: PlaylistEvents.shared.version) { _, _ in load() }
    }

    private func load() {
        model.load(online: NetworkMonitor.shared.isConnected)
    }

    /// Same layout as Library ▸ Albums (`LibraryView.gridLayout`).
    private var grid: some View {
        GeometryReader { geo in
            let edge = LibraryView.gridEdgePadding
            let layout = LibraryView.gridLayout(width: geo.size.width - 2 * edge,
                                                scale: UIScale.shared.factor)
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: layout.spacing, alignment: .top),
                                         count: layout.columns),
                          spacing: 16.scaled) {
                    ForEach(model.playlists) { playlist in
                        NavigationLink(value: playlist) {
                            PlaylistCardView(api: api, playlist: playlist)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, edge)
                .padding(.vertical)
            }
        }
    }

    private var list: some View {
        List(model.playlists) { playlist in
            NavigationLink(value: playlist) {
                HStack(spacing: 12.scaled) {
                    PlaylistArtView(api: api, playlist: playlist, size: 60.scaled, cornerRadius: 4.scaled, requestSize: 100)
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
            .listRowBackground(Color.clear)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }
}

// MARK: - Home row

/// Home's horizontal row of playlists.
struct HomePlaylistsRow: View {
    let api: JellyfinAPI?
    let playlists: [JellyfinPlaylist]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 12.scaled) {
                ForEach(playlists) { playlist in
                    NavigationLink(value: playlist) {
                        PlaylistCardView(api: api, playlist: playlist, artSize: 140, fixedWidth: true)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 8)
        }
    }
}
