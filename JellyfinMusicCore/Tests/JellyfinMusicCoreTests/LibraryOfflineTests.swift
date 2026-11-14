import XCTest
@testable import JellyfinMusicCore

private func album(_ id: String, _ name: String, artist: String = "Artist") -> JellyfinAlbum {
    JellyfinAlbum(id: id, name: name, serverId: nil, premiereDate: nil, productionYear: nil,
                  albumArtists: [JellyfinPerson(id: nil, name: artist, serverId: nil)],
                  album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
}

private let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")

/// Library ▸ tab, sort and view are remembered across launches.
@MainActor
final class LibrarySavedChoicesTests: XCTestCase {

    private var suite: String!
    private var defaults: UserDefaults!

    override func setUp() {
        suite = "LibrarySavedChoicesTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suite)
    }

    func testDefaultsWhenNothingSaved() {
        let model = LibraryViewModel(api: api, defaults: defaults)
        XCTAssertEqual(model.tab, .albums)
        XCTAssertEqual(model.sortOption, .name)
        XCTAssertEqual(model.displayMode, .grid)
    }

    func testSavesUnderSharedKeysAsRawValues() {
        let model = LibraryViewModel(api: api, defaults: defaults)
        model.tab = .favourites
        model.sortOption = .dateAdded
        model.displayMode = .list
        model.filter = .downloaded
        XCTAssertEqual(defaults.string(forKey: "library.tab"), "Favourites")
        XCTAssertEqual(defaults.string(forKey: "library.sort"), "Date Added")
        XCTAssertEqual(defaults.string(forKey: "library.viewMode"), "List")

        let next = LibraryViewModel(api: api, defaults: defaults)
        XCTAssertEqual(next.tab, .favourites)
        XCTAssertEqual(next.sortOption, .dateAdded)
        XCTAssertEqual(next.displayMode, .list)
        // The filter isn't remembered.
        XCTAssertEqual(next.filter, .all)
    }

    func testUnknownValuesFallBack() {
        defaults.set("Genres", forKey: "library.tab")
        defaults.set("DATE_ADDED", forKey: "library.sort")
        defaults.set(3, forKey: "library.viewMode")
        let model = LibraryViewModel(api: api, defaults: defaults)
        XCTAssertEqual(model.tab, .albums)
        XCTAssertEqual(model.sortOption, .name)
        XCTAssertEqual(model.displayMode, .grid)
    }
}

/// Library offline: downloaded albums only.
@MainActor
final class LibraryOfflineTests: XCTestCase {

    private func model() -> LibraryViewModel {
        let suite = "LibraryOfflineTests-\(UUID().uuidString)"
        let model = LibraryViewModel(api: api, defaults: UserDefaults(suiteName: suite)!)
        UserDefaults().removePersistentDomain(forName: suite)
        model.receive([album("a", "Gish"), album("b", "Siamese Dream"), album("c", "Adore")])
        model.downloadsChanged([album("b", "Siamese Dream"), album("d", "Zeitgeist")])
        return model
    }

    func testOfflineShowsDownloadsOnlyAndOnlineTheLibrary() {
        let model = model()
        XCTAssertEqual(model.sortedAlbums.map(\.id), ["c", "a", "b"])
        model.networkChanged(online: false)
        XCTAssertTrue(model.isOffline)
        XCTAssertEqual(model.sortedAlbums.map(\.id), ["b", "d"])
        model.networkChanged(online: true)
        XCTAssertFalse(model.isOffline)
        XCTAssertEqual(model.sortedAlbums.map(\.id), ["c", "a", "b"])
    }

    func testOfflineDoesNotLoad() {
        let model = model()
        model.networkChanged(online: false)
        model.loadLibrary()
        XCTAssertFalse(model.isLoading)
        XCTAssertNil(model.errorMessage)
    }

    func testDownloadedAlbumsFromSavedInfoKeepOrderAndMergeDiscs() {
        let info: [String: (title: String, artist: String)] = [
            "1": ("Mellon Collie (Disc 1)", "Smashing Pumpkins"),
            "2": ("Mellon Collie (Disc 2)", "Smashing Pumpkins"),
            "3": ("Gish", "Smashing Pumpkins"),
        ]
        let albums = LibraryViewModel.downloadedAlbums(ids: ["3", "1", "2", "4"]) { info[$0] }
        XCTAssertEqual(albums.map(\.id), ["3", "1", "4"])
        XCTAssertEqual(albums[1].allDiscs.map(\.id), ["1", "2"])
        XCTAssertEqual(albums[0].artistName, "Smashing Pumpkins")
    }

    func testFavouritesOfflineOnlyDownloaded() {
        let favs = [album("a", "Gish"), album("b", "Adore")]
        let downloaded: (JellyfinAlbum) -> Bool = { $0.id == "b" }
        XCTAssertEqual(LibraryOffline.favourites(favs, offline: false, isDownloaded: downloaded).map(\.id), ["a", "b"])
        XCTAssertEqual(LibraryOffline.favourites(favs, offline: true, isDownloaded: downloaded).map(\.id), ["b"])
    }

    func testArtistPageOffline() {
        let theirs = [album("a", "Gish", artist: "Smashing Pumpkins"), album("b", "Adore", artist: "Smashing Pumpkins")]
        let downloads = [album("b", "Adore", artist: "smashing pumpkins"), album("x", "Other", artist: "Someone")]
        let downloaded: (JellyfinAlbum) -> Bool = { $0.id == "b" }
        XCTAssertEqual(LibraryOffline.artistAlbums(theirs, artist: "Smashing Pumpkins", offline: false,
                                                   downloaded: { downloads }, isDownloaded: downloaded).map(\.id), ["a", "b"])
        XCTAssertEqual(LibraryOffline.artistAlbums(theirs, artist: "Smashing Pumpkins", offline: true,
                                                   downloaded: { downloads }, isDownloaded: downloaded).map(\.id), ["b"])
        // Never loaded: their downloads, by name.
        XCTAssertEqual(LibraryOffline.artistAlbums([], artist: "Smashing Pumpkins", offline: true,
                                                   downloaded: { downloads }, isDownloaded: downloaded).map(\.id), ["b"])
    }
}

/// Search: offline matches track titles; back online, the server is asked again.
@MainActor
final class OfflineSearchTests: XCTestCase {

    func testLocalSearchMatchesTrackTitles() {
        let albums = [
            LocalAlbum(id: "1", title: "Siamese Dream", artist: "Smashing Pumpkins", trackTitles: ["Cherub Rock", "Today"]),
            LocalAlbum(id: "2", title: "Gish", artist: "Smashing Pumpkins", trackTitles: ["I Am One"]),
        ]
        XCTAssertEqual(LocalSearch.matches("cherub", in: albums).map(\.id), ["1"])
        // Words may come from different places.
        XCTAssertEqual(LocalSearch.matches("pumpkins today", in: albums).map(\.id), ["1"])
        XCTAssertEqual(LocalSearch.matches("rock today", in: albums).map(\.id), ["1"])
        XCTAssertEqual(LocalSearch.matches("tôday", in: albums).map(\.id), ["1"])
        XCTAssertEqual(LocalSearch.matches("zero", in: albums), [])
    }

    func testReconnectSearchesTheServerAgain() async throws {
        let suite = "OfflineSearchTests-\(UUID().uuidString)"
        SearchHistory.defaults = UserDefaults(suiteName: suite)!
        defer {
            UserDefaults().removePersistentDomain(forName: suite)
            SearchHistory.defaults = .standard
        }
        var online = false
        var searched: [String] = []
        let model = SearchViewModel(
            downloadedAlbums: { [LocalAlbum(id: "1", title: "Gish", artist: "Smashing Pumpkins")] },
            isOnline: { online },
            search: { term in
                await MainActor.run { searched.append(term) }
                return [album("s", "Gish")]
            })
        model.query = "gish"
        try await waitUntil { model.hasSearched }
        XCTAssertTrue(model.isOfflineResults)
        XCTAssertEqual(model.localResults.map(\.id), ["1"])
        XCTAssertEqual(searched, [])

        // Still offline: nothing happens.
        model.networkChanged(online: false)
        XCTAssertTrue(model.isOfflineResults)

        online = true
        model.networkChanged(online: true)
        try await waitUntil { !model.isOfflineResults }
        XCTAssertEqual(searched, ["gish"])
        XCTAssertEqual(model.results.map(\.id), ["s"])
        XCTAssertEqual(model.localResults, [])
    }

    private func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<100 where !condition() {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertTrue(condition())
    }
}
