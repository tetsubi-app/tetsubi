import XCTest
@testable import JellyfinMusicCore

final class AlbumSearchTests: XCTestCase {

    private func album(_ id: String, _ name: String, artist: String = "Gruntruck") -> JellyfinAlbum {
        JellyfinAlbum(id: id, name: name, serverId: nil, premiereDate: nil, productionYear: nil,
                      albumArtists: [JellyfinPerson(id: "ar", name: artist, serverId: nil)],
                      album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
    }

    /// Searching "Gruntruck" matched no titles; the artist's albums must show.
    func testArtistMatchesAppearWhenNoTitleMatches() {
        let results = AlbumSearch.combine(titleMatches: [], artistMatches: [
            album("a", "Inside Yours"), album("d1", "Inside Yours/Push (Disc 1)"),
            album("d2", "Inside Yours/Push (Disc 2)"),
        ])
        XCTAssertEqual(results.map(\.displayName), ["Inside Yours", "Inside Yours/Push"])
    }

    func testTitleMatchesFirstWithoutDuplicates() {
        let results = AlbumSearch.combine(
            titleMatches: [album("a", "Inside Yours")],
            artistMatches: [album("b", "Push"), album("a", "Inside Yours")]
        )
        XCTAssertEqual(results.map(\.id), ["a", "b"])
    }
}
