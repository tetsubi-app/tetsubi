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
    var cornerRadius: CGFloat = Radii.card
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
        return AlbumArtView(request: request, size: size, cornerRadius: cornerRadius,
                            placeholderColor: TetsubiColors.art)
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
                    Banner("Couldn't load your playlists. \(error)", actionTitle: "Retry") { load() }
                    Spacer(minLength: 0)
                }
            } else if model.playlists.isEmpty {
                if model.hasLoaded {
                    EmptyState(model.isOffline ? "No downloaded playlists yet" : "No playlists yet",
                               message: model.isOffline ? nil : "Add songs from an album's track menu.",
                               systemImage: "music.note.list")
                        .frame(maxHeight: .infinity, alignment: .top)
                } else if model.isLoading {
                    LoadingSpinner().frame(maxHeight: .infinity, alignment: .top)
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

    private var grid: some View {
        CardGrid { metrics in
            ForEach(model.playlists) { playlist in
                PlaylistCardLink(api: api, playlist: playlist, width: metrics.cardWidth)
            }
        }
    }

    private var list: some View {
        ListGrid {
            ForEach(model.playlists) { playlist in
                PlaylistListRow(api: api, playlist: playlist)
            }
        }
    }
}

/// A playlist as a list row: its picture, name and "12 songs · 1 h 12 min".
struct PlaylistListRow: View {
    let api: JellyfinAPI?
    let playlist: JellyfinPlaylist
    var horizontalPadding: CGFloat?

    var body: some View {
        NavigationLink(value: playlist) {
            ListRow(title: playlist.name, subtitle: playlist.summary, horizontalPadding: horizontalPadding, art: {
                PlaylistArtView(api: api, playlist: playlist, size: 40.scaled, cornerRadius: Radii.thumb,
                                requestSize: 100)
            })
        }
        .buttonStyle(.plain)
        .playlistQueueMenu(playlist)
        .accessibilityIdentifier("PlaylistRow.\(playlist.id)")
    }
}
