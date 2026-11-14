import XCTest
@testable import JellyfinMusicCore

final class DiscGroupingTests: XCTestCase {

    private func album(_ id: String, _ name: String, artist: String = "Pumpkins") -> JellyfinAlbum {
        JellyfinAlbum(id: id, name: name, serverId: nil, premiereDate: nil, productionYear: 1995,
                      albumArtists: [JellyfinPerson(id: "ar", name: artist, serverId: nil)],
                      album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
    }

    func testParsesCommonDiscNames() {
        let cases: [(String, String, Int)] = [
            ("Mellon Collie (Disc 1)", "Mellon Collie", 1),
            ("Mellon Collie [Disc 2]", "Mellon Collie", 2),
            ("Mellon Collie (CD2)", "Mellon Collie", 2),
            ("Mellon Collie - Disc 2", "Mellon Collie", 2),
            ("Mellon Collie: Disk 3", "Mellon Collie", 3),
            ("Mellon Collie CD 1", "Mellon Collie", 1),
            ("Mellon Collie (Disc 2 of 2)", "Mellon Collie", 2),
            ("Mellon Collie (disc.2)", "Mellon Collie", 2),
            ("Inside Yours/Push (Disc 2)", "Inside Yours/Push", 2),
            ("Akumajou Dracula X (Disc 1 Dracula X Chii no Rondo)", "Akumajou Dracula X", 1),
        ]
        for (name, base, disc) in cases {
            let parsed = DiscGrouping.parse(name)
            XCTAssertEqual(parsed?.base, base, name)
            XCTAssertEqual(parsed?.disc, disc, name)
        }
    }

    /// Real names from the library: a subtitle inside the disc brackets.
    func testParsesDiscSubtitle() {
        XCTAssertEqual(DiscGrouping.parse("Akumajou Dracula X (Disc 2 Vampire Killer-Castlevania Bloodlines)")?.subtitle,
                       "Vampire Killer-Castlevania Bloodlines")
        XCTAssertEqual(DiscGrouping.parse("Mellon Collie (Disc 1: Dawn to Dusk)")?.subtitle, "Dawn to Dusk")
        XCTAssertNil(DiscGrouping.parse("Mellon Collie (Disc 1)")?.subtitle)
    }

    func testMergesDiscsWithDifferentSubtitles() {
        let merged = DiscGrouping.merge([
            album("a", "Akumajou Dracula X (Disc 1 Dracula X Chii no Rondo)", artist: "Konami Kukeiha Club"),
            album("b", "Akumajou Dracula X (Disc 2 Vampire Killer-Castlevania Bloodlines)", artist: "Konami Kukeiha Club"),
        ])
        XCTAssertEqual(merged.count, 1)
        XCTAssertEqual(merged[0].displayName, "Akumajou Dracula X")
        XCTAssertEqual(merged[0].allDiscs.map(\.id), ["a", "b"])
    }

    func testIgnoresNonDiscNames() {
        for name in ["Mellon Collie", "Disc 2", "Abcd 2", "NOW 100", "1999", "Discovery", "Discovery 2", "Abc (Discotheque 2)"] {
            XCTAssertNil(DiscGrouping.parse(name), name)
        }
    }

    func testMergesDiscsInPlaceOfFirst() {
        let merged = DiscGrouping.merge([
            album("x", "Siamese Dream"),
            album("d2", "Mellon Collie (Disc 2)"),
            album("y", "Gish"),
            album("d1", "Mellon Collie (Disc 1)"),
        ])
        XCTAssertEqual(merged.map(\.displayName), ["Siamese Dream", "Mellon Collie", "Gish"])
        let mc = merged[1]
        XCTAssertEqual(mc.id, "d1", "keeps the lowest disc's id")
        XCTAssertTrue(mc.isMultiDisc)
        XCTAssertEqual(mc.allDiscs.map(\.id), ["d1", "d2"])
        XCTAssertEqual(mc.allDiscs.map(\.displayName), ["Mellon Collie (Disc 1)", "Mellon Collie (Disc 2)"])
    }

    func testDifferentArtistsAreNotMerged() {
        let merged = DiscGrouping.merge([
            album("a", "Greatest Hits (Disc 1)", artist: "Queen"),
            album("b", "Greatest Hits (Disc 1)", artist: "ABBA"),
        ])
        XCTAssertEqual(merged.count, 2)
    }

    func testSingleDiscGetsBaseNameAndFindsSiblings() {
        let lone = DiscGrouping.merge([album("d2", "Mellon Collie (Disc 2)")])[0]
        XCTAssertEqual(lone.displayName, "Mellon Collie")
        XCTAssertFalse(lone.isMultiDisc)
        let artistAlbums = [album("d1", "Mellon Collie (Disc 1)"), album("d2", "Mellon Collie (Disc 2)"),
                            album("z", "Zeitgeist")]
        XCTAssertEqual(DiscGrouping.siblings(of: lone, among: artistAlbums).map(\.id), ["d1", "d2"])
        XCTAssertEqual(DiscGrouping.siblings(of: album("z", "Zeitgeist"), among: artistAlbums), [])
    }
}
