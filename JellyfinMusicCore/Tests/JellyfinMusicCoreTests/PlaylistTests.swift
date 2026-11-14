import XCTest
@testable import JellyfinMusicCore

// MARK: - Fixtures

/// Server answers as recorded from a Jellyfin server (trimmed).
private enum PlaylistFixtures {
    static let list = """
    {"Items":[
      {"Name":"Tetsubi test renamed","Id":"03fa46cb02bc9de9396da4258c2a84ff",
       "DateCreated":"2026-11-11T23:28:15.0000000Z","CumulativeRunTimeTicks":10143941948,
       "RunTimeTicks":10143941948,"ChildCount":4,
       "ImageTags":{"Primary":"0da9f1ae7eaac8ac1c7c526f652bd931"},"Type":"Playlist","MediaType":"Audio",
       "UserData":{"PlaybackPositionTicks":0,"PlayCount":0,"IsFavorite":false,"Played":false}},
      {"Name":"No collage yet","Id":"aa11","DateCreated":"2026-10-01T08:00:00.0000000Z",
       "ChildCount":1,"ImageTags":{},"Type":"Playlist","MediaType":"Audio"}
    ],"TotalRecordCount":2,"StartIndex":0}
    """

    static let empty = #"{"Items":[],"TotalRecordCount":0,"StartIndex":0}"#

    private static let simple = """
    {"Name":"Perfectly Simple","Id":"a85d5e83dfed72d4a3e6f266d8dfd3f2",
     "PlaylistItemId":"a85d5e83dfed72d4a3e6f266d8dfd3f2","AlbumId":"19a8bfe4dbe882ef56f712c13e7c6de1",
     "Album":"...out flew reason","AlbumArtist":"dark star","IndexNumber":1,"RunTimeTicks":3071437639,
     "NormalizationGain":-10.1,"AlbumPrimaryImageTag":"744e3f0e15d44cdd51ae0203992cef66",
     "Type":"Audio","MediaType":"Audio"}
    """
    private static let roman = """
    {"Name":"Roman Road","Id":"4ac3003946a71d6e72fc9489b38171c7",
     "PlaylistItemId":"4ac3003946a71d6e72fc9489b38171c7","AlbumId":"19a8bfe4dbe882ef56f712c13e7c6de1",
     "Album":"...out flew reason","AlbumArtist":"dark star","IndexNumber":2,"RunTimeTicks":2241462130,
     "NormalizationGain":-11,"Type":"Audio","MediaType":"Audio"}
    """
    private static let other = """
    {"Name":"Elsewhere","Id":"e1","PlaylistItemId":"e1","AlbumId":"album-2","Album":"Another Album",
     "AlbumArtist":"Someone Else","IndexNumber":7,"RunTimeTicks":600000000,"Type":"Audio","MediaType":"Audio"}
    """

    /// Two albums, with "Perfectly Simple" listed twice (same IDs both times).
    static let tracks = #"{"Items":[\#(simple),\#(roman),\#(other),\#(simple)],"TotalRecordCount":4,"StartIndex":0}"#

    static func entries() throws -> [PlaylistEntry] {
        try JellyfinAPI.playlistTracksResult(data: Data(tracks.utf8), response: response(200), error: nil).get()
    }

    static func response(_ status: Int) -> HTTPURLResponse {
        HTTPURLResponse(url: URL(string: "http://jellyfin-box:8096/x")!, statusCode: status,
                        httpVersion: nil, headerFields: nil)!
    }

    static let playlist = JellyfinPlaylist(id: "03fa46cb02bc9de9396da4258c2a84ff", name: "Tetsubi test renamed",
                                           songCount: 4, runTimeTicks: 10143941948)
}

// MARK: - API and models

final class PlaylistAPITests: XCTestCase {

    private func makeAPI() -> JellyfinAPI {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")
        api.setUserId("user-1")
        return api
    }

    private func query(_ request: URLRequest) -> [String: String] {
        let items = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
    }

    func testPlaylistsRequestShape() throws {
        let request = try XCTUnwrap(makeAPI().playlistsRequest())
        XCTAssertEqual(request.httpMethod ?? "GET", "GET")
        XCTAssertEqual(request.url?.path, "/Users/user-1/Items")
        XCTAssertEqual(query(request), [
            "IncludeItemTypes": "Playlist",
            "MediaTypes": "Audio",
            "Recursive": "true",
            "Fields": "ChildCount,CumulativeRunTimeTicks,DateCreated"
        ])
        let auth = request.value(forHTTPHeaderField: "Authorization") ?? ""
        XCTAssertTrue(auth.contains("MediaBrowser"))
        XCTAssertTrue(auth.contains("test-token"), "same signed-in header as the other calls: \(auth)")
    }

    func testPlaylistTracksRequestShape() throws {
        let request = try XCTUnwrap(makeAPI().playlistTracksRequest("pl-9"))
        XCTAssertEqual(request.httpMethod ?? "GET", "GET")
        XCTAssertEqual(request.url?.path, "/Playlists/pl-9/Items")
        XCTAssertEqual(query(request), ["userId": "user-1", "Fields": "MediaSources,NormalizationGain"])
        XCTAssertTrue((request.value(forHTTPHeaderField: "Authorization") ?? "").contains("test-token"))
    }

    func testNoRequestsWithoutAUser() {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")
        XCTAssertNil(api.playlistsRequest())
        XCTAssertNil(api.playlistTracksRequest("pl-9"))
        let answered = expectation(description: "fails without asking the server")
        api.playlistTracks("pl-9") { result in
            if case .failure(.missingCredentials) = result { answered.fulfill() }
        }
        wait(for: [answered], timeout: 1)
    }

    func testPlaylistListDecodes() throws {
        let playlists = try JellyfinAPI.playlistsResult(
            data: Data(PlaylistFixtures.list.utf8), response: PlaylistFixtures.response(200), error: nil).get()
        XCTAssertEqual(playlists.count, 2)
        let first = playlists[0]
        XCTAssertEqual(first.id, "03fa46cb02bc9de9396da4258c2a84ff")
        XCTAssertEqual(first.name, "Tetsubi test renamed")
        XCTAssertEqual(first.songCount, 4)
        XCTAssertEqual(first.runTimeTicks, 10143941948)
        XCTAssertEqual(first.duration, 1014)
        XCTAssertTrue(first.hasArt)
        XCTAssertEqual(first.summary, "4 songs · 16 min")
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let parts = utc.dateComponents([.year, .month, .day, .hour, .minute, .second], from: try XCTUnwrap(first.dateCreated))
        XCTAssertEqual([parts.year, parts.month, parts.day, parts.hour, parts.minute, parts.second],
                       [2026, 11, 11, 23, 28, 15])
    }

    /// `ImageTags` is `{}` until the server has made the collage.
    func testEmptyImageTagsMeansNoArt() throws {
        let playlists = try JellyfinAPI.playlistsResult(
            data: Data(PlaylistFixtures.list.utf8), response: PlaylistFixtures.response(200), error: nil).get()
        XCTAssertFalse(playlists[1].hasArt)
        XCTAssertEqual(playlists[1].runTimeTicks, 0)
        XCTAssertEqual(playlists[1].summary, "1 song")
    }

    func testEmptyListDecodes() throws {
        let playlists = try JellyfinAPI.playlistsResult(
            data: Data(PlaylistFixtures.empty.utf8), response: PlaylistFixtures.response(200), error: nil).get()
        XCTAssertTrue(playlists.isEmpty)
        let entries = try JellyfinAPI.playlistTracksResult(
            data: Data(PlaylistFixtures.empty.utf8), response: PlaylistFixtures.response(200), error: nil).get()
        XCTAssertTrue(entries.isEmpty)
    }

    func testTracksDecodeWithTheirOwnAlbums() throws {
        let entries = try PlaylistFixtures.entries()
        XCTAssertEqual(entries.map(\.track.id),
                       ["a85d5e83dfed72d4a3e6f266d8dfd3f2", "4ac3003946a71d6e72fc9489b38171c7", "e1",
                        "a85d5e83dfed72d4a3e6f266d8dfd3f2"])
        XCTAssertEqual(entries[0].track.displayName, "Perfectly Simple")
        XCTAssertEqual(entries[0].albumName, "...out flew reason")
        XCTAssertEqual(entries[0].albumId, "19a8bfe4dbe882ef56f712c13e7c6de1")
        XCTAssertEqual(entries[0].albumArtist, "dark star")
        XCTAssertEqual(entries[0].detailLine, "dark star · ...out flew reason")
        XCTAssertEqual(entries[0].artItemId, "19a8bfe4dbe882ef56f712c13e7c6de1")
        XCTAssertEqual(entries[0].track.normalizationGain, -10.1)
        XCTAssertEqual(entries[0].track.duration, 307.1437639, accuracy: 1)
        XCTAssertEqual(entries[2].detailLine, "Someone Else · Another Album")
    }

    /// The server gives both rows of a song listed twice the same IDs;
    /// they're told apart by position.
    func testASongListedTwiceIsTwoRows() throws {
        let entries = try PlaylistFixtures.entries()
        XCTAssertEqual(entries.count, 4)
        XCTAssertEqual(entries[0].entryId, entries[3].entryId)
        XCTAssertEqual(entries[0].track.id, entries[3].track.id)
        XCTAssertEqual(entries.map(\.position), [0, 1, 2, 3])
        XCTAssertEqual(Set(entries.map(\.id)).count, 4, "list identities are all different")
    }

    func testAMissingPlaylistIsA404() {
        let result = JellyfinAPI.playlistTracksResult(
            data: Data("Not Found".utf8), response: PlaylistFixtures.response(404), error: nil)
        guard case .failure(.http(let code, _)) = result else {
            return XCTFail("expected an HTTP error, got \(result)")
        }
        XCTAssertEqual(code, 404)
    }

    func testANetworkFailureIsReported() {
        let result = JellyfinAPI.playlistsResult(data: nil, response: nil, error: URLError(.notConnectedToInternet))
        guard case .failure(.network) = result else { return XCTFail("expected a network error, got \(result)") }
    }

    // MARK: Counts and wording

    func testCountsAndRunningTimeComeFromTheLoadedTracks() throws {
        let entries = try PlaylistFixtures.entries()
        // 307.1 + 224.1 + 60 + 307.1 seconds.
        XCTAssertEqual(entries.totalDuration, 898.4, accuracy: 1)
        XCTAssertEqual(PlaylistDetailViewModel.summary(of: entries), "4 songs · 14 min")
        XCTAssertEqual(PlaylistDetailViewModel.summary(of: Array(entries.prefix(1))), "1 song · 5 min")
        XCTAssertEqual(PlaylistDetailViewModel.summary(of: []), "0 songs")
    }

    func testWording() {
        XCTAssertEqual(JellyfinPlaylist.songCountText(1), "1 song")
        XCTAssertEqual(JellyfinPlaylist.songCountText(12), "12 songs")
        XCTAssertEqual(JellyfinPlaylist.durationText(72 * 60), "1 h 12 min")
        XCTAssertEqual(JellyfinPlaylist.durationText(47 * 60 + 59), "47 min")
        XCTAssertEqual(JellyfinPlaylist.durationText(2 * 3600), "2 h")
        XCTAssertEqual(JellyfinPlaylist.durationText(20), "1 min")
        XCTAssertEqual(JellyfinPlaylist.durationText(0), "")
        XCTAssertEqual(JellyfinPlaylist.summary(songCount: 12, duration: 72 * 60), "12 songs · 1 h 12 min")
    }

    // MARK: Art

    func testArtIsThePlaylistsOwnThenTheFirstTracksAlbum() throws {
        let entries = try PlaylistFixtures.entries()
        let withArt = JellyfinPlaylist(id: "pl", name: "A", hasArt: true)
        let without = JellyfinPlaylist(id: "pl", name: "A")
        XCTAssertEqual(PlaylistArt.itemId(for: withArt, firstEntry: entries.first), "pl")
        XCTAssertEqual(PlaylistArt.itemId(for: without, firstEntry: entries.first), "19a8bfe4dbe882ef56f712c13e7c6de1")
        XCTAssertNil(PlaylistArt.itemId(for: without, firstEntry: nil), "the placeholder")
    }

    // MARK: Sorting

    func testSortByName() {
        let lists = [
            JellyfinPlaylist(id: "1", name: "zebra"),
            JellyfinPlaylist(id: "2", name: "Élan"),
            JellyfinPlaylist(id: "3", name: "apple"),
            JellyfinPlaylist(id: "4", name: "Mix 10"),
            JellyfinPlaylist(id: "5", name: "Mix 2")
        ]
        XCTAssertEqual(PlaylistsViewModel.sorted(lists, by: .name).map(\.name),
                       ["apple", "Élan", "Mix 2", "Mix 10", "zebra"])
    }

    func testSortByDateAddedIsNewestFirst() {
        let day: TimeInterval = 86_400
        let base = Date(timeIntervalSince1970: 1_800_000_000)
        let lists = [
            JellyfinPlaylist(id: "1", name: "Old", dateCreated: base),
            JellyfinPlaylist(id: "2", name: "Undated"),
            JellyfinPlaylist(id: "3", name: "New", dateCreated: base + 2 * day),
            JellyfinPlaylist(id: "4", name: "B same day", dateCreated: base + day),
            JellyfinPlaylist(id: "5", name: "A same day", dateCreated: base + day)
        ]
        XCTAssertEqual(PlaylistsViewModel.sorted(lists, by: .dateAdded).map(\.name),
                       ["New", "A same day", "B same day", "Old", "Undated"])
    }

    func testSortOptionsAndLibraryTabs() {
        XCTAssertEqual(PlaylistSortOption.allCases.map(\.rawValue), ["Name", "Date Added"])
        XCTAssertEqual(LibraryTab.allCases.map(\.rawValue), ["Albums", "Artists", "Favourites", "Playlists"])
    }

    @MainActor
    func testSortAndViewAreRemembered() {
        let suite = "PlaylistTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let model = PlaylistsViewModel(api: makeAPI(), defaults: defaults)
        XCTAssertEqual(model.sortOption, .name)
        XCTAssertEqual(model.displayMode, .grid)
        model.sortOption = .dateAdded
        model.displayMode = .list
        let next = PlaylistsViewModel(api: makeAPI(), defaults: defaults)
        XCTAssertEqual(next.sortOption, .dateAdded)
        XCTAssertEqual(next.displayMode, .list)
    }

    /// Offline, the server isn't asked and nothing is left spinning.
    @MainActor
    func testLoadingOfflineAsksNothing() {
        let model = PlaylistsViewModel(api: makeAPI(), defaults: UserDefaults(suiteName: "PlaylistTests-offline")!)
        model.load(online: false)
        XCTAssertFalse(model.isLoading)
        XCTAssertTrue(model.playlists.isEmpty)
        XCTAssertNil(model.errorMessage)
    }
}

// MARK: - Queue

/// Playing a playlist. Nothing here reaches a server: the address is a
/// closed local port, and the checks are on the queue that was built.
@MainActor
final class PlaylistQueueTests: XCTestCase {

    private var suite = ""
    private var player: PlayerManager!
    private var entries: [PlaylistEntry] = []
    private let playlist = PlaylistFixtures.playlist
    private let simpleId = "a85d5e83dfed72d4a3e6f266d8dfd3f2"

    override func setUp() async throws {
        suite = "PlaylistQueueTests-\(UUID().uuidString)"
        PlaybackSettings.defaults = UserDefaults(suiteName: suite)!
        let api = JellyfinAPI(baseURL: URL(string: "http://127.0.0.1:9")!, accessToken: "test-token")
        api.setUserId("user-1")
        player = PlayerManager(api: api)
        entries = try PlaylistFixtures.entries()
    }

    override func tearDown() async throws {
        player.stop()
        player = nil
        UserDefaults().removePersistentDomain(forName: suite)
        PlaybackSettings.defaults = .standard
    }

    func testEachTrackKeepsItsOwnAlbumAndArt() {
        let sources = entries.map { PlayerManager.QueueSource(track: $0.track, album: $0.album) }
        let queue = player.playbackTracks(sources: sources, order: Array(sources.indices))
        XCTAssertEqual(queue.map(\.title), ["Perfectly Simple", "Roman Road", "Elsewhere", "Perfectly Simple"])
        XCTAssertEqual(queue.map(\.album),
                       ["...out flew reason", "...out flew reason", "Another Album", "...out flew reason"])
        XCTAssertEqual(queue.map(\.albumId),
                       ["19a8bfe4dbe882ef56f712c13e7c6de1", "19a8bfe4dbe882ef56f712c13e7c6de1", "album-2",
                        "19a8bfe4dbe882ef56f712c13e7c6de1"])
        XCTAssertEqual(queue.map(\.artItemId), queue.map(\.albumId))
        XCTAssertEqual(queue[2].artist, "Someone Else")
        XCTAssertTrue(queue[2].albumArtURL?.absoluteString.contains("/Items/album-2/Images/Primary") ?? false)
        XCTAssertEqual(queue[2].trackNumber, 7, "the track's own number on its album")
    }

    func testPlayStartsAtTheFirstSong() {
        player.play(playlist: playlist, tracks: entries)
        XCTAssertEqual(player.queue.count, 4)
        XCTAssertEqual(player.currentIndex, 0)
        XCTAssertEqual(player.currentTrack?.title, "Perfectly Simple")
        XCTAssertEqual(player.queueSourceId, playlist.id)
        XCTAssertEqual(player.currentSourcePosition, 0)
    }

    func testPlayStartsAtAPosition() {
        player.play(playlist: playlist, tracks: entries, startingAt: 2)
        XCTAssertEqual(player.currentIndex, 2)
        XCTAssertEqual(player.currentTrack?.title, "Elsewhere")
        XCTAssertEqual(player.currentTrack?.album, "Another Album")
        XCTAssertEqual(player.queue.map(\.id), entries.map(\.track.id), "the whole playlist is queued, in order")
    }

    /// The second row of a song listed twice plays from there, not from
    /// the first row with the same track ID.
    func testTheSameSongTwice() {
        player.play(playlist: playlist, tracks: entries, startingAt: 3)
        XCTAssertEqual(player.queue.filter { $0.id == simpleId }.count, 2)
        XCTAssertEqual(player.currentIndex, 3)
        XCTAssertEqual(player.currentSourcePosition, 3)
        XCTAssertEqual(player.currentTrack?.id, simpleId)
        XCTAssertTrue(player.upNext.isEmpty)

        XCTAssertFalse(PlaylistDetailViewModel.isCurrent(entries[0], playingPosition: 3, playingTrackId: simpleId),
                       "only the playing row is marked")
        XCTAssertTrue(PlaylistDetailViewModel.isCurrent(entries[3], playingPosition: 3, playingTrackId: simpleId))

        player.play(playlist: playlist, tracks: entries, startingAt: 0)
        XCTAssertEqual(player.upNext.map(\.index), [1, 2, 3], "Up next rows are told apart by queue index")
        XCTAssertEqual(player.upNext.last?.track.id, simpleId)
    }

    func testAPositionOutsideThePlaylistStartsAtTheTop() {
        player.play(playlist: playlist, tracks: entries, startingAt: 9)
        XCTAssertEqual(player.currentIndex, 0)
        player.play(playlist: playlist, tracks: entries, startingAt: -1)
        XCTAssertEqual(player.currentIndex, 0)
    }

    func testAnEmptyPlaylistQueuesNothing() {
        player.play(playlist: playlist, tracks: [])
        XCTAssertTrue(player.queue.isEmpty)
        XCTAssertNil(player.queueSourceId)
    }

    func testShuffleKeepsEverySongAndPlaysTheTappedOneFirst() {
        player.setShuffle(true)
        player.play(playlist: playlist, tracks: entries, startingAt: 2)
        XCTAssertEqual(player.currentIndex, 0)
        XCTAssertEqual(player.currentTrack?.title, "Elsewhere")
        XCTAssertEqual(player.currentSourcePosition, 2)
        XCTAssertEqual(player.queue.map(\.id).sorted(), entries.map(\.track.id).sorted(),
                       "both rows of the repeated song are still there")

        player.play(playlist: playlist, tracks: entries)
        XCTAssertEqual(player.queue.count, 4)
        XCTAssertEqual(player.queue.map(\.id).sorted(), entries.map(\.track.id).sorted())
    }

    func testShufflePlanKeepsEachPlaceOnce() {
        for _ in 0..<20 {
            let plan = planQueue(tracks: Array(0..<6), forcedStartIndex: 4, shuffleOn: true)
            XCTAssertEqual(plan.tracks.first, 4)
            XCTAssertEqual(plan.startIndex, 0)
            XCTAssertEqual(plan.tracks.sorted(), Array(0..<6))
        }
    }

    func testManiacModeStartsAtTheFirstSongInOrder() {
        player.setShuffle(true)
        player.setManiacMode(true)
        player.play(playlist: playlist, tracks: entries, startingAt: 2)
        XCTAssertFalse(player.shuffle)
        XCTAssertEqual(player.currentIndex, 0)
        XCTAssertEqual(player.queue.map(\.id), entries.map(\.track.id))
        XCTAssertEqual(player.currentSourcePosition, 0)
    }

    /// Turning Maniac Mode on mid-playlist puts the rest back in playlist
    /// order, with both rows of a repeated song kept.
    func testManiacModePutsAShuffledPlaylistBackInOrder() {
        player.setShuffle(true)
        player.play(playlist: playlist, tracks: entries, startingAt: 1)
        player.setManiacMode(true)
        XCTAssertEqual(player.currentTrack?.title, "Roman Road")
        XCTAssertEqual(player.queue.map(\.title), ["Roman Road", "Perfectly Simple", "Elsewhere", "Perfectly Simple"])
        XCTAssertEqual(player.currentSourcePosition, 1)
    }

    func testOrderRestoreWithTheSameSongTwice() {
        // Places in the playlist of a shuffled queue; the song at places 0
        // and 3 is the same one. Entry 1 is playing.
        let order = orderAfterCurrent(positions: [2, 3, 1, 0], current: 1)
        XCTAssertEqual(order, [0, 1, 3, 2])
        XCTAssertEqual(order.map { [2, 3, 1, 0][$0] }, [2, 3, 0, 1])
    }

    func testTheEndOfThePlaylistIsLikeTheEndOfAnAlbum() {
        player.play(playlist: playlist, tracks: entries, startingAt: 3)
        player.isNowPlayingPresented = true
        player.finishQueue()
        XCTAssertFalse(player.isNowPlayingPresented)
        XCTAssertFalse(player.isPlaying)
        XCTAssertEqual(player.currentIndex, 0)
        XCTAssertEqual(player.currentTrack?.title, "Perfectly Simple")
        XCTAssertEqual(player.position, 0)
        XCTAssertEqual(player.queueSourceId, playlist.id)
    }

    func testStoppingClearsTheSource() {
        player.play(playlist: playlist, tracks: entries)
        player.stop()
        XCTAssertNil(player.queueSourceId)
        XCTAssertNil(player.currentSourcePosition)
    }

    // MARK: Which page shows "Now playing"

    func testAnAlbumIsNotMarkedPlayingBecauseOfAPlaylistQueue() {
        player.play(playlist: playlist, tracks: entries)
        let albumId = "19a8bfe4dbe882ef56f712c13e7c6de1"
        XCTAssertEqual(player.currentTrack?.albumId, albumId)
        XCTAssertFalse(AlbumDetailViewModel.isAlbumPlaying(
            albumIds: [albumId], playingAlbumId: player.currentTrack?.albumId,
            queueSourceId: player.queueSourceId, queue: player.queue))
    }

    func testAnAlbumQueueStillMarksItsAlbum() {
        let sources = entries.prefix(2).map { PlayerManager.QueueSource(track: $0.track, album: $0.album) }
        let queue = player.playbackTracks(sources: Array(sources), order: [0, 1])
        let albumId = "19a8bfe4dbe882ef56f712c13e7c6de1"
        XCTAssertTrue(AlbumDetailViewModel.isAlbumPlaying(
            albumIds: [albumId], playingAlbumId: albumId, queueSourceId: albumId, queue: queue))
        // Downloads and older queues without a source: as before.
        XCTAssertTrue(AlbumDetailViewModel.isAlbumPlaying(
            albumIds: [albumId], playingAlbumId: albumId, queueSourceId: nil, queue: queue))
        // A multi-disc album queued under its merged entry's ID.
        XCTAssertTrue(AlbumDetailViewModel.isAlbumPlaying(
            albumIds: ["disc-2", "disc-1"], playingAlbumId: "disc-2", queueSourceId: "disc-1", queue: []))
        XCTAssertFalse(AlbumDetailViewModel.isAlbumPlaying(
            albumIds: ["other"], playingAlbumId: albumId, queueSourceId: albumId, queue: queue))
        XCTAssertFalse(AlbumDetailViewModel.isAlbumPlaying(
            albumIds: [albumId], playingAlbumId: nil, queueSourceId: nil, queue: []))
    }
}
