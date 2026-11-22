// MARK: - ArtistLink — which artist a tappable artist line opens

import Foundation

/// The artist line on the album page and in Now Playing opens that album
/// artist's albums. This works out which artist, or that the line isn't
/// tappable. Kotlin's `ArtistLink` is the same.
public enum ArtistLink: Equatable, Sendable {
    /// Open this artist's page.
    case artist(JellyfinArtist)
    /// Only the name is known: look it up (`match`) and open the match.
    case lookup(name: String)

    /// The album artist named `name` with server ID `id` (the first of
    /// `AlbumArtists`). Nil, not tappable: no name, or "Various Artists".
    public static func resolve(name: String, id: String?) -> ArtistLink? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isVarious(trimmed) else { return nil }
        if let id, !id.isEmpty { return .artist(JellyfinArtist(id: id, name: name)) }
        return .lookup(name: trimmed)
    }

    /// The artist in `candidates` (an album-artist search) with exactly
    /// this name, ignoring case and surrounding spaces; nil if none.
    public static func match(_ name: String, among candidates: [JellyfinArtist]) -> JellyfinArtist? {
        let wanted = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return candidates.first {
            !$0.id.isEmpty &&
                $0.name.trimmingCharacters(in: .whitespacesAndNewlines)
                .caseInsensitiveCompare(wanted) == .orderedSame
        }
    }

    /// The artist to open, looking a name-only artist up with `search`
    /// (the album artists matching a term) when `online`. Nil: not tappable.
    public static func find(name: String, id: String?, online: Bool,
                            search: (String) async -> [JellyfinArtist]) async -> JellyfinArtist? {
        switch resolve(name: name, id: id) {
        case .artist(let artist): return artist
        case .lookup(let name): return online ? match(name, among: await search(name)) : nil
        case nil: return nil
        }
    }

    /// The album artist shown for a track (`PlaybackTrack.artist`) and its
    /// ID: the track's first album artist, else the album's. A track artist
    /// without an ID borrows the album's when the names are the same.
    static func albumArtist(trackArtists: [JellyfinPerson]?, album: JellyfinAlbum) -> (name: String, id: String?) {
        guard let first = trackArtists?.first else { return (album.artistName, album.artistId) }
        let id = first.id ?? (first.name == album.artistName ? album.artistId : nil)
        return (first.name, id)
    }

    static func isVarious(_ name: String) -> Bool {
        name.caseInsensitiveCompare("Various Artists") == .orderedSame
    }
}

extension JellyfinAPI {
    /// Album artists whose names match `term`, for `ArtistLink.find`; empty on failure.
    func albumArtists(matching term: String) async -> [JellyfinArtist] {
        await withCheckedContinuation { cont in
            searchArtists(term: term, limit: 10) { cont.resume(returning: (try? $0.get()) ?? []) }
        }
    }
}
