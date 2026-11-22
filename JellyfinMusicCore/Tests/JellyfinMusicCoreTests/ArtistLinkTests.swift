import XCTest
@testable import JellyfinMusicCore

/// The artist line on the album page and in Now Playing: which artist it opens, and when it can't.
final class ArtistLinkTests: XCTestCase {

    private func album(_ json: String) throws -> JellyfinAlbum {
        try JSONDecoder().decode(JellyfinAlbum.self, from: Data(json.utf8))
    }

    private func track(_ json: String) throws -> JellyfinTrack {
        try JSONDecoder().decode(JellyfinTrack.self, from: Data(json.utf8))
    }

    func testAnArtistWithAnIDOpensThatArtist() {
        XCTAssertEqual(ArtistLink.resolve(name: "Low", id: "a1"), .artist(JellyfinArtist(id: "a1", name: "Low")))
    }

    func testANameWithoutAnIDIsLookedUp() {
        XCTAssertEqual(ArtistLink.resolve(name: " Low ", id: nil), .lookup(name: "Low"))
        XCTAssertEqual(ArtistLink.resolve(name: "Low", id: ""), .lookup(name: "Low"))
    }

    func testNoNameOrVariousArtistsIsNotTappable() {
        XCTAssertNil(ArtistLink.resolve(name: "", id: "a1"))
        XCTAssertNil(ArtistLink.resolve(name: "  ", id: nil))
        XCTAssertNil(ArtistLink.resolve(name: "Various Artists", id: "va"))
        XCTAssertNil(ArtistLink.resolve(name: "various artists", id: nil))
    }

    func testTheAlbumsFirstAlbumArtistIsUsed() throws {
        let a = try album(#"{"Id":"al","Name":"Split","AlbumArtists":[{"Id":"a1","Name":"Low"},{"Id":"a2","Name":"Spoon"}]}"#)
        XCTAssertEqual(ArtistLink.resolve(name: a.artistName, id: a.artistId), .artist(JellyfinArtist(id: "a1", name: "Low")))
    }

    func testMatchIsExactIgnoringCaseAndSpaces() {
        let found = [JellyfinArtist(id: "1", name: "Lower"), JellyfinArtist(id: "2", name: "LOW ")]
        XCTAssertEqual(ArtistLink.match("low", among: found)?.id, "2")
        XCTAssertNil(ArtistLink.match("Lo", among: found))
        XCTAssertNil(ArtistLink.match("Low", among: []))
    }

    func testFindLooksUpANameOnlyOnline() async {
        var searched: [String] = []
        let search: (String) async -> [JellyfinArtist] = { term in
            searched.append(term)
            return [JellyfinArtist(id: "x", name: "Low")]
        }
        let byId = await ArtistLink.find(name: "Low", id: "a1", online: true, search: search)
        XCTAssertEqual(byId?.id, "a1")
        XCTAssertEqual(searched, [])
        let looked = await ArtistLink.find(name: "Low", id: nil, online: true, search: search)
        XCTAssertEqual(looked?.id, "x")
        XCTAssertEqual(searched, ["Low"])
        let offline = await ArtistLink.find(name: "Low", id: nil, online: false, search: search)
        XCTAssertNil(offline)
        // Offline with an ID it still opens (the page shows the downloaded albums).
        let offlineById = await ArtistLink.find(name: "Low", id: "a1", online: false, search: search)
        XCTAssertEqual(offlineById?.id, "a1")
        let noMatch = await ArtistLink.find(name: "Spoon", id: nil, online: true, search: search)
        XCTAssertNil(noMatch)
        let various = await ArtistLink.find(name: "Various Artists", id: nil, online: true, search: search)
        XCTAssertNil(various)
        XCTAssertEqual(searched, ["Low", "Spoon"])
    }

    /// Now Playing's track carries its album artist's ID: the track's own first album artist, else the album's.
    func testPlaybackTrackCarriesTheAlbumArtistID() throws {
        let a = try album(#"{"Id":"al","Name":"A","AlbumArtists":[{"Id":"a1","Name":"Low"}]}"#)
        let own = try track(#"{"Id":"t","AlbumArtists":[{"Id":"a2","Name":"Spoon"}]}"#)
        let pt = PlaybackTrack(track: own, album: a)
        XCTAssertEqual(pt.artist, "Spoon")
        XCTAssertEqual(pt.albumArtistId, "a2")

        let bare = PlaybackTrack(track: try track(#"{"Id":"t"}"#), album: a)
        XCTAssertEqual(bare.artist, "Low")
        XCTAssertEqual(bare.albumArtistId, "a1")

        // A playlist's album knows only the name; the track's artist has no ID either.
        let named = try album(#"{"Id":"al","Name":"A","AlbumArtists":[{"Name":"Low"}]}"#)
        let noId = PlaybackTrack(track: try track(#"{"Id":"t","AlbumArtists":[{"Name":"Low"}]}"#), album: named)
        XCTAssertEqual(noId.artist, "Low")
        XCTAssertNil(noId.albumArtistId)
        XCTAssertEqual(ArtistLink.resolve(name: noId.artist, id: noId.albumArtistId), .lookup(name: "Low"))
    }

    func testANameOnlyTrackArtistBorrowsTheAlbumsIDWhenTheNamesMatch() throws {
        let a = try album(#"{"Id":"al","Name":"A","AlbumArtists":[{"Id":"a1","Name":"Low"}]}"#)
        let same = ArtistLink.albumArtist(trackArtists: [JellyfinPerson(id: nil, name: "Low", serverId: nil)], album: a)
        XCTAssertEqual(same.id, "a1")
        let other = ArtistLink.albumArtist(trackArtists: [JellyfinPerson(id: nil, name: "Spoon", serverId: nil)], album: a)
        XCTAssertNil(other.id)
    }
}
