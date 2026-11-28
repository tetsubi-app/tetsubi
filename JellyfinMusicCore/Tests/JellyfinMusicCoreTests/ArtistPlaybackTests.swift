import XCTest
@testable import JellyfinMusicCore

private func album(_ id: String, _ name: String? = nil, year: Int? = nil, premiere: String? = nil) -> JellyfinAlbum {
    JellyfinAlbum(id: id, name: name ?? "Album \(id)", serverId: nil, premiereDate: premiere, productionYear: year,
                  albumArtists: [JellyfinPerson(id: nil, name: "Band", serverId: nil)],
                  album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
}

/// Play all and Shuffle on an artist: the order, the cap and the queue group (as Kotlin's ArtistPlaybackTest).
final class ArtistPlaybackTests: XCTestCase {
    func testAlbumsPlayOldestFirstUnknownLastTiesByTitle() {
        let albums = [album("new", year: 2010), album("none"), album("b", "B side", year: 1999),
                      album("old", year: 1980), album("a", "A side", year: 1999)]
        XCTAssertEqual(ArtistTracks.albumOrder(albums).map(\.id), ["old", "a", "b", "new", "none"])
    }

    func testAFullReleaseDateBeatsTheYearWithinTheYear() {
        let late = album("late", "Late", year: 2001, premiere: "2001-11-05T00:00:00.0000000Z")
        let early = album("early", "Zed", year: 2001, premiere: "2001-02-01T00:00:00.0000000Z")
        XCTAssertEqual(ArtistTracks.albumOrder([late, early]).map(\.id), ["early", "late"])
    }

    func testTheCapCutsAndSaysSo() {
        let (kept, capped) = ArtistTracks.limit(Array(1...7), cap: 5)
        XCTAssertEqual(kept, [1, 2, 3, 4, 5])
        XCTAssertTrue(capped)
        let (all, cappedNone) = ArtistTracks.limit(Array(1...5), cap: 5)
        XCTAssertEqual(all.count, 5)
        XCTAssertFalse(cappedNone)
        XCTAssertEqual(ArtistTracks.cap, 500)
        XCTAssertEqual(ArtistTracks.cappedMessage(), "Queued the first 500 of this artist's tracks.")
    }

    func testMoreAlbumsAreFetchedOnlyWhileWithinTheCap() {
        XCTAssertTrue(ArtistTracks.needsMore(trackCount: 0))
        XCTAssertTrue(ArtistTracks.needsMore(trackCount: 500))
        XCTAssertFalse(ArtistTracks.needsMore(trackCount: 501))
    }

    func testOutcomesSayWhatHappened() {
        XCTAssertNil(ArtistTracks.Outcome.done(capped: false).notice)
        XCTAssertEqual(ArtistTracks.Outcome.done(capped: true).notice, "Queued the first 500 of this artist's tracks.")
        XCTAssertFalse(ArtistTracks.Outcome.done(capped: true).isFailure)
        XCTAssertEqual(ArtistTracks.emptyOutcome(online: true).notice, "This artist has no tracks.")
        XCTAssertEqual(ArtistTracks.emptyOutcome(online: false).notice, "None of this artist's music is downloaded.")
        XCTAssertTrue(ArtistTracks.Outcome.notLoaded.isFailure)
    }

    func testTheQueueGroupIsTheArtistWithTheirPicture() {
        let group = ArtistTracks.group(id: "ar", name: "Band")
        XCTAssertEqual(group.kind, .tracks)
        XCTAssertEqual(group.title, "Band")
        XCTAssertEqual(group.artItemId, "ar")
    }
}
