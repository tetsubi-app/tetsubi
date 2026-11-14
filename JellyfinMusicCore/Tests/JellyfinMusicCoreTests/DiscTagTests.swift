import XCTest
@testable import JellyfinMusicCore

/// Albums stored as one album with tracks tagged disc 1, disc 2 (The Fragile).
final class DiscTagTests: XCTestCase {

    private func track(_ id: String, disc: Int?, number: Int) -> JellyfinTrack {
        var t = JellyfinTrack(id: id, name: id, serverId: nil, indexNumber: number, runTimeTicks: nil,
                              album: nil, albumId: "a", albumArtists: nil, imageTags: nil, mediaSource: nil)
        t.discNumber = disc
        return t
    }

    func testDecodesParentIndexNumber() throws {
        let json = #"{"Id":"t","Name":"The Way Out Is Through","IndexNumber":1,"ParentIndexNumber":2}"#
        let t = try JSONDecoder().decode(JellyfinTrack.self, from: Data(json.utf8))
        XCTAssertEqual(t.discNumber, 2)
        XCTAssertEqual(t.trackNumber, 1)
    }

    func testGroupsByDiscTag() {
        let tracks = [track("a1", disc: 1, number: 1), track("a2", disc: 1, number: 2),
                      track("b1", disc: 2, number: 1)]
        let groups = DiscGrouping.byDiscNumber(tracks)
        XCTAssertEqual(groups?.map(\.number), [1, 2])
        XCTAssertEqual(groups?.map { $0.tracks.map(\.id) }, [["a1", "a2"], ["b1"]])
    }

    func testOneDiscOrUntaggedIsNotSplit() {
        XCTAssertNil(DiscGrouping.byDiscNumber([track("a", disc: 1, number: 1), track("b", disc: 1, number: 2)]))
        XCTAssertNil(DiscGrouping.byDiscNumber([track("a", disc: nil, number: 1), track("b", disc: nil, number: 2)]))
    }

    func testFileNumbersDontCollideAndSortInPlayOrder() {
        let d1t1 = track("a", disc: 1, number: 1), d1t12 = track("b", disc: 1, number: 12)
        let d2t1 = track("c", disc: 2, number: 1), untagged = track("d", disc: nil, number: 3)
        XCTAssertEqual(d1t1.fileNumber, 1)
        XCTAssertEqual(d1t12.fileNumber, 12)
        XCTAssertEqual(d2t1.fileNumber, 201)
        XCTAssertEqual(untagged.fileNumber, 3)
        let path = DownloadPaths.trackPath(albumID: "x", trackNumber: d2t1.fileNumber,
                                           trackName: "The Way Out Is Through", format: .alac)
        XCTAssertEqual(path.lastPathComponent, "201 - The Way Out Is Through.m4a")
        XCTAssertEqual(DownloadPaths.parseTrackNumber(from: path.lastPathComponent), 201)
    }
}
