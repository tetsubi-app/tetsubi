// Playlists in Search: the request, its answer, the results beside the
// albums, and matching downloaded playlists with no server.

import XCTest
@testable import JellyfinMusicCore

@MainActor
final class PlaylistSearchTests: XCTestCase {

    private var root: URL!
    private var suite = ""

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("playlist-search-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        DownloadPaths.rootDirOverride = root
        suite = "PlaylistSearchTests-\(UUID().uuidString)"
        SearchHistory.defaults = UserDefaults(suiteName: suite)!
    }

    override func tearDown() async throws {
        DownloadPaths.rootDirOverride = nil
        try? FileManager.default.removeItem(at: root)
        UserDefaults().removePersistentDomain(forName: suite)
        SearchHistory.defaults = .standard
    }

    private func makeAPI() -> JellyfinAPI {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")
        api.setUserId("user-1")
        return api
    }

    private func response(_ status: Int) -> HTTPURLResponse {
        HTTPURLResponse(url: URL(string: "http://jellyfin-box:8096/x")!, statusCode: status,
                        httpVersion: nil, headerFields: nil)!
    }

    private func album(_ id: String, _ name: String) -> JellyfinAlbum {
        JellyfinAlbum(id: id, name: name, serverId: nil, premiereDate: nil, productionYear: nil,
                      albumArtists: [JellyfinPerson(id: "ar", name: "Artist", serverId: nil)],
                      album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
    }

    private func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<100 where !condition() {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertTrue(condition())
    }

    // MARK: - API

    func testSearchPlaylistsRequestShape() throws {
        let request = try XCTUnwrap(makeAPI().searchPlaylistsRequest(term: "road trip"))
        XCTAssertEqual(request.httpMethod ?? "GET", "GET")
        XCTAssertEqual(request.url?.path, "/Users/user-1/Items")
        let items = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") }), [
            "SearchTerm": "road trip",
            "IncludeItemTypes": "Playlist",
            "MediaTypes": "Audio",
            "Recursive": "true",
            "Fields": "ChildCount,CumulativeRunTimeTicks,DateCreated"
        ])
        XCTAssertTrue(request.url!.absoluteString.contains("SearchTerm=road%20trip"))
        let auth = request.value(forHTTPHeaderField: "Authorization") ?? ""
        XCTAssertTrue(auth.contains("MediaBrowser") && auth.contains("test-token"), auth)
    }

    func testSearchPlaylistsNeedsAUserAndATerm() {
        let signedOut = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")
        XCTAssertNil(signedOut.searchPlaylistsRequest(term: "mix"))
        let failed = expectation(description: "fails without asking the server")
        signedOut.searchPlaylists(term: "mix") { result in
            if case .failure(.missingCredentials) = result { failed.fulfill() }
        }
        // A blank term finds nothing, and the server isn't asked.
        let blank = expectation(description: "nothing for a blank term")
        makeAPI().searchPlaylists(term: "   ") { result in
            if case .success(let found) = result, found.isEmpty { blank.fulfill() }
        }
        wait(for: [failed, blank], timeout: 1)
    }

    func testSearchPlaylistsAnswerDecodes() throws {
        // As the server answers a search: the listing's fields, plus ones
        // the app doesn't read.
        let answer = """
        {"Items":[
          {"Name":"Road Trip","ServerId":"s1","Id":"pl-1","DateCreated":"2026-11-11T23:28:15.0000000Z",
           "CumulativeRunTimeTicks":43200000000,"RunTimeTicks":43200000000,"ChildCount":12,"IsFolder":true,
           "ImageTags":{"Primary":"0da9f1ae"},"Type":"Playlist","MediaType":"Audio","LocationType":"FileSystem"},
          {"Name":"Road songs","Id":"pl-2","ChildCount":1,"ImageTags":{},"Type":"Playlist","MediaType":"Audio"}
        ],"TotalRecordCount":2,"StartIndex":0}
        """
        let found = try JellyfinAPI.playlistsResult(data: Data(answer.utf8), response: response(200), error: nil).get()
        XCTAssertEqual(found.map(\.id), ["pl-1", "pl-2"])
        XCTAssertEqual(found[0].name, "Road Trip")
        XCTAssertEqual(found[0].summary, "12 songs · 1 h 12 min")
        XCTAssertTrue(found[0].hasArt)
        XCTAssertEqual(found[1].summary, "1 song")
        XCTAssertFalse(found[1].hasArt)

        let none = #"{"Items":[],"TotalRecordCount":0,"StartIndex":0}"#
        XCTAssertEqual(try JellyfinAPI.playlistsResult(data: Data(none.utf8), response: response(200), error: nil).get(), [])
        if case .success = JellyfinAPI.playlistsResult(data: Data(), response: response(500), error: nil) {
            XCTFail("a server error isn't an empty answer")
        }
    }

    // MARK: - Matching downloaded playlists

    func testDownloadedPlaylistsMatchByName() {
        let lists = [JellyfinPlaylist(id: "1", name: "Road Trip", songCount: 3),
                     JellyfinPlaylist(id: "2", name: "Café del Mar", songCount: 2),
                     JellyfinPlaylist(id: "3", name: "Trip-hop for the road", songCount: 1)]
        XCTAssertEqual(LocalSearch.matches("road", in: lists).map(\.id), ["1", "3"], "in the order given")
        XCTAssertEqual(LocalSearch.matches("TRIP road", in: lists).map(\.id), ["1", "3"], "every word, any order")
        XCTAssertEqual(LocalSearch.matches("cafe", in: lists).map(\.id), ["2"], "accents don't matter")
        XCTAssertEqual(LocalSearch.matches("ro", in: lists).map(\.id), ["1", "3"], "part of a word")
        XCTAssertEqual(LocalSearch.matches("road mar", in: lists), [])
        XCTAssertEqual(LocalSearch.matches("  ", in: lists), [])
    }

    /// Only playlists that finished downloading at least once count.
    func testThePlaylistsOnDiskThatAreListedOffline() throws {
        let row = PlaylistManifest.Entry(
            PlaylistEntry(position: 0, entryId: "e0",
                          track: JellyfinTrack(id: "a", name: "Song", serverId: nil, indexNumber: 1,
                                               runTimeTicks: 600_000_000, album: "Album", albumId: "al",
                                               albumArtists: nil, imageTags: nil, mediaSource: nil),
                          albumName: "Album", albumId: "al", albumArtist: "Artist"),
            format: .flac)
        try PlaylistDownloadPaths.save(PlaylistManifest(id: "done", name: "Road Trip", entries: [row],
                                                        downloadedAt: Date(timeIntervalSince1970: 1_700_000_000)))
        try PlaylistDownloadPaths.save(PlaylistManifest(id: "half", name: "Road Works", entries: [row]))
        let listed = PlaylistDownloadPaths.offlinePlaylists()
        XCTAssertEqual(listed.map(\.id), ["done"])
        XCTAssertEqual(listed.first?.summary, "1 song · 1 min")
        XCTAssertEqual(LocalSearch.matches("road", in: listed).map(\.name), ["Road Trip"])
    }

    // MARK: - The search page's results

    func testOnlinePlaylistsComeWithTheAlbums() async throws {
        var asked: [String] = []
        let model = SearchViewModel(
            downloadedAlbums: { [] }, isOnline: { true },
            search: { [self] _ in await [album("al", "Road Movies")] },
            downloadedPlaylists: { [JellyfinPlaylist(id: "saved", name: "Road Saved")] },
            searchPlaylists: { term in
                await MainActor.run { asked.append(term) }
                return [JellyfinPlaylist(id: "pl-1", name: "Road Trip", songCount: 12)]
            })
        model.query = " road "
        try await waitUntil { model.hasSearched }
        XCTAssertEqual(asked, ["road"])
        XCTAssertEqual(model.playlistResults.map(\.id), ["pl-1"], "the server's, not the downloads")
        XCTAssertEqual(model.results.map(\.id), ["al"])
        XCTAssertFalse(model.isOfflineResults)
        XCTAssertEqual(model.localPlaylistResults, [])

        model.query = ""
        XCTAssertEqual(model.playlistResults, [])
        XCTAssertFalse(model.hasSearched)
    }

    func testAPlaylistSearchThatFailsStillShowsTheAlbums() async throws {
        let model = SearchViewModel(
            downloadedAlbums: { [] }, isOnline: { true },
            search: { [self] _ in await [album("al", "Road Movies")] },
            searchPlaylists: { _ in throw JellyfinError.http(500, "") })
        model.query = "road"
        try await waitUntil { model.hasSearched }
        XCTAssertFalse(model.isOfflineResults)
        XCTAssertEqual(model.results.map(\.id), ["al"])
        XCTAssertEqual(model.playlistResults, [])
    }

    func testOnlyPlaylistsMatching() async throws {
        let model = SearchViewModel(
            downloadedAlbums: { [] }, isOnline: { true },
            search: { _ in [] },
            searchPlaylists: { _ in [JellyfinPlaylist(id: "pl-1", name: "Road Trip")] })
        model.query = "road"
        try await waitUntil { model.hasSearched }
        XCTAssertEqual(model.results, [])
        XCTAssertEqual(model.playlistResults.map(\.id), ["pl-1"])
    }

    func testOfflineDownloadedPlaylistsAreSearchedByName() async throws {
        var asked = 0
        var online = false
        let model = SearchViewModel(
            downloadedAlbums: { [LocalAlbum(id: "1", title: "Road Movies", artist: "Artist")] },
            isOnline: { online },
            search: { _ in [] },
            downloadedPlaylists: { [JellyfinPlaylist(id: "saved", name: "Road Trip", songCount: 3),
                                    JellyfinPlaylist(id: "other", name: "Quiet", songCount: 1)] },
            searchPlaylists: { _ in
                await MainActor.run { asked += 1 }
                return [JellyfinPlaylist(id: "pl-1", name: "Road Trip (server)")]
            })
        model.query = "road"
        try await waitUntil { model.hasSearched }
        XCTAssertTrue(model.isOfflineResults)
        XCTAssertEqual(model.localPlaylistResults.map(\.id), ["saved"])
        XCTAssertEqual(model.localResults.map(\.id), ["1"])
        XCTAssertEqual(model.playlistResults, [])
        XCTAssertEqual(asked, 0, "the server isn't asked offline")

        // Back online: the server's answer replaces the downloads.
        online = true
        model.networkChanged(online: true)
        try await waitUntil { !model.isOfflineResults }
        XCTAssertEqual(model.playlistResults.map(\.id), ["pl-1"])
        XCTAssertEqual(model.localPlaylistResults, [])
    }

    /// The server can't be reached though the device is connected.
    func testAFailedSearchFallsBackToDownloadedPlaylists() async throws {
        let model = SearchViewModel(
            downloadedAlbums: { [] }, isOnline: { true },
            search: { _ in throw JellyfinError.http(503, "") },
            downloadedPlaylists: { [JellyfinPlaylist(id: "saved", name: "Road Trip")] },
            searchPlaylists: { _ in throw JellyfinError.http(503, "") })
        model.query = "trip"
        try await waitUntil { model.hasSearched }
        XCTAssertTrue(model.isOfflineResults)
        XCTAssertEqual(model.localPlaylistResults.map(\.id), ["saved"])
    }

    // MARK: - Wording

    func testWording() {
        XCTAssertEqual(SearchView.albumsHeader(albums: 3, playlists: 0), "Results (3)")
        XCTAssertEqual(SearchView.albumsHeader(albums: 3, playlists: 1), "Albums (3)")
        XCTAssertEqual(SearchView.noResultsMessage("zzz"), "No albums or playlists found for “zzz”.")
        XCTAssertEqual(SearchView.noDownloadsMessage("zzz"), "No downloaded albums or playlists match “zzz”.")
        XCTAssertEqual(LibraryTab.playlists.rawValue, "Playlists")
        XCTAssertEqual(AppCommands.playlistsShortcut, "5")
        XCTAssertFalse(AppTab.allCases.compactMap(\.shortcut).contains(AppCommands.playlistsShortcut))
    }
}
