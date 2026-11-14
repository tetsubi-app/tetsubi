// Playlists, making and editing: the write calls, duplicates, names,
// wording, the one-at-a-time save queue and the page's edit mode.

import XCTest
@testable import JellyfinMusicCore

private func response(_ status: Int) -> HTTPURLResponse {
    HTTPURLResponse(url: URL(string: "http://jellyfin-box:8096/x")!, statusCode: status,
                    httpVersion: nil, headerFields: nil)!
}

// MARK: - Requests and answers

final class PlaylistWriteAPITests: XCTestCase {

    private func makeAPI() -> JellyfinAPI {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")
        api.setUserId("user-1")
        return api
    }

    private func query(_ request: URLRequest) -> [String: String] {
        let items = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
    }

    private func body(_ request: URLRequest) throws -> NSDictionary {
        let data = try XCTUnwrap(request.httpBody)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? NSDictionary)
    }

    private func assertSignedIn(_ request: URLRequest, file: StaticString = #filePath, line: UInt = #line) {
        let auth = request.value(forHTTPHeaderField: "Authorization") ?? ""
        XCTAssertTrue(auth.contains("MediaBrowser") && auth.contains("test-token"), auth, file: file, line: line)
    }

    // Create

    func testCreatePlaylistRequest() throws {
        let request = try XCTUnwrap(makeAPI().createPlaylistRequest(name: "Road trip", trackIds: ["a", "b", "a"]))
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/Playlists")
        XCTAssertEqual(query(request), [:])
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(try body(request), [
            "Name": "Road trip", "Ids": ["a", "b", "a"], "UserId": "user-1",
            "MediaType": "Audio", "IsPublic": false
        ] as NSDictionary)
        assertSignedIn(request)
    }

    func testCreateEmptyPlaylistSendsNoIds() throws {
        let request = try XCTUnwrap(makeAPI().createPlaylistRequest(name: "Empty", trackIds: []))
        XCTAssertEqual(try body(request)["Ids"] as? [String], [])
    }

    func testCreatePlaylistAnswer() throws {
        let data = Data(#"{"Id":"03fa46cb02bc9de9396da4258c2a84ff"}"#.utf8)
        let id = try JellyfinAPI.createPlaylistResult(data: data, response: response(200), error: nil).get()
        XCTAssertEqual(id, "03fa46cb02bc9de9396da4258c2a84ff")
    }

    func testCreatePlaylistAnswerFailures() {
        XCTAssertThrowsError(try JellyfinAPI.createPlaylistResult(
            data: Data("Forbidden".utf8), response: response(403), error: nil).get())
        XCTAssertThrowsError(try JellyfinAPI.createPlaylistResult(
            data: Data("{}".utf8), response: response(200), error: nil).get())
        XCTAssertThrowsError(try JellyfinAPI.createPlaylistResult(
            data: nil, response: nil, error: URLError(.notConnectedToInternet)).get())
    }

    // Add

    func testAddToPlaylistRequest() throws {
        let request = try XCTUnwrap(makeAPI().addToPlaylistRequest("pl-9", trackIds: ["a", "b", "c"]))
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/Playlists/pl-9/Items")
        XCTAssertEqual(query(request), ["ids": "a,b,c", "userId": "user-1"])
        XCTAssertNil(request.httpBody)
        assertSignedIn(request)
    }

    // Set items

    func testSetPlaylistItemsRequestKeepsOrderAndDuplicates() throws {
        let request = try XCTUnwrap(makeAPI().setPlaylistItemsRequest("pl-9", trackIds: ["c", "a", "c"]))
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/Playlists/pl-9")
        XCTAssertEqual(query(request), [:])
        XCTAssertEqual(try body(request), ["Ids": ["c", "a", "c"]] as NSDictionary)
        assertSignedIn(request)
    }

    func testSetPlaylistItemsRequestCanEmptyThePlaylist() throws {
        let request = try XCTUnwrap(makeAPI().setPlaylistItemsRequest("pl-9", trackIds: []))
        XCTAssertEqual(try body(request), ["Ids": [String]()] as NSDictionary)
    }

    // Rename

    func testRenamePlaylistRequest() throws {
        let request = try XCTUnwrap(makeAPI().renamePlaylistRequest("pl-9", name: "New name"))
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/Playlists/pl-9")
        XCTAssertEqual(query(request), [:])
        XCTAssertEqual(try body(request), ["Name": "New name"] as NSDictionary, "the name only: the songs stay")
        assertSignedIn(request)
    }

    // Can edit

    func testCanEditPlaylistRequest() throws {
        let request = try XCTUnwrap(makeAPI().canEditPlaylistRequest("pl-9"))
        XCTAssertEqual(request.httpMethod ?? "GET", "GET")
        XCTAssertEqual(request.url?.path, "/Playlists/pl-9/Users/user-1")
        XCTAssertEqual(query(request), [:])
        assertSignedIn(request)
    }

    func testCanEditPlaylistAnswer() {
        let yes = Data(#"{"UserId":"d0ad7ae482834035af94aec8ef91e6a3","CanEdit":true}"#.utf8)
        let no = Data(#"{"UserId":"d0ad7ae482834035af94aec8ef91e6a3","CanEdit":false}"#.utf8)
        XCTAssertTrue(JellyfinAPI.canEditPlaylistResult(data: yes, response: response(200), error: nil))
        XCTAssertFalse(JellyfinAPI.canEditPlaylistResult(data: no, response: response(200), error: nil))
    }

    func testCanEditPlaylistIsNoWhenNotFoundOrFailed() {
        XCTAssertFalse(JellyfinAPI.canEditPlaylistResult(
            data: Data("Playlist not found".utf8), response: response(404), error: nil))
        XCTAssertFalse(JellyfinAPI.canEditPlaylistResult(
            data: nil, response: nil, error: URLError(.timedOut)))
        XCTAssertFalse(JellyfinAPI.canEditPlaylistResult(data: Data("{}".utf8), response: response(200), error: nil))
        XCTAssertFalse(JellyfinAPI.canEditPlaylistResult(data: Data(), response: response(200), error: nil))
    }

    // Delete

    func testDeletePlaylistRequest() throws {
        let request = try XCTUnwrap(makeAPI().deletePlaylistRequest("pl-9"))
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.url?.path, "/Items/pl-9")
        XCTAssertEqual(query(request), [:])
        XCTAssertNil(request.httpBody)
        assertSignedIn(request)
    }

    // Add, set items, rename and delete all answer 204 with no body.

    func testEmptyAnswerSucceedsOn204() {
        XCTAssertNoThrow(try JellyfinAPI.emptyResult(data: Data(), response: response(204), error: nil).get())
        XCTAssertNoThrow(try JellyfinAPI.emptyResult(data: nil, response: response(204), error: nil).get())
    }

    func testEmptyAnswerFailsOnErrorStatusOrNoConnection() {
        XCTAssertThrowsError(try JellyfinAPI.emptyResult(
            data: Data("Playlist not found".utf8), response: response(404), error: nil).get())
        XCTAssertThrowsError(try JellyfinAPI.emptyResult(data: Data(), response: response(403), error: nil).get())
        XCTAssertThrowsError(try JellyfinAPI.emptyResult(data: Data(), response: response(500), error: nil).get())
        XCTAssertThrowsError(try JellyfinAPI.emptyResult(
            data: nil, response: nil, error: URLError(.notConnectedToInternet)).get())
    }

    func testWritesNeedASignedInUser() {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")
        XCTAssertNil(api.createPlaylistRequest(name: "x", trackIds: []))
        XCTAssertNil(api.addToPlaylistRequest("pl", trackIds: ["a"]))
        XCTAssertNil(api.canEditPlaylistRequest("pl"))
    }
}

// MARK: - Duplicates, names, wording

final class PlaylistAddTests: XCTestCase {

    func testOneSongAlreadyThere() {
        let plan = PlaylistAdd.plan(adding: ["a"], existing: ["x", "a", "y"])
        XCTAssertTrue(plan.needsQuestion)
        XCTAssertEqual(plan.trackIds(for: .add), ["a"])
        XCTAssertEqual(plan.trackIds(for: .skip), [], "Skip adds nothing")
    }

    func testWholeAlbumWithSomeAlreadyThere() {
        let plan = PlaylistAdd.plan(adding: ["a", "b", "c", "d"], existing: ["c", "x", "a"])
        XCTAssertTrue(plan.needsQuestion)
        XCTAssertEqual(plan.trackIds(for: .add), ["a", "b", "c", "d"], "Add adds all")
        XCTAssertEqual(plan.trackIds(for: .skip), ["b", "d"], "Skip adds only those not there, in order")
    }

    func testWholeAlbumAllAlreadyThere() {
        let plan = PlaylistAdd.plan(adding: ["a", "b"], existing: ["b", "a"])
        XCTAssertTrue(plan.needsQuestion)
        XCTAssertEqual(plan.trackIds(for: .skip), [], "nothing left to send")
    }

    func testNoneThereMeansNoQuestion() {
        let plan = PlaylistAdd.plan(adding: ["a", "b"], existing: ["x", "y"])
        XCTAssertFalse(plan.needsQuestion)
        XCTAssertEqual(plan.all, ["a", "b"])
        XCTAssertFalse(PlaylistAdd.plan(adding: ["a"], existing: []).needsQuestion)
    }

    func testNames() {
        XCTAssertEqual(PlaylistName.cleaned("  Road trip \n"), "Road trip")
        XCTAssertEqual(PlaylistName.cleaned("Two  words"), "Two  words")
        XCTAssertNil(PlaylistName.cleaned(""))
        XCTAssertNil(PlaylistName.cleaned("   \n\t"))
    }

    func testWording() {
        XCTAssertEqual(PlaylistWording.duplicateQuestion(playlist: "Mix"), "Already in Mix. Add again?")
        XCTAssertEqual(PlaylistWording.added(count: 1, to: "Mix"), "Added to Mix.")
        XCTAssertEqual(PlaylistWording.added(count: 12, to: "Mix"), "Added 12 songs to Mix.")
        XCTAssertEqual(PlaylistWording.addFailed(to: "Mix", error: "Timed out."), "Couldn't add to Mix. Timed out.")
        XCTAssertEqual(PlaylistWording.saveFailed(error: "Timed out."), "Couldn't save your changes. Timed out.")
        XCTAssertEqual(PlaylistWording.deleteTitle(playlist: "Mix"), "Delete “Mix”?")
        XCTAssertEqual(PlaylistWording.deleteMessage,
                       "This removes the playlist from your server. The songs stay in your library.")
    }
}

// MARK: - Save queue

@MainActor
final class PlaylistSaveQueueTests: XCTestCase {

    /// Records what was sent and lets the test answer each call.
    private final class Recorder {
        var sent: [PlaylistSaveQueue.Change] = []
        var answers: [@MainActor (JellyfinError?) -> Void] = []
        var saved: [PlaylistSaveQueue.Change] = []
        var failures = 0

        @MainActor func makeQueue() -> PlaylistSaveQueue {
            let queue = PlaylistSaveQueue { [self] change, done in
                sent.append(change)
                answers.append(done)
            }
            queue.onSaved = { [self] in saved.append($0) }
            queue.onFailure = { [self] _ in failures += 1 }
            return queue
        }
    }

    func testSendsOneAtATimeAndNewerOrderReplacesTheOneWaiting() {
        let recorder = Recorder()
        let queue = recorder.makeQueue()

        queue.submit(.order(["a", "b", "c"]))
        queue.submit(.order(["b", "a", "c"]))
        queue.submit(.order(["b", "c", "a"]))
        XCTAssertEqual(recorder.sent, [.order(["a", "b", "c"])], "only the first is on its way")
        XCTAssertFalse(queue.isIdle)

        recorder.answers[0](nil)
        XCTAssertEqual(recorder.sent, [.order(["a", "b", "c"]), .order(["b", "c", "a"])],
                       "the middle order was replaced, never sent")

        recorder.answers[1](nil)
        XCTAssertEqual(recorder.sent.count, 2)
        XCTAssertEqual(recorder.saved.count, 2)
        XCTAssertTrue(queue.isIdle)
    }

    func testNameAndOrderBothGo() {
        let recorder = Recorder()
        let queue = recorder.makeQueue()
        queue.submit(.order(["a"]))
        queue.submit(.order(["b"]))
        queue.submit(.name("New"))
        recorder.answers[0](nil)
        recorder.answers[1](nil)
        recorder.answers[2](nil)
        XCTAssertEqual(recorder.sent, [.order(["a"]), .name("New"), .order(["b"])])
        XCTAssertTrue(queue.isIdle)
    }

    func testFailureDropsWhatIsWaiting() {
        let recorder = Recorder()
        let queue = recorder.makeQueue()
        queue.submit(.order(["a"]))
        queue.submit(.order(["b"]))
        recorder.answers[0](.http(500, ""))
        XCTAssertEqual(recorder.failures, 1)
        XCTAssertEqual(recorder.sent, [.order(["a"])], "the waiting order is not sent after a failure")
        XCTAssertTrue(queue.isIdle)

        queue.submit(.order(["c"]))
        XCTAssertEqual(recorder.sent.last, .order(["c"]), "a later edit still goes")
    }

    func testCancelAllIgnoresTheAnswerOnItsWay() {
        let recorder = Recorder()
        let queue = recorder.makeQueue()
        queue.submit(.order(["a"]))
        queue.submit(.order(["b"]))
        queue.cancelAll()
        XCTAssertTrue(queue.isIdle)
        recorder.answers[0](.http(500, ""))
        XCTAssertEqual(recorder.failures, 0)
        XCTAssertEqual(recorder.sent.count, 1)
    }
}

// MARK: - The page's edit mode

@MainActor
final class PlaylistEditModeTests: XCTestCase {

    /// Stands in for the server: answers at once, on the calling thread.
    private final class FakeServer {
        var serverIds = ["a", "b", "a", "c"]
        var canEdit = true
        var setItemsCalls: [[String]] = []
        var renameCalls: [String] = []
        var deleteCalls = 0
        var tracksCalls = 0
        var failWrites = false

        func entries() -> [PlaylistEntry] {
            serverIds.enumerated().map { index, id in
                PlaylistEntry(position: index, entryId: id,
                              track: JellyfinTrack(id: id, name: "Song \(id)", serverId: nil, indexNumber: 1,
                                                   runTimeTicks: 600_000_000, album: "Album", albumId: "album",
                                                   albumArtists: nil, imageTags: nil, mediaSource: nil),
                              albumName: "Album", albumId: "album", albumArtist: "Artist")
            }
        }

        var server: PlaylistServer {
            PlaylistServer(
                tracks: { [self] _, done in tracksCalls += 1; done(.success(entries())) },
                canEdit: { [self] _, done in done(canEdit) },
                setItems: { [self] _, ids, done in
                    setItemsCalls.append(ids)
                    if failWrites { done(.failure(.http(500, ""))) } else {
                        serverIds = ids
                        done(.success(()))
                    }
                },
                rename: { [self] _, name, done in
                    renameCalls.append(name)
                    done(failWrites ? .failure(.http(500, "")) : .success(()))
                },
                delete: { [self] _, done in
                    deleteCalls += 1
                    done(failWrites ? .failure(.http(500, "")) : .success(()))
                })
        }
    }

    private func makeModel(_ fake: FakeServer, events: PlaylistEvents? = nil) -> PlaylistDetailViewModel {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "t")
        return PlaylistDetailViewModel(api: api, playlist: JellyfinPlaylist(id: "pl", name: "Mix", songCount: 4),
                                       isOnline: { true }, server: fake.server, events: events ?? PlaylistEvents())
    }

    /// Lets the answers hop to the main queue.
    private func settle() async {
        for _ in 0 ..< 5 {
            await withCheckedContinuation { cont in DispatchQueue.main.async { cont.resume() } }
        }
    }

    private func loaded(_ fake: FakeServer, events: PlaylistEvents? = nil) async -> PlaylistDetailViewModel {
        let model = makeModel(fake, events: events)
        model.load()
        await settle()
        model.beginEditing()
        return model
    }

    func testEditShowsOnlyWhenThePlaylistCanBeChanged() async {
        let fake = FakeServer()
        fake.canEdit = false
        let model = makeModel(fake)
        model.load()
        await settle()
        XCTAssertFalse(model.showsEdit)
        model.beginEditing()
        XCTAssertFalse(model.isEditing)

        let mine = await loaded(FakeServer())
        XCTAssertTrue(mine.showsEdit)
        XCTAssertTrue(mine.isEditing)
    }

    func testRemovingOneOfTwoCopiesGoesByPosition() async {
        let fake = FakeServer()
        let model = await loaded(fake)
        // "a" is at 0 and 2; remove the second copy.
        model.remove(atOffsets: IndexSet(integer: 2))
        XCTAssertEqual(model.entries.map(\.track.id), ["a", "b", "c"], "shown at once")
        XCTAssertEqual(model.entries.map(\.position), [0, 1, 2], "rows are counted again")
        await settle()
        XCTAssertEqual(fake.setItemsCalls, [["a", "b", "c"]], "the whole new order is sent")
    }

    func testMovingSendsTheWholeOrder() async {
        let fake = FakeServer()
        let model = await loaded(fake)
        model.move(fromOffsets: IndexSet(integer: 3), toOffset: 0)
        XCTAssertEqual(model.entries.map(\.track.id), ["c", "a", "b", "a"])
        XCTAssertEqual(model.entries.map(\.position), [0, 1, 2, 3])
        XCTAssertEqual(model.summary, "4 songs · 4 min")
        await settle()
        XCTAssertEqual(fake.setItemsCalls, [["c", "a", "b", "a"]])
        XCTAssertNil(model.editErrorMessage)
    }

    func testFailedSaveReloadsFromTheServerAndSaysSo() async {
        let fake = FakeServer()
        let model = await loaded(fake)
        let fetchesBefore = fake.tracksCalls
        fake.failWrites = true
        model.remove(atOffsets: IndexSet(integer: 0))
        XCTAssertEqual(model.entries.count, 3)
        await settle()
        XCTAssertEqual(fake.tracksCalls, fetchesBefore + 1, "the page asks the server again")
        XCTAssertEqual(model.entries.map(\.track.id), ["a", "b", "a", "c"], "back to what the server has")
        XCTAssertEqual(model.editErrorMessage?.hasPrefix("Couldn't save your changes. "), true)
    }

    func testRename() async {
        let fake = FakeServer()
        let model = await loaded(fake)
        model.rename(to: "   ")
        model.rename(to: "Mix")
        XCTAssertEqual(fake.renameCalls, [], "an empty or unchanged name isn't sent")
        model.rename(to: "  Better mix ")
        XCTAssertEqual(model.playlist.name, "Better mix")
        await settle()
        XCTAssertEqual(fake.renameCalls, ["Better mix"])
    }

    func testFailedRenameGoesBackToTheOldName() async {
        let fake = FakeServer()
        let model = await loaded(fake)
        fake.failWrites = true
        model.rename(to: "Better mix")
        await settle()
        XCTAssertEqual(model.playlist.name, "Mix")
        XCTAssertEqual(model.editErrorMessage?.hasPrefix("Couldn't save your changes. "), true)
    }

    func testDeleteLeavesThePageAndTellsTheLists() async {
        let fake = FakeServer()
        let events = PlaylistEvents()
        let model = await loaded(fake, events: events)
        let before = events.version
        model.delete()
        await settle()
        XCTAssertEqual(fake.deleteCalls, 1)
        XCTAssertTrue(model.isDeleted)
        XCTAssertFalse(model.isEditing)
        XCTAssertGreaterThan(events.version, before)
    }

    func testFailedDeleteStaysAndSaysSo() async {
        let fake = FakeServer()
        let model = await loaded(fake)
        fake.failWrites = true
        model.delete()
        await settle()
        XCTAssertFalse(model.isDeleted)
        XCTAssertEqual(model.editErrorMessage?.hasPrefix("Couldn't delete the playlist. "), true)
    }

    func testAPageBeingEditedIsNotReloadedUnderTheUser() async {
        let fake = FakeServer()
        let model = await loaded(fake)
        let fetches = fake.tracksCalls
        model.load()
        XCTAssertEqual(fake.tracksCalls, fetches)
        model.endEditing()
        model.load()
        XCTAssertEqual(fake.tracksCalls, fetches + 1)
    }
}
