// MARK: - AppSession — the one server connection, player and download manager

import Foundation
import Observation
import TetsubiWidgetData

/// App-wide owner of the signed-in state. The phone UI, the Mac window and
/// CarPlay all use this one instance, so music started in the car keeps
/// playing — with the same queue — when the phone app opens, and vice versa.
/// CarPlay can launch the app without the phone UI ever appearing, which is
/// why this can't live inside a SwiftUI view.
@MainActor
@Observable
public final class AppSession {

    public static let shared = AppSession()

    public private(set) var api: JellyfinAPI?
    public private(set) var player: PlayerManager?
    public private(set) var downloadManager: DownloadManager?

    private let authenticator = Authenticator()

    private init() {}

    /// Restores the saved login without a network round trip (works offline;
    /// a rejected token surfaces later as a 401). Returns whether signed in.
    @discardableResult
    public func restore() -> Bool {
        if api != nil { return true }
        guard let saved = authenticator.makeAPI() else { return false }
        start(saved, newLogin: false)
        return true
    }

    /// Starts a fresh player and download manager for `api` (a new login).
    public func signIn(_ api: JellyfinAPI) {
        start(api, newLogin: true)
    }

    private func start(_ api: JellyfinAPI, newLogin: Bool) {
        player?.stop()
        self.api = api
        let player = PlayerManager(api: api, queueSaver: PlayQueueSaver())
        player.start()
        self.player = player
        // The queue saved for this server and user comes back paused.
        player.restoreSavedQueue()
        self.downloadManager = DownloadManager(api: api)
        #if os(iOS)
        // The widget keeps the last track across launches, not across logins.
        WidgetSnapshotWriter.shared.signedIn(player, clearTrack: newLogin)
        #endif
    }

    // MARK: - Siri / Shortcuts helpers

    public enum PlayError: Error {
        case notSignedIn
        case albumNotFound
        case playlistNotFound
        case playlistEmpty
        /// Offline, and its songs aren't on this device.
        case notDownloaded(PlaylistPlayRefusal)
    }

    /// Albums whose name or artist matches `term`, discs merged.
    public func searchAlbums(_ term: String) async -> [JellyfinAlbum] {
        guard restore(), let api else { return [] }
        return (try? await AlbumSearch.run(api: api, term: term)) ?? []
    }

    /// Recently played then favourite albums (deduplicated, discs merged) —
    /// the albums Siri should recognise by name without a search.
    public func suggestedAlbums() async -> [JellyfinAlbum] {
        guard restore(), let api else { return [] }
        async let recent: [JellyfinAlbum] = withCheckedContinuation { cont in
            api.recentlyPlayedAlbums(limit: 20) { cont.resume(returning: (try? $0.get()) ?? []) }
        }
        async let favourites: [JellyfinAlbum] = withCheckedContinuation { cont in
            api.favoriteAlbums { cont.resume(returning: (try? $0.get()) ?? []) }
        }
        var seen = Set<String>()
        return DiscGrouping.merge(await recent + FavoritesStore.ordered(await favourites))
            .filter { seen.insert($0.id).inserted }
    }

    /// One album by id (name shown without a "(Disc 1)" suffix), or nil.
    public func album(id: String) async -> JellyfinAlbum? {
        guard restore(), let api else { return nil }
        let fetched: JellyfinAlbum? = await withCheckedContinuation { cont in
            api.album(id) { cont.resume(returning: try? $0.get()) }
        }
        return fetched.map { DiscGrouping.merge([$0])[0] }
    }

    /// A random album from the library.
    public func randomAlbum() async -> JellyfinAlbum? {
        guard restore(), let api else { return nil }
        return await withCheckedContinuation { cont in
            api.randomAlbums(limit: 1) { cont.resume(returning: (try? $0.get())?.first) }
        }
    }

    /// Looks up an album by id and plays it — every disc, in order, for a
    /// multi-disc album. Returns the album played.
    @discardableResult
    public func playAlbum(id: String, shuffled: Bool = false) async throws -> JellyfinAlbum {
        guard restore(), let api, let player else { throw PlayError.notSignedIn }
        let fetched: JellyfinAlbum? = await withCheckedContinuation { cont in
            api.album(id) { cont.resume(returning: try? $0.get()) }
        }
        guard let fetched else { throw PlayError.albumNotFound }

        var album = fetched
        if DiscGrouping.parse(fetched.displayName) != nil, let artistId = fetched.artistId {
            let artistAlbums: [JellyfinAlbum] = await withCheckedContinuation { cont in
                api.albums(forArtist: artistId) { cont.resume(returning: (try? $0.get()) ?? []) }
            }
            let siblings = DiscGrouping.siblings(of: fetched, among: artistAlbums)
            if siblings.count > 1 { album = DiscGrouping.combined(siblings) }
        }
        player.setShuffle(shuffled)
        player.play(discs: album.allDiscs, as: album)
        return album
    }

    /// The widget's Play when nothing is queued (the app was closed): the
    /// album it shows starts again from the start, as Siri's Play Album
    /// would play it. When every disc is downloaded it plays from them
    /// straight away (as the app always does), so it also works offline.
    public func playAgain(_ snapshot: WidgetSnapshot) async throws {
        guard restore(), let player else { throw PlayError.notSignedIn }
        guard let albumId = snapshot.albumId else { throw PlayError.albumNotFound }
        let discs = snapshot.discIds.isEmpty ? [albumId] : snapshot.discIds
        let downloaded = discs.allSatisfy {
            DownloadPaths.exists(albumID: $0) && DownloadPaths.fileCount(albumID: $0) > 0
        }
        if downloaded {
            player.setShuffle(false)
            player.playDownloaded(albumIDs: discs, sourceId: albumId,
                                  albumTitle: snapshot.album ?? "", artistName: snapshot.artist ?? "")
            return
        }
        try await playAlbum(id: albumId)
    }

    /// A tap on the widget: Now Playing when something is loaded, else Home.
    public func open(widgetURL url: URL) {
        guard url.scheme == WidgetShared.nowPlayingURL.scheme else { return }
        restore()
        if url == WidgetShared.nowPlayingURL, let player, player.currentTrack != nil {
            player.isNowPlayingPresented = true
        } else {
            player?.isNowPlayingPresented = false
            NotificationCenter.default.post(name: Self.showHome, object: self)
        }
    }

    /// Asks the root view to show the Home tab (a widget tap with nothing playing).
    public static let showHome = Notification.Name("AppSession.showHome")

    // MARK: - Playlists, for CarPlay and Siri

    /// A playlist's songs and where they came from.
    public struct PlaylistRows: Sendable {
        public let entries: [PlaylistEntry]
        /// False when they were read from the download (no connection, or
        /// the server didn't answer): only songs on this device can play.
        public let fromServer: Bool
    }

    /// The server's playlists; without it, those listed offline (each
    /// finished downloading at least once). By name.
    public func playlists() async -> (playlists: [JellyfinPlaylist], fromServer: Bool) {
        guard restore(), let api else { return ([], false) }
        if NetworkMonitor.shared.isConnected {
            let listed: [JellyfinPlaylist]? = await withCheckedContinuation { cont in
                api.playlists { cont.resume(returning: try? $0.get()) }
            }
            if let listed { return (PlaylistsViewModel.sorted(listed, by: .name), true) }
        }
        return (PlaylistsViewModel.sorted(PlaylistDownloadPaths.offlinePlaylists(), by: .name), false)
    }

    /// Playlists whose name matches `term`; without the server, among
    /// those listed offline.
    public func searchPlaylists(_ term: String) async -> [JellyfinPlaylist] {
        guard restore(), let api else { return [] }
        if NetworkMonitor.shared.isConnected {
            let found: [JellyfinPlaylist]? = await withCheckedContinuation { cont in
                api.searchPlaylists(term: term) { cont.resume(returning: try? $0.get()) }
            }
            if let found { return found }
        }
        return LocalSearch.matches(term, in: PlaylistDownloadPaths.offlinePlaylists())
    }

    /// One playlist by id, or nil.
    public func playlist(id: String) async -> JellyfinPlaylist? {
        await playlists().playlists.first { $0.id == id }
    }

    /// A playlist's songs: the server's, else the downloaded ones. Nil
    /// when neither can be read.
    public func playlistRows(_ playlistID: String) async -> PlaylistRows? {
        guard restore(), let api else { return nil }
        if NetworkMonitor.shared.isConnected {
            let entries: [PlaylistEntry]? = await withCheckedContinuation { cont in
                api.playlistTracks(playlistID) { cont.resume(returning: try? $0.get()) }
            }
            if let entries { return PlaylistRows(entries: entries, fromServer: true) }
        }
        guard let manifest = PlaylistDownloadPaths.loadManifest(playlistID), manifest.downloadedAt != nil else {
            return nil
        }
        return PlaylistRows(entries: manifest.playlistEntries, fromServer: false)
    }

    /// Plays a playlist's songs from the top, or from a `position` in it.
    /// `shuffled` sets shuffle first (nil leaves it as it is). Returns why
    /// nothing played, when offline and the songs aren't on this device.
    @discardableResult
    public func play(_ playlist: JellyfinPlaylist, rows: PlaylistRows, startingAt position: Int? = nil,
                     shuffled: Bool? = nil) -> PlaylistPlayRefusal? {
        guard let player else { return nil }
        let before = player.shuffle
        if let shuffled { player.setShuffle(shuffled) }
        let refusal = player.play(playlist: playlist, tracks: rows.entries, startingAt: position,
                                  online: rows.fromServer)
        if refusal != nil { player.setShuffle(before) }
        return refusal
    }

    /// Looks up a playlist by id and plays it from the top. Returns the
    /// playlist played.
    @discardableResult
    public func playPlaylist(id: String, shuffled: Bool = false) async throws -> JellyfinPlaylist {
        guard restore(), api != nil, player != nil else { throw PlayError.notSignedIn }
        guard let playlist = await playlist(id: id), let rows = await playlistRows(id) else {
            throw PlayError.playlistNotFound
        }
        guard !rows.entries.isEmpty else { throw PlayError.playlistEmpty }
        if let refusal = play(playlist, rows: rows, shuffled: shuffled) { throw PlayError.notDownloaded(refusal) }
        return playlist
    }

    /// Clears the saved login and stops playback.
    public func signOut() {
        authenticator.logout()
        player?.stop(forgetQueue: true)
        PlayQueueStore.clear()
        api = nil
        player = nil
        downloadManager = nil
        #if os(iOS)
        WidgetSnapshotWriter.shared.signedOut()
        #endif
    }
}
