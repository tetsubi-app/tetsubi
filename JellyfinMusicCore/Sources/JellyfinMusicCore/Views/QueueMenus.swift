// MARK: - Queue menus — Play Next / Add to Queue everywhere, and the Play prompt

import SwiftUI
import Observation

// MARK: - Actions

/// Play Next and Add to Queue for albums, playlists and songs, with the
/// short notice ("Playing next" / "Added to queue", or why nothing was
/// added). Used by every ⋯ menu and long-press / right-click menu. Allowed
/// in Maniac Mode. The Kotlin side's `QueueActions` uses the same words.
@MainActor
public enum QueueActions {

    public enum Placement: Sendable {
        case playNext, addToQueue
    }

    // Wording (shared with Android/Linux).
    nonisolated static let playingNext = "Playing next"
    nonisolated static let addedToQueue = "Added to queue"
    nonisolated static let albumNotQueued = "Couldn't add this album. Check the connection, or download it first."
    nonisolated static let songNotQueued = "Couldn't add this song. Check the connection, or download it first."
    nonisolated static let playlistNotLoaded = "Couldn't load this playlist. Check the connection, then try again."

    private static var player: PlayerManager? { AppSession.shared.player }

    nonisolated static func confirmation(_ how: Placement) -> String {
        switch how {
        case .playNext: playingNext
        case .addToQueue: addedToQueue
        }
    }

    private static func notify(_ added: Bool, _ how: Placement, failure: String) {
        PlaylistEvents.shared.show(added ? confirmation(how) : failure, isError: !added)
    }

    /// An album (every disc). Downloaded albums are queued from disk.
    public static func queue(album: JellyfinAlbum, _ how: Placement) {
        guard let player else { return }
        let done: (Bool) -> Void = { notify($0, how, failure: albumNotQueued) }
        switch how {
        case .playNext: player.playNext(album: album, discs: album.allDiscs, completion: done)
        case .addToQueue: player.addToQueue(album: album, discs: album.allDiscs, completion: done)
        }
    }

    /// A downloaded album (Downloads, offline search), from disk.
    public static func queue(downloadedAlbumIDs ids: [String], sourceId: String, title: String, artist: String,
                             _ how: Placement) {
        guard let player else { return }
        let added = switch how {
        case .playNext: player.playNext(downloadedAlbumIDs: ids, sourceId: sourceId, albumTitle: title,
                                        artistName: artist)
        case .addToQueue: player.addToQueue(downloadedAlbumIDs: ids, sourceId: sourceId, albumTitle: title,
                                            artistName: artist)
        }
        notify(added, how, failure: albumNotQueued)
    }

    /// Songs from `album` (a track row's menu). Offline only downloaded ones.
    public static func queue(tracks: [JellyfinTrack], in album: JellyfinAlbum, _ how: Placement) {
        guard let player else { return }
        let added = switch how {
        case .playNext: player.playNext(tracks: tracks, in: album)
        case .addToQueue: player.addToQueue(tracks: tracks, in: album)
        }
        notify(added, how, failure: songNotQueued)
    }

    /// A playlist's rows (all of them, or `entries` when already loaded).
    /// Not loaded yet: fetched from the server, or offline from its
    /// download. Offline only the songs on this device are queued.
    public static func queue(playlist: JellyfinPlaylist, entries: [PlaylistEntry]? = nil, _ how: Placement) {
        guard let player else { return }
        if let entries {
            queueLoaded(playlist, entries, how, player: player)
            return
        }
        let online = NetworkMonitor.shared.isConnected
        let download = AppSession.shared.downloadManager?.playlists.manifests[playlist.id]?.playlistEntries
        guard online, let api = AppSession.shared.api else {
            if let download, !download.isEmpty {
                queueLoaded(playlist, download, how, player: player)
            } else {
                PlaylistEvents.shared.show(PlaylistPlayRefusal.nothingDownloaded.message, isError: true)
            }
            return
        }
        api.playlistTracks(playlist.id) { result in
            DispatchQueue.main.async {
                switch result {
                case .success(let entries) where !entries.isEmpty:
                    queueLoaded(playlist, entries, how, player: player)
                case .success:
                    PlaylistEvents.shared.show("This playlist is empty.", isError: true)
                case .failure:
                    if let download, !download.isEmpty {
                        queueLoaded(playlist, download, how, player: player)
                    } else {
                        PlaylistEvents.shared.show(playlistNotLoaded, isError: true)
                    }
                }
            }
        }
    }

    /// Play a downloaded album from disk (Downloads, offline search),
    /// asking first when more is queued; opens Now Playing.
    public static func playDownloaded(id: String, title: String, artist: String) {
        guard let player else { return }
        QueuePrompt.shared.play(title.isEmpty ? "Album" : title, replace: {
            player.playLocalDownloadedAlbum(albumID: id, format: .flac, albumTitle: title, artistName: artist)
            player.isNowPlayingPresented = true
        }, place: { how in
            queue(downloadedAlbumIDs: [id], sourceId: id, title: title, artist: artist, how)
        })
    }

    private static func queueLoaded(_ playlist: JellyfinPlaylist, _ entries: [PlaylistEntry], _ how: Placement,
                                    player: PlayerManager) {
        guard !entries.isEmpty else { return }
        let refusal = switch how {
        case .playNext: player.playNext(playlist: playlist, tracks: entries)
        case .addToQueue: player.addToQueue(playlist: playlist, tracks: entries)
        }
        if let refusal {
            PlaylistEvents.shared.show(refusal.message, isError: true)
        } else {
            PlaylistEvents.shared.show(confirmation(how))
        }
    }
}

// MARK: - Menu items

/// "Play Next" and "Add to Queue", for a ⋯ menu or a context menu.
struct QueueMenuItems: View {
    let action: (QueueActions.Placement) -> Void

    var body: some View {
        Button {
            action(.playNext)
        } label: {
            Label("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward")
        }
        Button {
            action(.addToQueue)
        } label: {
            Label("Add to Queue", systemImage: "text.line.last.and.arrowtriangle.forward")
        }
    }
}

extension View {
    /// Long press (iOS) / right-click (Mac): Play Next, Add to Queue for an album.
    func albumQueueMenu(_ album: JellyfinAlbum) -> some View {
        contextMenu {
            QueueMenuItems { QueueActions.queue(album: album, $0) }
        }
    }

    /// Long press / right-click: Play Next, Add to Queue for a downloaded
    /// album (Downloads, offline search), from disk.
    func downloadedAlbumQueueMenu(id: String, title: String, artist: String) -> some View {
        contextMenu {
            QueueMenuItems {
                QueueActions.queue(downloadedAlbumIDs: [id], sourceId: id, title: title, artist: artist, $0)
            }
        }
    }

    /// Long press / right-click: Play Next, Add to Queue for a playlist.
    func playlistQueueMenu(_ playlist: JellyfinPlaylist) -> some View {
        contextMenu {
            QueueMenuItems { QueueActions.queue(playlist: playlist, $0) }
        }
    }
}

// MARK: - The Play prompt

/// Pressing Play on an album or playlist, or a song in one, while the
/// queue holds more than the current album asks: Replace queue, Play Next,
/// Add to Queue or Cancel (`PlayQueue.needsPlayPrompt`). With nothing else
/// queued it replaces at once, as before. CarPlay, Siri and the widget
/// never ask (they call the player directly).
@MainActor
@Observable
public final class QueuePrompt {

    public static let shared = QueuePrompt()

    public struct Request: Identifiable {
        public let id = UUID()
        /// What would play: an album's, a playlist's or a song's name.
        public let name: String
        let replace: () -> Void
        let place: (QueueActions.Placement) -> Void
    }

    public var request: Request?

    init() {}

    /// Runs `replace` now, or asks first when the queue holds more than
    /// the current album or playlist. `place` queues the same music instead.
    public func play(_ name: String, replace: @escaping () -> Void,
                     place: @escaping (QueueActions.Placement) -> Void) {
        guard AppSession.shared.player?.needsPlayPrompt == true else {
            replace()
            return
        }
        request = Request(name: name, replace: replace, place: place)
    }

    // Wording (shared with Android/Linux).
    nonisolated static func title(_ name: String) -> String { "Play \u{201C}\(name)\u{201D}" }
    nonisolated static let message = "There's other music in the queue."
    nonisolated static let replaceButton = "Replace queue"
}

private struct QueuePromptHost: ViewModifier {
    @State private var prompt = QueuePrompt.shared

    func body(content: Content) -> some View {
        let request = prompt.request
        content.confirmationDialog(
            QueuePrompt.title(request?.name ?? ""),
            isPresented: Binding(get: { prompt.request != nil },
                                 set: { if !$0 { prompt.request = nil } }),
            titleVisibility: .visible,
            presenting: request
        ) { request in
            Button(QueuePrompt.replaceButton) { request.replace() }
            Button("Play Next") { request.place(.playNext) }
            Button("Add to Queue") { request.place(.addToQueue) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text(QueuePrompt.message)
        }
    }
}

extension View {
    /// Shows the Play prompt (`QueuePrompt`). Once, at the app's root.
    public func queuePromptHost() -> some View {
        modifier(QueuePromptHost())
    }
}
