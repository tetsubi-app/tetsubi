// MARK: - AlbumSearch — albums matching a title or an artist

import Foundation

/// Searches albums by title and by artist, since Jellyfin's album search
/// only matches titles ("Gruntruck" found nothing). Title matches come
/// first, then albums by matching artists (up to 3 artists); duplicates
/// removed and multi-disc albums merged.
public enum AlbumSearch {

    public static func run(api: JellyfinAPI, term: String) async throws -> [JellyfinAlbum] {
        async let byTitle: [JellyfinAlbum] = withCheckedThrowingContinuation { cont in
            api.searchForAlbums(term: term, limit: 50) { cont.resume(with: $0) }
        }
        async let artists: [JellyfinArtist] = withCheckedContinuation { cont in
            api.searchArtists(term: term, limit: 3) { cont.resume(returning: (try? $0.get()) ?? []) }
        }
        let titleMatches = try await byTitle
        var byArtist: [JellyfinAlbum] = []
        for artist in await artists {
            let albums: [JellyfinAlbum] = await withCheckedContinuation { cont in
                api.albums(forArtist: artist.id) { cont.resume(returning: (try? $0.get()) ?? []) }
            }
            byArtist += albums
        }
        return combine(titleMatches: titleMatches, artistMatches: byArtist)
    }

    /// Title matches first, then artist matches; each album once; discs merged.
    static func combine(titleMatches: [JellyfinAlbum], artistMatches: [JellyfinAlbum]) -> [JellyfinAlbum] {
        var seen = Set<String>()
        let unique = (titleMatches + artistMatches).filter { seen.insert($0.id).inserted }
        return DiscGrouping.merge(unique)
    }
}
