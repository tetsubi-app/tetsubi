import XCTest
@testable import JellyfinMusicCore

final class ArtistInfoTests: XCTestCase {

    private let sample = """
    {"id":"b7ffd2af-418f-4be2-bdd1-22f8b48613da","name":"Nine Inch Nails","type":"Group",
     "disambiguation":"","area":{"name":"United States"},"begin-area":{"name":"Cleveland"},
     "life-span":{"begin":"1988","end":null,"ended":false},
     "genres":[{"name":"industrial","count":18},{"name":"industrial rock","count":40},
               {"name":"alternative rock","count":16},{"name":"electronic","count":12},
               {"name":"industrial metal","count":8},{"name":"electro-industrial","count":6}],
     "relations":[{"type":"official homepage","url":{"resource":"https://www.nin.com/"}},
                  {"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q11647"}}]}
    """

    func testParsesMusicBrainzArtist() throws {
        let artist = try JSONDecoder().decode(MBArtist.self, from: Data(sample.utf8))
        let info = ArtistInfoClient.info(from: artist)
        XCTAssertEqual(info.facts, "Group · Cleveland, United States · 1988–present")
        XCTAssertEqual(info.genres, ["industrial rock", "industrial", "alternative rock", "electronic", "industrial metal"])
        XCTAssertNil(info.disambiguation)
        XCTAssertEqual(info.musicBrainzURL.absoluteString,
                       "https://musicbrainz.org/artist/b7ffd2af-418f-4be2-bdd1-22f8b48613da")
        XCTAssertEqual(ArtistInfoClient.wikidataID(in: artist.relations ?? []), "Q11647")
    }

    func testYears() {
        XCTAssertEqual(ArtistInfo.years(begin: "1988", end: nil, ended: false), "1988–present")
        XCTAssertEqual(ArtistInfo.years(begin: "1989-03-01", end: "1996-11", ended: true), "1989–1996")
        XCTAssertEqual(ArtistInfo.years(begin: "1990", end: nil, ended: true), "1990")
        XCTAssertNil(ArtistInfo.years(begin: nil, end: "2000", ended: true))
    }

    func testNameSearchOnlyTrustsAnExactConfidentMatch() {
        typealias A = MBSearchResponse.Artist
        let results = [A(id: "tribute", name: "Not Nine Inch Nails", score: 100),
                       A(id: "nin", name: "nine inch nails", score: 98)]
        XCTAssertEqual(ArtistInfoClient.bestMatch(for: "Nine Inch Nails", in: results), "nin")
        XCTAssertNil(ArtistInfoClient.bestMatch(for: "Nine Inch Nails", in: [A(id: "x", name: "Nine Inch Nails", score: 70)]))
        XCTAssertNil(ArtistInfoClient.bestMatch(for: "Gruntruck", in: []))
    }

    func testOldStyleWikipediaLink() throws {
        let json = #"[{"type":"wikipedia","url":{"resource":"https://en.wikipedia.org/wiki/Mot%C3%B6rhead"}}]"#
        let rels = try JSONDecoder().decode([MBArtist.Relation].self, from: Data(json.utf8))
        XCTAssertEqual(ArtistInfoClient.wikipediaTitleFromLink(in: rels), "Motörhead")
        XCTAssertNil(ArtistInfoClient.wikidataID(in: rels))
    }
}
