import XCTest
@testable import JellyfinMusicCore

/// An album page's track list built from a download (offline, or when the
/// server fails).
@MainActor
final class DownloadedTracksTests: XCTestCase {

    private var tempRoot: URL!

    override func setUp() {
        super.setUp()
        tempRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("DownloadedTracksTests-\(UUID().uuidString)", isDirectory: true)
        DownloadPaths.rootDirOverride = tempRoot
    }

    override func tearDown() {
        DownloadPaths.rootDirOverride = nil
        try? FileManager.default.removeItem(at: tempRoot)
        super.tearDown()
    }

    private func addFiles(_ names: [String], albumID: String) throws {
        let dir = DownloadPaths.albumDir(albumID)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for name in names { try Data([1, 2, 3]).write(to: dir.appendingPathComponent(name)) }
    }

    private func newID() -> String { "album-\(UUID().uuidString)" }

    private func album(_ id: String, _ name: String) -> JellyfinAlbum {
        JellyfinAlbum(id: id, name: name, serverId: nil, premiereDate: nil, productionYear: nil,
                      albumArtists: nil, album: nil, imageTags: nil, runTimeTicks: nil,
                      type: nil, userData: nil)
    }

    /// No token, so every server request fails at once.
    private let offlineAPI = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!)

    private func waitForTracks(_ vm: AlbumDetailViewModel) async throws {
        for _ in 0..<200 where vm.isLoadingTracks {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertFalse(vm.isLoadingTracks)
    }

    // MARK: - Track list

    func testSavedTitlesNumbersAndIds() throws {
        let id = newID()
        try addFiles(["02 - Me Im Not.flac", "01 - Intro.flac", "03 - Ghost.flac"], albumID: id)
        DownloadedTitles.save([1: "Intro (Remix)", 2: "Me, I’m Not"], for: id)

        let tracks = DownloadedTracks.tracks(albumID: id, albumTitle: "Ghosts")
        XCTAssertEqual(tracks.map(\.displayName), ["Intro (Remix)", "Me, I’m Not", "Ghost"])
        XCTAssertEqual(tracks.map(\.trackNumber), [1, 2, 3])
        XCTAssertEqual(tracks.map(\.discNumber), [nil, nil, nil])
        XCTAssertEqual(tracks.first?.id, id + "/01 - Intro.flac")   // same id as the queued local track
        XCTAssertEqual(tracks.first?.albumId, id)
        XCTAssertEqual(tracks.first?.album, "Ghosts")
        XCTAssertEqual(tracks.first?.duration, 0)
    }

    func testDiscNumbersFromFileNumbers() throws {
        let id = newID()
        try addFiles(["01 - A.flac", "02 - B.flac", "201 - C.flac", "202 - D.flac"], albumID: id)
        DownloadedTitles.save([201: "C!"], for: id)

        let tracks = DownloadedTracks.tracks(albumID: id, albumTitle: nil)
        XCTAssertEqual(tracks.map(\.trackNumber), [1, 2, 1, 2])
        XCTAssertEqual(tracks.map(\.discNumber), [1, 1, 2, 2])
        XCTAssertEqual(tracks[2].displayName, "C!")
        let groups = DiscGrouping.byDiscNumber(tracks)
        XCTAssertEqual(groups?.map(\.number), [1, 2])
        XCTAssertEqual(groups?.last?.tracks.map(\.displayName), ["C!", "D"])
    }

    func testClashingNumbersUseTitlesByFileAndPosition() throws {
        let id = newID()
        try addFiles(["00 - Closer.flac", "00 - Im In Love.flac"], albumID: id)
        DownloadedTitles.save([0: "Wrong"], for: id)
        DownloadedTitles.saveByFile(DownloadedTitles.byFileName(["I'm In Love"]), for: id)

        let tracks = DownloadedTracks.tracks(albumID: id, albumTitle: nil)
        XCTAssertEqual(tracks.map(\.displayName), ["Closer", "I'm In Love"])
        XCTAssertEqual(tracks.map(\.trackNumber), [1, 2])
    }

    func testDurationsByFileName() throws {
        let id = newID()
        try addFiles(["01 - A.flac", "02 - B.flac"], albumID: id)
        let tracks = DownloadedTracks.tracks(albumID: id, albumTitle: nil, durations: ["02 - B.flac": 125])
        XCTAssertEqual(tracks.map(\.duration), [0, 125])
    }

    func testNoDownloadNoTracks() {
        XCTAssertTrue(DownloadedTracks.tracks(albumID: newID(), albumTitle: nil).isEmpty)
    }

    // MARK: - Album page

    func testOfflineDownloadedAlbumAsksNothing() async throws {
        let id = newID()
        try addFiles(["01 - A.flac", "02 - B.flac"], albumID: id)
        let vm = AlbumDetailViewModel(api: offlineAPI, album: album(id, "Album"), isOnline: { false })
        vm.load()
        try await waitForTracks(vm)
        XCTAssertTrue(vm.isOffline)
        XCTAssertEqual(vm.tracks.map(\.displayName), ["A", "B"])
        XCTAssertEqual(vm.discsFromDownload, [id])
        XCTAssertNil(vm.errorMessage)
        XCTAssertTrue(vm.moreByArtist.isEmpty)
        XCTAssertNil(vm.artistInfo)
    }

    func testServerFailureFallsBackToDownload() async throws {
        let id = newID()
        try addFiles(["01 - A.flac"], albumID: id)
        let vm = AlbumDetailViewModel(api: offlineAPI, album: album(id, "Album"), isOnline: { true })
        vm.load()
        try await waitForTracks(vm)
        XCTAssertFalse(vm.isOffline)
        XCTAssertEqual(vm.tracks.map(\.displayName), ["A"])
        XCTAssertEqual(vm.discsFromDownload, [id])
        XCTAssertNil(vm.errorMessage)
    }

    func testNotDownloadedStillShowsServerError() async throws {
        let vm = AlbumDetailViewModel(api: offlineAPI, album: album(newID(), "Album"), isOnline: { false })
        vm.load()
        try await waitForTracks(vm)
        XCTAssertTrue(vm.tracks.isEmpty)
        XCTAssertNotNil(vm.errorMessage)
    }

    func testOfflineMultiDiscShowsDownloadedDiscs() async throws {
        let disc1 = album(newID(), "Set (Disc 1)")
        let disc2 = album(newID(), "Set (Disc 2)")
        try addFiles(["01 - Two A.flac"], albumID: disc2.id)
        let vm = AlbumDetailViewModel(api: offlineAPI, album: DiscGrouping.combined([disc1, disc2]),
                                      isOnline: { false })
        vm.load()
        try await waitForTracks(vm)
        XCTAssertTrue(vm.isMultiDisc)
        XCTAssertEqual(vm.tracksByDisc.map(\.disc.id), [disc2.id])
        XCTAssertEqual(vm.tracks.map(\.displayName), ["Two A"])
        XCTAssertEqual(vm.discsFromDownload, [disc2.id])
    }
}
