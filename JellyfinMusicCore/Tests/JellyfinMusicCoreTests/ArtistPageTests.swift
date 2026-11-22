import XCTest
@testable import JellyfinMusicCore

private func album(_ id: String, _ name: String, year: Int? = nil, premiere: String? = nil) -> JellyfinAlbum {
    JellyfinAlbum(id: id, name: name, serverId: nil, premiereDate: premiere, productionYear: year,
                  albumArtists: [JellyfinPerson(id: nil, name: "Artist", serverId: nil)],
                  album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
}

/// An artist's page: the year under each title, newest release first.
final class ArtistPageTests: XCTestCase {

    func testYearTextIsTheProductionYearElseTheReleaseDatesYear() {
        XCTAssertEqual(ArtistPage.yearText(album("a", "A", year: 1994, premiere: "1993-12-31T00:00:00.0000000Z")), "1994")
        XCTAssertEqual(ArtistPage.yearText(album("a", "A", premiere: "1987-03-09T00:00:00.0000000Z")), "1987")
        XCTAssertNil(ArtistPage.yearText(album("a", "A")), "nothing when the year is unknown")
    }

    func testNewestReleaseFirstThenTitleUnknownLast() {
        let albums = [
            album("u", "Unknown"),
            album("old", "Old", year: 1980),
            album("b", "Bravo", year: 2001),
            album("full", "Late 2001", premiere: "2001-11-05T00:00:00.0000000Z"),
            album("a", "Alpha", year: 2001),
            album("new", "New", year: 2020),
        ]
        XCTAssertEqual(ArtistPage.ordered(albums).map(\.id), ["new", "full", "a", "b", "old", "u"])
    }
}
