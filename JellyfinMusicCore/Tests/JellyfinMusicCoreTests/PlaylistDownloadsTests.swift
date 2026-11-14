// Playlists, downloads and offline: the manifest, which file a song
// plays from, reuse of downloaded albums, keeping in step with the
// server, and what shows offline.

import XCTest
@testable import JellyfinMusicCore

@MainActor
final class PlaylistDownloadsTests: XCTestCase {

    private var root: URL!

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("playlist-downloads-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        DownloadPaths.rootDirOverride = root
    }

    override func tearDown() async throws {
        DownloadPaths.rootDirOverride = nil
        try? FileManager.default.removeItem(at: root)
    }

    // MARK: - Stand-ins

    private final class FakeTask: PlaylistDownloadTask {
        var cancelled = false
        func cancel() { cancelled = true }
    }

    /// Stands in for the server and the download session.
    private final class Fake {
        var online = true
        var playlists: [JellyfinPlaylist] = []
        var rows: [PlaylistEntry] = []
        var failRows = false
        var noLinkFor: Set<String> = []
        var started: [(info: DownloadTaskInfo, task: FakeTask)] = []
        var rowsCalls = 0

        var backend: PlaylistDownloads.Backend {
            PlaylistDownloads.Backend(
                isOnline: { [self] in online },
                playlists: { [self] done in done(.success(playlists)) },
                entries: { [self] _, done in
                    rowsCalls += 1
                    if failRows { done(.failure(.http(500, ""))) } else { done(.success(rows)) }
                },
                format: { _ in .flac },
                url: { [self] track, _ in
                    noLinkFor.contains(track.id) ? nil : URL(string: "http://jellyfin-box:8096/Audio/\(track.id)")
                },
                start: { [self] info, _ in
                    let task = FakeTask()
                    started.append((info, task))
                    return task
                },
                image: { item, _ in Data("art-\(item)".utf8) })
        }

        var startedIDs: [String] { started.map(\.info.trackID) }
    }

    private let playlist = JellyfinPlaylist(id: "pl", name: "Mix", songCount: 3)

    private func entry(_ id: String, at position: Int, album: String? = "album-1", number: Int? = 1,
                       title: String? = nil) -> PlaylistEntry {
        PlaylistEntry(position: position, entryId: "e\(position)",
                      track: JellyfinTrack(id: id, name: title ?? "Song \(id)", serverId: nil, indexNumber: number,
                                           runTimeTicks: 600_000_000, album: "Album", albumId: album,
                                           albumArtists: nil, imageTags: nil, mediaSource: nil),
                      albumName: "Album", albumId: album, albumArtist: "Artist")
    }

    private func rows(_ ids: [String]) -> [PlaylistEntry] {
        ids.enumerated().map { entry($1, at: $0, number: $0 + 1) }
    }

    /// The session saved the song.
    private func finish(_ id: String, _ fake: Fake, _ engine: PlaylistDownloads) throws {
        let info = try XCTUnwrap(fake.started.last { $0.info.trackID == id }?.info)
        try FileManager.default.createDirectory(at: info.destination.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try Data("audio".utf8).write(to: info.destination)
        engine.completed(info)
    }

    /// A song's file in a downloaded album.
    @discardableResult
    private func albumFile(_ album: String, number: Int, title: String) throws -> URL {
        let url = DownloadPaths.trackPath(albumID: album, trackNumber: number, trackName: title, format: .flac)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("album audio".utf8).write(to: url)
        return url
    }

    /// A file's path, however the temporary folder is spelled.
    private func path(_ url: URL?) -> String? { url?.resolvingSymlinksInPath().path }

    private func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }

    private func own(_ id: String) -> URL {
        PlaylistDownloadPaths.trackURL(playlistID: "pl", trackID: id, format: .flac)
    }

    /// Lets the answers hop to the main queue.
    private func settle() async {
        for _ in 0 ..< 5 {
            await withCheckedContinuation { cont in DispatchQueue.main.async { cont.resume() } }
        }
    }

    // MARK: - Manifest

    func testManifestRoundTrip() throws {
        var track = entry("a", at: 0, number: 3).track
        track.discNumber = 2
        track.normalizationGain = -4.5
        let first = PlaylistEntry(position: 0, entryId: "e0", track: track,
                                  albumName: "Album", albumId: "album-1", albumArtist: "Artist")
        let untagged = entry("b", at: 1, album: nil, number: nil)
        let manifest = PlaylistManifest(
            id: "pl", name: "Mix", hasArt: true, dateCreated: Date(timeIntervalSince1970: 1_700_000_000),
            entries: [.init(first, format: .flac), .init(untagged, format: .mp3), .init(first, format: .flac)],
            paused: true, failure: "Track download failed: x")
        try PlaylistDownloadPaths.save(manifest)

        let url = root.appendingPathComponent("playlists/pl/playlist.json")
        XCTAssertTrue(exists(url))
        let loaded = try XCTUnwrap(PlaylistDownloadPaths.loadManifest("pl"))
        XCTAssertEqual(loaded, manifest)
        XCTAssertEqual(loaded.entries.map(\.file), ["a.flac", "b.mp3", "a.flac"])
        XCTAssertEqual(loaded.entries[0].fileNumber, 203)
        XCTAssertEqual(PlaylistDownloadPaths.downloadedIDs(), ["pl"])

        // The rows come back as the page and the queue want them.
        let back = loaded.playlistEntries
        XCTAssertEqual(back.map(\.position), [0, 1, 2])
        XCTAssertEqual(back.map(\.track.id), ["a", "b", "a"])
        XCTAssertEqual(back[0].track.displayName, "Song a")
        XCTAssertEqual(back[0].albumArtist, "Artist")
        XCTAssertEqual(back[0].albumId, "album-1")
        XCTAssertEqual(back[0].track.indexNumber, 3)
        XCTAssertEqual(back[0].track.discNumber, 2)
        XCTAssertEqual(back[0].track.normalizationGain, -4.5)
        XCTAssertEqual(back[0].track.runTimeTicks, 600_000_000)
        XCTAssertNil(back[1].track.indexNumber)
        XCTAssertNil(back[1].albumId)
        XCTAssertEqual(back[0].artItemId, first.artItemId)
        XCTAssertEqual(back[1].artItemId, untagged.artItemId)

        let shown = loaded.playlist
        XCTAssertEqual(shown.songCount, 3)
        XCTAssertEqual(shown.runTimeTicks, 1_800_000_000)
        XCTAssertEqual(shown.name, "Mix")
        XCTAssertTrue(shown.hasArt)
    }

    func testThePlaylistsFolderIsNotAnAlbum() throws {
        try albumFile("album-1", number: 1, title: "Song a")
        try PlaylistDownloadPaths.save(PlaylistManifest(id: "pl", name: "Mix", entries: []))
        XCTAssertEqual(DownloadPaths.downloadedAlbumIDs(), ["album-1"])
    }

    func testTaskInfoGoesToThePlaylistsFolder() throws {
        let info = DownloadTaskInfo(albumID: "album-1", trackID: "a", trackNumber: 1, trackName: "Song a",
                                    format: .flac, expectedTrackCount: 2, playlistID: "pl")
        XCTAssertEqual(info.destination.path, root.appendingPathComponent("playlists/pl/a.flac").path)
        XCTAssertEqual(DownloadTaskInfo(taskDescription: info.encoded), info)

        // An album's task, as written before playlists.
        let old = #"{"albumID":"album-1","trackID":"a","trackNumber":1,"trackName":"Song a","format":"flac","expectedTrackCount":2}"#
        let album = try XCTUnwrap(DownloadTaskInfo(taskDescription: old))
        XCTAssertNil(album.playlistID)
        XCTAssertEqual(album.destination.path, root.appendingPathComponent("album-1/01 - Song a.flac").path)
    }

    // MARK: - Which file plays

    func testFileResolutionOrder() throws {
        let row = entry("a", at: 0, number: 1)
        XCTAssertNil(PlaylistFiles(playlistID: "pl").file(for: row), "nothing saved: streamed")

        let inAlbum = try albumFile("album-1", number: 1, title: "Song a")
        XCTAssertEqual(path(PlaylistFiles(playlistID: "pl").file(for: row)), path(inAlbum))

        try FileManager.default.createDirectory(at: PlaylistDownloadPaths.dir("pl"), withIntermediateDirectories: true)
        try Data("audio".utf8).write(to: own("a"))
        XCTAssertEqual(path(PlaylistFiles(playlistID: "pl").file(for: row)), path(own("a")),
                       "the playlist's own file comes first")

        // The manifest's row resolves the same way.
        let saved = PlaylistManifest.Entry(row, format: .flac)
        XCTAssertEqual(path(PlaylistFiles(playlistID: "pl").file(for: saved)), path(own("a")))
        // Another playlist has no file of its own, so it plays the album's.
        XCTAssertEqual(path(PlaylistFiles(playlistID: "other").file(for: saved)), path(inAlbum))
        // A song from another album isn't found in this one.
        XCTAssertNil(PlaylistFiles(playlistID: "other").file(for: entry("z", at: 0, album: "album-2", title: "Song a")))
    }

    func testAnAlbumWithTheSameTitleTwiceIsToldApartByNumber() throws {
        try albumFile("album-1", number: 1, title: "Intro")
        let second = try albumFile("album-1", number: 7, title: "Intro")
        let files = PlaylistFiles(playlistID: "pl")
        XCTAssertEqual(path(files.file(for: entry("x", at: 0, number: 7, title: "Intro"))), path(second))
        XCTAssertNil(files.file(for: entry("y", at: 1, number: nil, title: "Intro")))
    }

    func testSongsWithNoTrackNumbers() async throws {
        // In a downloaded album an untagged song has a made-up number;
        // it's found by its title.
        let inAlbum = try albumFile("album-1", number: 2, title: "Loose")
        let loose = entry("a", at: 0, number: nil, title: "Loose")
        XCTAssertEqual(path(PlaylistFiles(playlistID: "pl").file(for: loose)), path(inAlbum))

        // Downloaded for a playlist, untagged songs are named by ID, so
        // they can't collide.
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        let list = [entry("b", at: 0, album: nil, number: nil, title: "Untitled"),
                    entry("c", at: 1, album: nil, number: nil, title: "Untitled")]
        engine.download(playlist: playlist, entries: list)
        XCTAssertEqual(fake.startedIDs, ["b", "c"])
        XCTAssertEqual(fake.started.map(\.info.trackNumber), [0, 0])
        try finish("b", fake, engine)
        try finish("c", fake, engine)
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertTrue(exists(own("b")) && exists(own("c")))
        XCTAssertEqual(engine.manifests["pl"]?.entries.map(\.trackNumber), [0, 0])
        XCTAssertEqual(engine.manifests["pl"]?.playlistEntries.map(\.track.indexNumber), [nil, nil])
        await engine.waitForArt()
    }

    // MARK: - Downloading

    func testDownloadSavesEachSongAndFinishes() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b", "c"]))

        XCTAssertEqual(fake.startedIDs, ["a", "b", "c"])
        XCTAssertTrue(fake.started.allSatisfy { $0.info.playlistID == "pl" })
        XCTAssertEqual(engine.state(for: "pl")?.status, .downloading)
        XCTAssertEqual(engine.state(for: "pl")?.expectedTrackCount, 3)
        XCTAssertFalse(engine.isDownloaded("pl"))
        XCTAssertTrue(engine.hasDownload("pl"))
        XCTAssertTrue(engine.downloadedPlaylists.isEmpty, "not listed offline until it's complete")

        try finish("a", fake, engine)
        XCTAssertEqual(engine.state(for: "pl")?.completedTracks, 1)
        XCTAssertEqual(engine.state(for: "pl")?.progress ?? 0, 1.0 / 3, accuracy: 0.001)
        try finish("b", fake, engine)
        try finish("c", fake, engine)
        XCTAssertNil(engine.state(for: "pl"))
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertEqual(engine.downloadedPlaylists.map(\.id), ["pl"])
        XCTAssertEqual(engine.downloadedPlaylists.first?.songCount, 3)
        XCTAssertGreaterThan(engine.size("pl"), 0)

        // A second tap changes nothing.
        engine.download(playlist: playlist, entries: rows(["a", "b", "c"]))
        XCTAssertEqual(fake.started.count, 3)

        // A new launch reads it back from the folder.
        await engine.waitForArt()
        let again = PlaylistDownloads(backend: fake.backend)
        XCTAssertTrue(again.isDownloaded("pl"))
        XCTAssertEqual(again.manifests["pl"]?.entries.map(\.trackId), ["a", "b", "c"])
    }

    func testTheSameSongTwice() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b", "a"]))

        XCTAssertEqual(fake.startedIDs, ["a", "b"], "stored once")
        XCTAssertEqual(engine.state(for: "pl")?.expectedTrackCount, 2)
        XCTAssertEqual(engine.manifests["pl"]?.entries.map(\.trackId), ["a", "b", "a"], "both rows are kept")
        try finish("a", fake, engine)
        try finish("b", fake, engine)
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertEqual(PlaylistDownloadPaths.trackFiles("pl").keys.sorted(), ["a", "b"])
        XCTAssertEqual(engine.manifests["pl"]?.playlistEntries.map(\.position), [0, 1, 2])
        XCTAssertEqual(engine.downloadedPlaylists.first?.songCount, 3)

        // One of the two is taken out: the file stays for the other.
        engine.sync(playlistID: "pl", entries: rows(["a", "b"]))
        XCTAssertTrue(exists(own("a")))
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertEqual(fake.started.count, 2)
        await engine.waitForArt()
    }

    func testAlbumFilesAreReused() async throws {
        try albumFile("album-1", number: 1, title: "Song a")
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        let list = [entry("a", at: 0, album: "album-1", number: 1), entry("b", at: 1, album: "album-2", number: 1)]
        engine.download(playlist: playlist, entries: list)

        XCTAssertEqual(fake.startedIDs, ["b"], "the album's song isn't downloaded again")
        XCTAssertEqual(engine.state(for: "pl")?.completedTracks, 1)
        try finish("b", fake, engine)
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertFalse(exists(own("a")))
        await engine.waitForArt()
    }

    func testAnAlbumFinishingCompletesAWaitingPlaylist() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: [entry("a", at: 0, number: 1)])
        XCTAssertEqual(fake.startedIDs, ["a"])

        try albumFile("album-1", number: 1, title: "Song a")
        engine.albumDownloaded("album-1")
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertTrue(fake.started[0].task.cancelled, "the song's own download is no longer needed")
        await engine.waitForArt()
    }

    // MARK: - Keeping in step

    func testKeepingInStepAddsAndRemovesTheRightFiles() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b", "c"]))
        try finish("a", fake, engine)
        try finish("b", fake, engine)
        // c is still on its way.

        // The server now has: c gone, d new, and b before a.
        engine.sync(playlistID: "pl", entries: rows(["b", "a", "d"]))
        XCTAssertEqual(engine.manifests["pl"]?.entries.map(\.trackId), ["b", "a", "d"], "the server's order")
        XCTAssertTrue(fake.started[2].task.cancelled, "the removed song's download stops")
        XCTAssertEqual(fake.startedIDs, ["a", "b", "c", "d"], "only the new song starts")
        XCTAssertTrue(exists(own("a")) && exists(own("b")))
        XCTAssertEqual(engine.state(for: "pl")?.status, .downloading)
        XCTAssertEqual(engine.state(for: "pl")?.completedTracks, 2)
        try finish("d", fake, engine)
        XCTAssertTrue(engine.isDownloaded("pl"))

        // A saved song leaves the playlist: its file goes.
        engine.sync(playlistID: "pl", entries: rows(["b", "d"]))
        XCTAssertFalse(exists(own("a")))
        XCTAssertTrue(exists(own("b")) && exists(own("d")))
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertEqual(fake.started.count, 4)

        // A song that finishes after it left the playlist isn't kept.
        try finish("c", fake, engine)
        XCTAssertFalse(exists(own("c")))

        // A playlist that isn't downloaded is left alone.
        engine.sync(playlistID: "other", entries: rows(["x"]))
        XCTAssertNil(engine.manifests["other"])
        XCTAssertEqual(fake.started.count, 4)
        await engine.waitForArt()
    }

    func testTheServersListBringsNameAndSongsUpToDate() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a"]))
        try finish("a", fake, engine)

        fake.rows = rows(["a", "b"])
        engine.sync(with: [JellyfinPlaylist(id: "pl", name: "Renamed", songCount: 2),
                           JellyfinPlaylist(id: "other", name: "Other", songCount: 1)])
        await settle()
        XCTAssertEqual(fake.rowsCalls, 1, "only downloaded playlists are asked about")
        XCTAssertEqual(engine.manifests["pl"]?.name, "Renamed")
        XCTAssertEqual(fake.startedIDs, ["a", "b"])
        XCTAssertEqual(engine.state(for: "pl")?.name, "Renamed")

        // Not in the server's list: the download stays as it is.
        engine.sync(with: [])
        await settle()
        XCTAssertNotNil(engine.manifests["pl"])

        // Renamed in the app.
        engine.renamed(playlistID: "pl", to: "Again")
        XCTAssertEqual(PlaylistDownloadPaths.loadManifest("pl")?.name, "Again")
        await engine.waitForArt()
    }

    func testNothingIsAskedOffline() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a"]))
        fake.online = false
        engine.sync(with: [playlist])
        engine.syncAll()
        await settle()
        XCTAssertEqual(fake.rowsCalls, 0)
        await engine.waitForArt()
    }

    // MARK: - A downloaded album is deleted

    func testDeletingTheAlbumDownloadsItsSongsIntoThePlaylist() async throws {
        try albumFile("album-1", number: 1, title: "Song a")
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        let list = [entry("a", at: 0, album: "album-1", number: 1), entry("b", at: 1, album: "album-2", number: 1)]
        engine.download(playlist: playlist, entries: list)
        try finish("b", fake, engine)
        XCTAssertTrue(engine.isDownloaded("pl"))

        try DownloadPaths.delete(albumID: "album-1")
        fake.rows = list
        engine.albumRemoved("album-1")
        XCTAssertFalse(engine.isDownloaded("pl"))
        XCTAssertEqual(engine.state(for: "pl")?.status, .downloading, "shows as downloading meanwhile")
        XCTAssertEqual(engine.state(for: "pl")?.completedTracks, 1)
        await settle()
        XCTAssertEqual(fake.startedIDs, ["b", "a"])
        XCTAssertEqual(fake.started[1].info.destination.path, own("a").path, "into the playlist's own folder")
        try finish("a", fake, engine)
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertTrue(exists(own("b")), "the other song wasn't touched")
        await engine.waitForArt()
    }

    func testDeletingTheAlbumOfflineWaitsForTheConnection() async throws {
        try albumFile("album-1", number: 1, title: "Song a")
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        let list = [entry("a", at: 0, album: "album-1", number: 1)]
        engine.download(playlist: playlist, entries: list)
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertTrue(fake.started.isEmpty)

        fake.online = false
        try DownloadPaths.delete(albumID: "album-1")
        engine.albumRemoved("album-1")
        await settle()
        XCTAssertEqual(engine.state(for: "pl")?.status, .downloading)
        XCTAssertTrue(fake.started.isEmpty, "nothing can start offline")
        XCTAssertTrue(engine.downloadedPlaylists.isEmpty)

        // The connection is back.
        fake.online = true
        fake.playlists = [playlist]
        fake.rows = list
        engine.syncAll()
        await settle()
        XCTAssertEqual(fake.startedIDs, ["a"])
        try finish("a", fake, engine)
        XCTAssertTrue(engine.isDownloaded("pl"))
        await engine.waitForArt()
    }

    // MARK: - Failure, pause, remove

    func testAFailurePartWayKeepsWhatWasSavedAndRetries() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b", "c"]))
        try finish("a", fake, engine)

        engine.failed(fake.started[1].info, error: URLError(.timedOut))
        let state = try XCTUnwrap(engine.state(for: "pl"))
        XCTAssertEqual(state.status, .failed)
        XCTAssertTrue(state.errorMessage?.hasPrefix("Track download failed: ") == true, state.errorMessage ?? "")
        XCTAssertEqual(state.completedTracks, 1)
        XCTAssertTrue(fake.started[2].task.cancelled, "the rest stop")
        XCTAssertTrue(exists(own("a")), "saved songs are kept")

        // It stays failed over a relaunch, and a sync doesn't restart it.
        let again = PlaylistDownloads(backend: fake.backend)
        XCTAssertEqual(again.state(for: "pl")?.status, .failed)
        engine.sync(playlistID: "pl", entries: rows(["a", "b", "c"]))
        XCTAssertEqual(fake.started.count, 3)
        XCTAssertEqual(engine.state(for: "pl")?.status, .failed)

        fake.rows = rows(["a", "b", "c"])
        engine.retry("pl")
        XCTAssertEqual(engine.state(for: "pl")?.status, .downloading)
        await settle()
        XCTAssertEqual(fake.startedIDs, ["a", "b", "c", "b", "c"], "only the missing songs")
        try finish("b", fake, engine)
        try finish("c", fake, engine)
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertNil(PlaylistDownloadPaths.loadManifest("pl")?.failure)
        await engine.waitForArt()
    }

    func testFailureMessages() async throws {
        let fake = Fake()
        fake.noLinkFor = ["b"]
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b"]))
        XCTAssertEqual(engine.state(for: "pl")?.status, .failed)
        XCTAssertEqual(engine.state(for: "pl")?.errorMessage, "No download link for \"Song b\"")
        XCTAssertTrue(fake.started[0].task.cancelled)

        // Retry, and the server can't give the rows.
        fake.failRows = true
        engine.retry("pl")
        await settle()
        XCTAssertEqual(engine.state(for: "pl")?.status, .failed)
        XCTAssertNotNil(engine.state(for: "pl")?.errorMessage)
        await engine.waitForArt()
    }

    func testPauseAndResume() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b"]))
        try finish("a", fake, engine)

        engine.pause("pl")
        XCTAssertEqual(engine.state(for: "pl")?.status, .paused)
        XCTAssertTrue(fake.started[1].task.cancelled)
        XCTAssertEqual(PlaylistDownloadPaths.loadManifest("pl")?.paused, true)
        // The cancelled task's error is not a failure.
        engine.failed(fake.started[1].info, error: URLError(.cancelled))
        XCTAssertEqual(engine.state(for: "pl")?.status, .paused)
        // Nothing starts while it's paused.
        engine.sync(playlistID: "pl", entries: rows(["a", "b"]))
        XCTAssertEqual(fake.started.count, 2)

        fake.rows = rows(["a", "b"])
        engine.resume("pl")
        XCTAssertEqual(engine.state(for: "pl")?.status, .downloading)
        await settle()
        XCTAssertEqual(fake.startedIDs, ["a", "b", "b"])
        try finish("b", fake, engine)
        XCTAssertTrue(engine.isDownloaded("pl"))
        await engine.waitForArt()
    }

    func testRemoveDeletesTheFolderAndLeavesAlbumsAlone() async throws {
        let inAlbum = try albumFile("album-1", number: 1, title: "Song a")
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        let list = [entry("a", at: 0, album: "album-1", number: 1), entry("b", at: 1, album: "album-2", number: 1),
                    entry("c", at: 2, album: "album-2", number: 2)]
        engine.download(playlist: playlist, entries: list)
        try finish("b", fake, engine)

        engine.remove("pl")
        await engine.waitForArt()
        XCTAssertFalse(exists(PlaylistDownloadPaths.dir("pl")))
        XCTAssertTrue(exists(inAlbum))
        XCTAssertFalse(engine.hasDownload("pl"))
        XCTAssertNil(engine.state(for: "pl"))
        XCTAssertTrue(fake.started[1].task.cancelled)

        // A song that lands after the removal doesn't bring the folder back.
        try finish("c", fake, engine)
        XCTAssertFalse(exists(PlaylistDownloadPaths.dir("pl")))
        XCTAssertTrue(PlaylistDownloadPaths.downloadedIDs().isEmpty)
    }

    func testTasksFoundAtLaunchAreAdoptedOrStopped() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b"]))
        await engine.waitForArt()

        // A new launch: the session still runs a's task.
        let again = PlaylistDownloads(backend: fake.backend)
        let running = FakeTask()
        again.adopt(fake.started[0].info, task: running)
        again.sync(playlistID: "pl", entries: rows(["a", "b"]))
        XCTAssertFalse(running.cancelled)
        XCTAssertEqual(fake.startedIDs, ["a", "b", "b"], "the adopted song isn't started twice")

        // A task for a playlist with no download is stopped.
        let stray = FakeTask()
        again.adopt(DownloadTaskInfo(albumID: "", trackID: "z", trackNumber: 0, trackName: "Z", format: .flac,
                                     expectedTrackCount: 1, playlistID: "gone"), task: stray)
        XCTAssertTrue(stray.cancelled)
        await again.waitForArt()
    }

    // MARK: - Pictures

    func testArtIsSavedForOffline() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        let list = [entry("a", at: 0, album: "album-1", number: 1), entry("b", at: 1, album: "album-2", number: 1)]
        engine.download(playlist: playlist, entries: list)
        await engine.waitForArt()

        XCTAssertNotNil(PlaylistDownloadPaths.savedArt(playlistID: "pl", itemID: "album-1"))
        XCTAssertNotNil(PlaylistDownloadPaths.savedArt(playlistID: "pl", itemID: "album-2"))
        // No picture of its own on the server: the first row's.
        let cover = try XCTUnwrap(PlaylistDownloadPaths.savedCover(playlistID: "pl"))
        XCTAssertEqual(try Data(contentsOf: cover), Data("art-album-1".utf8))

        // A row's picture goes with its last song.
        engine.sync(playlistID: "pl", entries: [list[1]])
        await engine.waitForArt()
        XCTAssertNil(PlaylistDownloadPaths.savedArt(playlistID: "pl", itemID: "album-1"))
        XCTAssertEqual(try Data(contentsOf: cover), Data("art-album-2".utf8))
        // Pictures aren't songs.
        XCTAssertTrue(PlaylistDownloadPaths.trackFiles("pl").isEmpty)
    }

    // MARK: - Offline

    func testOfflineTheLibraryListsOnlyDownloadedPlaylists() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a"]))
        try finish("a", fake, engine)
        engine.download(playlist: JellyfinPlaylist(id: "half", name: "Half", songCount: 1), entries: rows(["x"]))

        let api = JellyfinAPI(baseURL: URL(string: "http://127.0.0.1:9")!, accessToken: "test-token")
        let suite = "playlist-downloads-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = PlaylistsViewModel(api: api, defaults: defaults, downloads: engine)
        model.load(online: false)
        XCTAssertTrue(model.isOffline)
        XCTAssertTrue(model.hasLoaded)
        XCTAssertNil(model.errorMessage)
        XCTAssertEqual(model.playlists.map(\.id), ["pl"])
        await engine.waitForArt()
    }

    func testOfflineThePageOpensFromTheManifest() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b", "a"]))
        try finish("a", fake, engine)
        try finish("b", fake, engine)
        engine.renamed(playlistID: "pl", to: "Renamed")

        let api = JellyfinAPI(baseURL: URL(string: "http://127.0.0.1:9")!, accessToken: "test-token")
        var asked = 0
        var server = PlaylistServer(api: api)
        server.tracks = { _, _ in asked += 1 }
        server.canEdit = { _, _ in asked += 1 }
        let model = PlaylistDetailViewModel(api: api, playlist: playlist, isOnline: { false },
                                            server: server, events: PlaylistEvents(), downloads: engine)
        model.load()
        XCTAssertEqual(asked, 0, "the server isn't asked offline")
        XCTAssertTrue(model.isOffline)
        XCTAssertTrue(model.hasLoaded)
        XCTAssertTrue(model.isDownloaded)
        XCTAssertEqual(model.entries.map(\.track.id), ["a", "b", "a"])
        XCTAssertEqual(model.entries.map(\.position), [0, 1, 2])
        XCTAssertEqual(model.playlist.name, "Renamed")
        XCTAssertFalse(model.canDownload, "nothing can be downloaded offline")

        // The queue plays the saved files, each row its own.
        let files = PlaylistFiles(playlistID: "pl")
        let sources = model.entries.map {
            PlayerManager.QueueSource(track: $0.track, album: $0.album, localURL: files.file(for: $0))
        }
        let queue = PlayerManager(api: api).playbackTracks(sources: sources, order: [0, 1, 2])
        XCTAssertEqual(queue.map { $0.localURL?.lastPathComponent }, ["a.flac", "b.flac", "a.flac"])
        await engine.waitForArt()
    }

    func testOnlineThePageKeepsTheDownloadInStep() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a"]))
        try finish("a", fake, engine)

        let api = JellyfinAPI(baseURL: URL(string: "http://127.0.0.1:9")!, accessToken: "test-token")
        var server = PlaylistServer(api: api)
        let now = rows(["b"])
        server.tracks = { _, done in done(.success(now)) }
        server.canEdit = { _, done in done(true) }
        let model = PlaylistDetailViewModel(api: api, playlist: playlist, isOnline: { true },
                                            server: server, events: PlaylistEvents(), downloads: engine)
        model.load()
        await settle()
        XCTAssertEqual(engine.manifests["pl"]?.entries.map(\.trackId), ["b"])
        XCTAssertFalse(exists(own("a")))
        XCTAssertEqual(fake.startedIDs, ["a", "b"])
        XCTAssertEqual(model.downloadState?.status, .downloading)

        model.removeDownload()
        await engine.waitForArt()
        XCTAssertFalse(engine.hasDownload("pl"))
        XCTAssertFalse(exists(PlaylistDownloadPaths.dir("pl")))
    }

    // MARK: - Once downloaded, it stays listed offline

    private func libraryModel(_ engine: PlaylistDownloads) throws -> PlaylistsViewModel {
        let api = JellyfinAPI(baseURL: URL(string: "http://127.0.0.1:9")!, accessToken: "test-token")
        let suite = "playlist-downloads-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        return PlaylistsViewModel(api: api, defaults: defaults, downloads: engine)
    }

    func testAPlaylistDownloadedOnceStaysListedOffline() async throws {
        try albumFile("album-1", number: 1, title: "Song a")
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        let list = [entry("a", at: 0, album: "album-1", number: 1), entry("b", at: 1, album: "album-2", number: 1)]
        engine.download(playlist: playlist, entries: list)
        XCTAssertNil(engine.manifests["pl"]?.downloadedAt, "not until it has finished")
        XCTAssertTrue(engine.offlinePlaylists.isEmpty)
        try finish("b", fake, engine)
        let finished = try XCTUnwrap(engine.manifests["pl"]?.downloadedAt)
        XCTAssertEqual(PlaylistDownloadPaths.loadManifest("pl")?.downloadedAt, finished, "saved with the playlist")
        XCTAssertEqual(engine.offlinePlaylists.map(\.id), ["pl"])

        // The album it relied on is deleted: a song is being fetched again.
        try DownloadPaths.delete(albumID: "album-1")
        fake.rows = list
        engine.albumRemoved("album-1")
        await settle()
        XCTAssertEqual(engine.state(for: "pl")?.status, .downloading)
        XCTAssertTrue(engine.downloadedPlaylists.isEmpty, "not among the finished downloads")
        XCTAssertEqual(engine.offlinePlaylists.map(\.id), ["pl"], "still listed offline")
        let model = try libraryModel(engine)
        model.load(online: false)
        XCTAssertEqual(model.playlists.map(\.id), ["pl"])

        // That fetch fails.
        let info = try XCTUnwrap(fake.started.last { $0.info.trackID == "a" }?.info)
        engine.failed(info, error: URLError(.timedOut))
        XCTAssertEqual(engine.state(for: "pl")?.status, .failed)
        XCTAssertEqual(engine.offlinePlaylists.map(\.id), ["pl"])
        XCTAssertEqual(engine.manifests["pl"]?.downloadedAt, finished)

        // The server's rows change: the first time stays as it was.
        engine.sync(playlistID: "pl", entries: [list[1]])
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertEqual(engine.manifests["pl"]?.downloadedAt, finished)

        // Found again at the next launch.
        let next = PlaylistDownloads(backend: fake.backend)
        next.refresh()
        XCTAssertEqual(next.offlinePlaylists.map(\.id), ["pl"])
        XCTAssertEqual(next.manifests["pl"]?.downloadedAt, finished)
        await engine.waitForArt()
        await next.waitForArt()
    }

    func testAPlaylistThatNeverFinishedIsNotListedOffline() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b"]))
        try finish("a", fake, engine)
        let info = try XCTUnwrap(fake.started.last?.info)
        engine.failed(info, error: URLError(.timedOut))
        XCTAssertNil(engine.manifests["pl"]?.downloadedAt)
        XCTAssertTrue(engine.offlinePlaylists.isEmpty)
        let model = try libraryModel(engine)
        model.load(online: false)
        XCTAssertTrue(model.playlists.isEmpty)
        XCTAssertTrue(model.hasLoaded)
        await engine.waitForArt()
    }

    func testAManifestSavedBeforeTheFinishTimeIsStillRead() async throws {
        // As written before the time was kept: no such key.
        let old = PlaylistManifest(id: "pl", name: "Mix", entries: rows(["a"]).map { .init($0, format: .flac) })
        try PlaylistDownloadPaths.save(old)
        let json = try String(contentsOf: root.appendingPathComponent("playlists/pl/playlist.json"), encoding: .utf8)
        XCTAssertFalse(json.contains("downloadedAt"))
        try Data("audio".utf8).write(to: own("a"))
        let unfinished = PlaylistManifest(id: "half", name: "Half", entries: rows(["x"]).map { .init($0, format: .flac) })
        try PlaylistDownloadPaths.save(unfinished)

        let engine = PlaylistDownloads(backend: Fake().backend)
        engine.refresh()
        XCTAssertEqual(engine.manifests.keys.sorted(), ["half", "pl"])
        XCTAssertEqual(engine.offlinePlaylists.map(\.id), ["pl"], "complete on disk, so it counts as finished")
        XCTAssertNotNil(PlaylistDownloadPaths.loadManifest("pl")?.downloadedAt)
        XCTAssertNil(PlaylistDownloadPaths.loadManifest("half")?.downloadedAt)
        await engine.waitForArt()
    }

    // MARK: - Offline, with songs missing

    func testOfflineTheQueueHoldsOnlyTheSongsOnTheDevice() throws {
        typealias Refusal = PlaylistPlayRefusal
        func plan(_ onDevice: [Bool], _ start: Int?, online: Bool = false) -> Result<[Int], Refusal> {
            PlayerManager.playlistQueue(onDevice: onDevice, startingAt: start, online: online).map(\.places)
        }
        func start(_ onDevice: [Bool], _ start: Int?, online: Bool = false) -> Int? {
            try? PlayerManager.playlistQueue(onDevice: onDevice, startingAt: start, online: online).get().start
        }
        let some = [true, false, true, true, false]

        // Online every row plays, saved or streamed.
        XCTAssertEqual(plan(some, nil, online: true), .success([0, 1, 2, 3, 4]))
        XCTAssertEqual(start(some, 1, online: true), 1)
        XCTAssertEqual(plan([false, false], 1, online: true), .success([0, 1]))

        // Offline only the saved ones, each still at its place on the page.
        XCTAssertEqual(plan(some, nil), .success([0, 2, 3]))
        XCTAssertNil(start(some, nil))
        XCTAssertEqual(plan(some, 3), .success([0, 2, 3]))
        XCTAssertEqual(start(some, 3), 2, "the tapped row, counted among the saved ones")
        XCTAssertEqual(start(some, 0), 0)

        // A tapped song that isn't saved; a playlist with none saved.
        XCTAssertEqual(plan(some, 1), .failure(.songNotDownloaded))
        XCTAssertEqual(plan(some, 4), .failure(.songNotDownloaded))
        XCTAssertEqual(plan([false, false], nil), .failure(.nothingDownloaded))
        XCTAssertEqual(plan([false, false], 0), .failure(.nothingDownloaded))
        XCTAssertEqual(plan([], nil), .failure(.nothingDownloaded))

        XCTAssertEqual(Refusal.songNotDownloaded.message, "This song isn't downloaded.")
        XCTAssertEqual(Refusal.nothingDownloaded.message, "None of this playlist's songs are downloaded.")
    }

    func testOfflineAMissingSongSaysSoAndNothingPlays() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b"]))
        try finish("a", fake, engine)

        let api = JellyfinAPI(baseURL: URL(string: "http://127.0.0.1:9")!, accessToken: "test-token")
        let player = PlayerManager(api: api)
        let events = PlaylistEvents()
        let model = PlaylistDetailViewModel(api: api, playlist: playlist, player: player, isOnline: { false },
                                            server: PlaylistServer(api: api), events: events, downloads: engine)
        model.load()
        XCTAssertEqual(model.entries.map(\.track.id), ["a", "b"])

        // The second song never arrived.
        model.play(from: model.entries[1])
        XCTAssertEqual(events.notice?.message, "This song isn't downloaded.")
        XCTAssertEqual(events.notice?.isError, true)
        XCTAssertTrue(player.queue.isEmpty)
        XCTAssertNil(player.currentTrack)
        XCTAssertFalse(player.isNowPlayingPresented)

        // Its one saved song goes too (its file, not the playlist).
        try FileManager.default.removeItem(at: own("a"))
        events.dismissNotice()
        model.play()
        XCTAssertEqual(events.notice?.message, "None of this playlist's songs are downloaded.")
        XCTAssertEqual(events.notice?.isError, true)
        XCTAssertFalse(player.isNowPlayingPresented)

        let shuffleBefore = player.shuffle
        events.dismissNotice()
        model.shuffle()
        XCTAssertEqual(events.notice?.message, "None of this playlist's songs are downloaded.")
        XCTAssertEqual(player.shuffle, shuffleBefore, "shuffle is left as it was")
        XCTAssertTrue(player.queue.isEmpty)
        XCTAssertFalse(player.isNowPlayingPresented)
        await engine.waitForArt()
    }

    // MARK: - Total playing time

    func testPlayingTimeCountsAPlaylistsOwnSongsOnce() async throws {
        try albumFile("album-1", number: 1, title: "Song a")
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        // a plays from its album; b is in the playlist twice; c once. A minute each.
        let list = [entry("a", at: 0, album: "album-1", number: 1), entry("b", at: 1, album: "album-2", number: 1),
                    entry("b", at: 2, album: "album-2", number: 1), entry("c", at: 3, album: "album-2", number: 2)]
        engine.download(playlist: playlist, entries: list)
        try finish("b", fake, engine)
        XCTAssertEqual(engine.ownPlayingTime, 0, "nothing until the playlist is downloaded")
        try finish("c", fake, engine)
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertEqual(engine.ownPlayingTime, 120, "b once and c; a is counted with its album")

        // A second playlist with its own copy of b.
        let other = JellyfinPlaylist(id: "other", name: "Other", songCount: 1)
        engine.download(playlist: other, entries: [entry("b", at: 0, album: "album-2", number: 1)])
        try finish("b", fake, engine)
        XCTAssertEqual(engine.ownPlayingTime, 180)

        // Added to the albums' time.
        XCTAssertEqual(DownloadsView.totalDuration(of: ["album-1"], durations: ["album-1": 60],
                                                   playlists: engine.ownPlayingTime), 240)
        XCTAssertEqual(DownloadsView.totalDuration(of: [], durations: [:], playlists: engine.ownPlayingTime), 180)
        XCTAssertEqual(DownloadsView.totalDuration(of: ["album-1"], durations: ["album-1": 60]), 60)

        engine.remove("other")
        XCTAssertEqual(engine.ownPlayingTime, 120)
        await engine.waitForArt()
    }

    // MARK: - The Library's list keeps downloads in step

    func testWhatCountsAsDifferentFromTheServersListing() {
        let manifest = PlaylistManifest(id: "pl", name: "Mix", entries: rows(["a", "b"]).map { .init($0, format: .flac) })
        func listed(name: String = "Mix", songs: Int = 2, seconds: Double = 120) -> JellyfinPlaylist {
            JellyfinPlaylist(id: "pl", name: name, songCount: songs, runTimeTicks: Int64(seconds * 10_000_000))
        }
        XCTAssertEqual(manifest.durationSeconds, 120)
        XCTAssertFalse(manifest.differs(from: listed()))
        XCTAssertTrue(manifest.differs(from: listed(name: "Renamed")))
        XCTAssertTrue(manifest.differs(from: listed(songs: 3)))
        XCTAssertTrue(manifest.differs(from: listed(songs: 1)))
        XCTAssertFalse(manifest.differs(from: listed(seconds: 122)), "within two seconds")
        XCTAssertFalse(manifest.differs(from: listed(seconds: 118)))
        XCTAssertTrue(manifest.differs(from: listed(seconds: 122.5)))
        XCTAssertTrue(manifest.differs(from: listed(seconds: 117)))
    }

    func testTheLibrarysListBringsChangedDownloadsInStep() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b"]))
        try finish("a", fake, engine)
        try finish("b", fake, engine)
        let model = try libraryModel(engine)
        let same = JellyfinPlaylist(id: "pl", name: "Mix", songCount: 2, runTimeTicks: 1_200_000_000)
        let notDownloaded = JellyfinPlaylist(id: "other", name: "Other", songCount: 9, runTimeTicks: 1)

        // Nothing differs: the server isn't asked.
        model.syncDownloads(with: [same, notDownloaded])
        await settle()
        XCTAssertEqual(fake.rowsCalls, 0)

        // A song was added somewhere else.
        fake.rows = rows(["a", "b", "c"])
        model.syncDownloads(with: [JellyfinPlaylist(id: "pl", name: "Mix", songCount: 3, runTimeTicks: 1_800_000_000),
                                   notDownloaded])
        await settle()
        XCTAssertEqual(fake.rowsCalls, 1)
        XCTAssertEqual(engine.manifests["pl"]?.entries.map(\.trackId), ["a", "b", "c"])
        XCTAssertEqual(fake.startedIDs, ["a", "b", "c"])
        try finish("c", fake, engine)

        // Renamed only.
        model.syncDownloads(with: [JellyfinPlaylist(id: "pl", name: "Renamed", songCount: 3,
                                                    runTimeTicks: 1_800_000_000)])
        await settle()
        XCTAssertEqual(fake.rowsCalls, 2)
        XCTAssertEqual(engine.manifests["pl"]?.name, "Renamed")
        await engine.waitForArt()
    }

    func testAListingThatLagsBehindRemovesNothing() async throws {
        let fake = Fake()
        let engine = PlaylistDownloads(backend: fake.backend)
        engine.download(playlist: playlist, entries: rows(["a", "b", "c"]))
        for id in ["a", "b", "c"] { try finish(id, fake, engine) }
        let model = try libraryModel(engine)

        // The listing still has the count and time from before a song was
        // added; the playlist's rows are the truth.
        fake.rows = rows(["a", "b", "c"])
        model.syncDownloads(with: [JellyfinPlaylist(id: "pl", name: "Mix", songCount: 2, runTimeTicks: 1_200_000_000)])
        await settle()
        XCTAssertEqual(fake.rowsCalls, 1, "it differs, so the rows are read again")
        XCTAssertEqual(engine.manifests["pl"]?.entries.map(\.trackId), ["a", "b", "c"])
        XCTAssertTrue(exists(own("a")) && exists(own("b")) && exists(own("c")), "no file is removed")
        XCTAssertTrue(engine.isDownloaded("pl"))
        XCTAssertEqual(fake.started.count, 3, "and nothing is fetched again")

        // The rows can't be read: everything stays.
        fake.failRows = true
        model.syncDownloads(with: [JellyfinPlaylist(id: "pl", name: "Mix", songCount: 1, runTimeTicks: 600_000_000)])
        await settle()
        XCTAssertEqual(engine.manifests["pl"]?.entries.count, 3)
        XCTAssertTrue(exists(own("a")) && exists(own("b")) && exists(own("c")))
        await engine.waitForArt()
    }

    // MARK: - Wording

    func testDownloadsTabWording() {
        XCTAssertEqual(DownloadsView.removalMessage(albums: 2, playlists: 0),
                       "They'll be deleted from this device. You can download them again from the album page.")
        XCTAssertEqual(DownloadsView.removalMessage(albums: 0, playlists: 1),
                       "They'll be deleted from this device. You can download them again from the playlist page.")
        XCTAssertEqual(DownloadsView.removalMessage(albums: 1, playlists: 1),
                       "They'll be deleted from this device. You can download them again from their pages.")
    }
}
