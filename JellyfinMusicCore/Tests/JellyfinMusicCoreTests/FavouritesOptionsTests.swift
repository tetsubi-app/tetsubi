import XCTest
@testable import JellyfinMusicCore

private func album(_ id: String, _ name: String, artist: String = "Artist",
                   premiere: String? = nil, year: Int? = nil) -> JellyfinAlbum {
    JellyfinAlbum(id: id, name: name, serverId: nil, premiereDate: premiere, productionYear: year,
                  albumArtists: [JellyfinPerson(id: nil, name: artist, serverId: nil)],
                  album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
}

/// Library ▸ Favourites' View, Sort and Filter: the sorts, the filter and
/// the remembered choices.
@MainActor
final class FavouritesOptionsTests: XCTestCase {

    private var suite: String!
    private var defaults: UserDefaults!

    override func setUp() {
        suite = "FavouritesOptionsTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suite)
    }

    /// In favourited order (most recent first), as `FavoritesStore.ordered` gives.
    private let favourites = [
        album("1", "Zeitgeist", artist: "Smashing Pumpkins", premiere: "2007-07-10T00:00:00.0000000Z", year: 2007),
        album("2", "adore", artist: "Smashing Pumpkins", year: 1998),
        album("3", "Ágætis byrjun", artist: "Sigur Rós", premiere: "1999-06-12T00:00:00.0000000Z", year: 1999),
        album("4", "1979", artist: "Bowie"),
        album("5", "Mellon Collie", artist: "Smashing Pumpkins", premiere: "1995-10-24T00:00:00.0000000Z", year: 1995),
        album("6", "Gish", artist: "Smashing Pumpkins", year: 1998),
    ]

    private func arrange(_ sort: FavouritesSortOption, filter: LibraryFilter = .all, offline: Bool = false,
                         downloaded: Set<String> = []) -> [String] {
        FavouritesOptions.arrange(favourites, sort: sort, filter: filter, offline: offline,
                                  isDownloaded: { downloaded.contains($0.id) }).map(\.id)
    }

    func testDateFavouritedKeepsFavouritedOrder() {
        XCTAssertEqual(arrange(.dateFavourited), ["1", "2", "3", "4", "5", "6"])
    }

    func testNameArtistYearAreTheAlbumsSorts() {
        for (option, library) in [(FavouritesSortOption.name, LibrarySortOption.name),
                                  (.artist, .artist), (.year, .year)] {
            XCTAssertEqual(arrange(option), LibraryViewModel.sort(favourites, by: library).map(\.id), option.rawValue)
        }
    }

    func testNameIsAToZStripOrder() {
        // # first, then A–Z with accents and case folded.
        XCTAssertEqual(arrange(.name), ["4", "2", "3", "6", "5", "1"])
    }

    /// Newest full date first, else Jan 1 of the year, none last; ties keep
    /// the favourited order.
    func testYearNewestFirstTiesKeepOrder() {
        XCTAssertEqual(arrange(.year), ["1", "3", "2", "6", "5", "4"])
    }

    func testDownloadedFilter() {
        XCTAssertEqual(arrange(.dateFavourited, filter: .downloaded, downloaded: ["5", "2"]), ["2", "5"])
        XCTAssertEqual(arrange(.name, filter: .downloaded, downloaded: ["5", "2"]), ["2", "5"])
        XCTAssertEqual(arrange(.dateFavourited, filter: .all, downloaded: ["5", "2"]).count, 6)
    }

    func testOfflineShowsDownloadedOnlyWhateverTheFilter() {
        XCTAssertEqual(arrange(.dateFavourited, filter: .all, offline: true, downloaded: ["6", "1"]), ["1", "6"])
    }

    func testIndexKeyForAlphabeticalSortsOnly() {
        let options = FavouritesOptions(defaults: defaults)
        XCTAssertNil(options.indexKey)
        options.sortOption = .name
        XCTAssertEqual(options.indexKey?(favourites[0]), "Zeitgeist")
        options.sortOption = .artist
        XCTAssertEqual(options.indexKey?(favourites[0]), "Smashing Pumpkins")
        options.sortOption = .year
        XCTAssertNil(options.indexKey)
    }

    func testDefaults() {
        let options = FavouritesOptions(defaults: defaults)
        XCTAssertEqual(options.displayMode, .grid)
        XCTAssertEqual(options.sortOption, .dateFavourited)
        XCTAssertEqual(options.filter, .all)
    }

    func testRemembersChoicesUnderSharedKeys() {
        let options = FavouritesOptions(defaults: defaults)
        options.displayMode = .list
        options.sortOption = .year
        options.filter = .downloaded
        XCTAssertEqual(defaults.string(forKey: "library.favouritesViewMode"), "List")
        XCTAssertEqual(defaults.string(forKey: "library.favouritesSort"), "Year")
        XCTAssertEqual(defaults.string(forKey: "library.favouritesFilter"), "Downloaded")

        let next = FavouritesOptions(defaults: defaults)
        XCTAssertEqual(next.displayMode, .list)
        XCTAssertEqual(next.sortOption, .year)
        XCTAssertEqual(next.filter, .downloaded)

        next.sortOption = .dateFavourited
        XCTAssertEqual(defaults.string(forKey: "library.favouritesSort"), "Date Favourited")
        XCTAssertEqual(FavouritesOptions(defaults: defaults).sortOption, .dateFavourited)
    }

    func testUnknownValuesFallBack() {
        defaults.set("Mosaic", forKey: "library.favouritesViewMode")
        defaults.set("Date Added", forKey: "library.favouritesSort")   // an Albums sort, not one of these
        defaults.set("Streamed", forKey: "library.favouritesFilter")
        let options = FavouritesOptions(defaults: defaults)
        XCTAssertEqual(options.displayMode, .grid)
        XCTAssertEqual(options.sortOption, .dateFavourited)
        XCTAssertEqual(options.filter, .all)
    }

    func testWrongTypeFallsBack() {
        defaults.set(3, forKey: "library.favouritesViewMode")
        defaults.set(99, forKey: "library.favouritesSort")
        defaults.set(true, forKey: "library.favouritesFilter")
        let options = FavouritesOptions(defaults: defaults)
        XCTAssertEqual(options.displayMode, .grid)
        XCTAssertEqual(options.sortOption, .dateFavourited)
        XCTAssertEqual(options.filter, .all)
    }

    func testAlbumsChoicesUntouched() {
        let options = FavouritesOptions(defaults: defaults)
        options.displayMode = .list
        options.sortOption = .artist
        XCTAssertNil(defaults.string(forKey: "library.viewMode"))
        XCTAssertNil(defaults.string(forKey: "library.sort"))
    }
}
