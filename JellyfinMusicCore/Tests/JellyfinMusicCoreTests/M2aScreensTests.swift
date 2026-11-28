// Pure rules behind the grid and list screens: cover sizes, the A to Z strip,
// the page controls' threshold, Search's filter chips and artists, Home's rows.

import XCTest
@testable import JellyfinMusicCore

final class CardArtTests: XCTestCase {
    func testCoverSizeStepsUpToCoverThePixels() {
        XCTAssertEqual(CardArt.requestSize(width: 150, displayScale: 2), 300)
        XCTAssertEqual(CardArt.requestSize(width: 151, displayScale: 2), 600)
        XCTAssertEqual(CardArt.requestSize(width: 330, displayScale: 3), 1000)
        XCTAssertEqual(CardArt.requestSize(width: 900, displayScale: 2), 1500, "the biggest there is")
    }
}

final class AlphaIndexStripTests: XCTestCase {
    private let names = ["Abba", "Beck", "Blur", "Émile", "9 Lives"]

    func testFirstIDUnderEachLetter() {
        let ids = AlphaIndex.firstIDs(names, key: { $0 }, id: { "id-" + $0 })
        XCTAssertEqual(ids["A"], "id-Abba")
        XCTAssertEqual(ids["B"], "id-Beck", "the first of two")
        XCTAssertEqual(ids["E"], "id-Émile", "accents are folded")
        XCTAssertEqual(ids["#"], "id-9 Lives")
        XCTAssertNil(ids["C"])
    }

    func testATapGoesToTheLetterOrTheNextOneWithItems() {
        let ids = AlphaIndex.firstIDs(names, key: { $0 }, id: { $0 })
        XCTAssertEqual(AlphaIndex.target(for: "B", in: ids), "Beck")
        XCTAssertEqual(AlphaIndex.target(for: "C", in: ids), "Émile", "the next letter that has one")
        XCTAssertEqual(AlphaIndex.target(for: "Z", in: ids), "Émile", "else the last there is")
        XCTAssertNil(AlphaIndex.target(for: "A", in: [:]))
    }
}

final class PageControlsRuleTests: XCTestCase {
    func testInlineFrom720() {
        XCTAssertFalse(PageControlsRule.showsInline(windowWidth: 390))
        XCTAssertFalse(PageControlsRule.showsInline(windowWidth: 719))
        XCTAssertTrue(PageControlsRule.showsInline(windowWidth: 720))
        XCTAssertTrue(PageControlsRule.showsInline(windowWidth: 1280))
    }

    func testControlMenuMarksTheSelectedOption() {
        var picked: LibrarySortOption?
        let menu = controlMenu("Sort", options: LibrarySortOption.allCases, selected: .year,
                               label: \.rawValue) { picked = $0 }
        XCTAssertEqual(menu.title, "Sort")
        XCTAssertEqual(menu.value, "Year")
        XCTAssertEqual(menu.choices.map(\.title), ["Name", "Artist", "Year", "Date Added", "Random"])
        XCTAssertEqual(menu.choices.filter(\.selected).map(\.title), ["Year"])
        menu.choices[0].onSelect()
        XCTAssertEqual(picked, .name)
    }

    func testListsFlowIntoTwoColumnsFrom700() {
        XCTAssertEqual(CardGridLayout.listColumns(paneWidth: 699), 1)
        XCTAssertEqual(CardGridLayout.listColumns(paneWidth: 700), 2)
    }
}

final class SearchChipsTests: XCTestCase {
    func testWhatEachChipShows() {
        XCTAssertEqual(SearchFilter.allCases.map(\.rawValue), ["All", "Albums", "Artists", "Playlists"])
        XCTAssertTrue(SearchFilter.all.showsAlbums && SearchFilter.all.showsArtists && SearchFilter.all.showsPlaylists)
        XCTAssertTrue(SearchFilter.albums.showsAlbums)
        XCTAssertFalse(SearchFilter.albums.showsArtists || SearchFilter.albums.showsPlaylists)
        XCTAssertTrue(SearchFilter.artists.showsArtists)
        XCTAssertFalse(SearchFilter.artists.showsAlbums || SearchFilter.artists.showsPlaylists)
        XCTAssertTrue(SearchFilter.playlists.showsPlaylists)
        XCTAssertFalse(SearchFilter.playlists.showsAlbums || SearchFilter.playlists.showsArtists)
    }

    func testWording() {
        XCTAssertEqual(SearchView.artistsHeader(2), "Artists (2)")
        XCTAssertEqual(SearchView.playlistsHeader(1), "Playlists (1)")
        XCTAssertEqual(SearchView.noResultsMessage("zz", filter: .all), "No albums or playlists found for “zz”.")
        XCTAssertEqual(SearchView.noResultsMessage("zz", filter: .artists), "No artists found for “zz”.")
    }

    @MainActor
    func testArtistsComeWithTheAlbumsAndClearWithTheQuery() async throws {
        let model = SearchViewModel(
            downloadedAlbums: { [] }, isOnline: { true },
            search: { _ in [] },
            searchArtists: { _ in [JellyfinArtist(id: "ar", name: "Gruntruck")] })
        model.query = "grunt"
        for _ in 0..<100 where !model.hasSearched { try await Task.sleep(nanoseconds: 50_000_000) }
        XCTAssertEqual(model.artistResults.map(\.id), ["ar"])
        model.query = ""
        XCTAssertEqual(model.artistResults, [])
    }
}

final class HomeRowTests: XCTestCase {
    func testEachAlbumOnce() {
        func album(_ id: String) -> JellyfinAlbum {
            JellyfinAlbum(id: id, name: id, serverId: nil, premiereDate: nil, productionYear: nil,
                          albumArtists: nil, album: nil, imageTags: nil, runTimeTicks: nil,
                          type: "MusicAlbum", userData: nil)
        }
        XCTAssertEqual(HomeView.distinct([album("a"), album("b"), album("a")]).map(\.id), ["a", "b"])
    }
}
