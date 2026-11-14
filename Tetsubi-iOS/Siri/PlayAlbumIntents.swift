// MARK: - Siri & Shortcuts — "Play <album>" and "Play <playlist>" in Tetsubi

import AppIntents
import JellyfinMusicCore

/// An album as Siri and Shortcuts see it.
struct AlbumEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Album"
    static let defaultQuery = AlbumQuery()

    let id: String
    let name: String
    let artist: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: artist.isEmpty ? nil : "\(artist)")
    }

    init(_ album: JellyfinAlbum) {
        id = album.id
        name = album.displayName
        artist = album.artistName
    }
}

/// Finds albums for Siri: recently played and favourites are offered up front
/// (so Siri knows them by name); anything else is a server search.
struct AlbumQuery: EntityStringQuery {
    @MainActor
    func entities(for identifiers: [String]) async throws -> [AlbumEntity] {
        var result: [AlbumEntity] = []
        for id in identifiers {
            if let album = await AppSession.shared.album(id: id) { result.append(AlbumEntity(album)) }
        }
        return result
    }

    @MainActor
    func entities(matching string: String) async throws -> [AlbumEntity] {
        await AppSession.shared.searchAlbums(string).map(AlbumEntity.init)
    }

    @MainActor
    func suggestedEntities() async throws -> [AlbumEntity] {
        await AppSession.shared.suggestedAlbums().map(AlbumEntity.init)
    }
}

/// What Siri says when it can't play.
enum PlayIntentError: Error, CustomLocalizedStringResourceConvertible {
    case notSignedIn
    case albumNotFound
    case libraryEmpty
    case playlistNotFound
    case playlistEmpty
    case notDownloaded(PlaylistPlayRefusal)

    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .notSignedIn:   "Open Tetsubi and sign in first."
        case .albumNotFound: "I couldn't find that album on your Jellyfin server."
        case .libraryEmpty:  "I couldn't reach your Jellyfin server."
        case .playlistNotFound: "I couldn't find that playlist on your Jellyfin server."
        case .playlistEmpty: "This playlist is empty."
        case .notDownloaded(let refusal): "\(refusal.message)"
        }
    }
}

/// Plays an album. `AudioPlaybackIntent` lets it start music without
/// opening the app.
struct PlayAlbumIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play Album"
    static let description = IntentDescription("Plays an album from your Jellyfin library.")

    @Parameter(title: "Album", requestValueDialog: "Which album?")
    var album: AlbumEntity

    @Parameter(title: "Shuffle", default: false)
    var shuffle: Bool

    static var parameterSummary: some ParameterSummary {
        Summary("Play \(\.$album)") { \.$shuffle }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        do {
            let played = try await AppSession.shared.playAlbum(id: album.id, shuffled: shuffle)
            return .result(dialog: "Playing \(played.displayName).")
        } catch AppSession.PlayError.notSignedIn {
            throw PlayIntentError.notSignedIn
        } catch {
            throw PlayIntentError.albumNotFound
        }
    }
}

/// A playlist as Siri and Shortcuts see it.
struct PlaylistEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Playlist"
    static let defaultQuery = PlaylistQuery()

    let id: String
    let name: String
    let summary: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: "\(summary)")
    }

    init(_ playlist: JellyfinPlaylist) {
        id = playlist.id
        name = playlist.name
        summary = playlist.summary
    }
}

/// Finds playlists for Siri: all of them are offered up front (so Siri
/// knows them by name); a search matches by name. Without the server,
/// the downloaded ones.
struct PlaylistQuery: EntityStringQuery {
    @MainActor
    func entities(for identifiers: [String]) async throws -> [PlaylistEntity] {
        let all = await AppSession.shared.playlists().playlists
        return identifiers.compactMap { id in all.first { $0.id == id } }.map(PlaylistEntity.init)
    }

    @MainActor
    func entities(matching string: String) async throws -> [PlaylistEntity] {
        await AppSession.shared.searchPlaylists(string).map(PlaylistEntity.init)
    }

    @MainActor
    func suggestedEntities() async throws -> [PlaylistEntity] {
        await AppSession.shared.playlists().playlists.map(PlaylistEntity.init)
    }
}

/// Plays a playlist from the top.
struct PlayPlaylistIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play Playlist"
    static let description = IntentDescription("Plays a playlist from your Jellyfin library.")

    @Parameter(title: "Playlist", requestValueDialog: "Which playlist?")
    var playlist: PlaylistEntity

    @Parameter(title: "Shuffle", default: false)
    var shuffle: Bool

    static var parameterSummary: some ParameterSummary {
        Summary("Play \(\.$playlist)") { \.$shuffle }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        do {
            let played = try await AppSession.shared.playPlaylist(id: playlist.id, shuffled: shuffle)
            return .result(dialog: "Playing \(played.name).")
        } catch AppSession.PlayError.notSignedIn {
            throw PlayIntentError.notSignedIn
        } catch AppSession.PlayError.playlistEmpty {
            throw PlayIntentError.playlistEmpty
        } catch AppSession.PlayError.notDownloaded(let refusal) {
            throw PlayIntentError.notDownloaded(refusal)
        } catch {
            throw PlayIntentError.playlistNotFound
        }
    }
}

/// "Play something" — a random album from the library.
struct PlayRandomAlbumIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play Something"
    static let description = IntentDescription("Plays a random album from your Jellyfin library.")

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard AppSession.shared.restore() else { throw PlayIntentError.notSignedIn }
        guard let pick = await AppSession.shared.randomAlbum() else { throw PlayIntentError.libraryEmpty }
        let played = try await AppSession.shared.playAlbum(id: pick.id)
        return .result(dialog: "Playing \(played.displayName) by \(played.artistName).")
    }
}

/// Phrases Siri recognises without any setup. Each must name the app.
struct TetsubiShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: PlayAlbumIntent(),
            phrases: [
                "Play \(\.$album) in \(.applicationName)",
                "Play \(\.$album) on \(.applicationName)",
                "Play an album in \(.applicationName)",
                "Play an album on \(.applicationName)",
            ],
            shortTitle: "Play Album",
            systemImageName: "play.fill"
        )
        AppShortcut(
            intent: PlayPlaylistIntent(),
            phrases: [
                "Play \(\.$playlist) in \(.applicationName)",
                "Play \(\.$playlist) on \(.applicationName)",
                "Play the playlist \(\.$playlist) in \(.applicationName)",
                "Play the playlist \(\.$playlist) on \(.applicationName)",
                "Play a playlist in \(.applicationName)",
                "Play a playlist on \(.applicationName)",
            ],
            shortTitle: "Play Playlist",
            systemImageName: "music.note.list"
        )
        AppShortcut(
            intent: PlayRandomAlbumIntent(),
            phrases: [
                "Play something in \(.applicationName)",
                "Play something on \(.applicationName)",
                "Play a random album in \(.applicationName)",
            ],
            shortTitle: "Play Something",
            systemImageName: "shuffle"
        )
    }
}
