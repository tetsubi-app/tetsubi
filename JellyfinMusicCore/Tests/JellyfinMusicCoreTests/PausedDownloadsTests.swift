import XCTest
@testable import JellyfinMusicCore

final class PausedDownloadsTests: XCTestCase {

    private var tempDir: URL!

    override func setUp() {
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("PausedDownloadsTests-\(UUID().uuidString)")
        PausedDownloads.rootDirOverride = tempDir
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: tempDir)
        PausedDownloads.rootDirOverride = nil
    }

    private func track(_ id: String, album: String = "album1", resume: Data? = nil) -> PausedTrack {
        PausedTrack(
            info: DownloadTaskInfo(albumID: album, trackID: id, trackNumber: 1,
                                   trackName: "Song", format: .flac, expectedTrackCount: 2),
            url: URL(string: "http://server/Audio/\(id)/stream.flac?static=true"),
            resumeData: resume)
    }

    func testSaveAndLoadKeepsResumeData() {
        let saved = track("t1", resume: Data([1, 2, 3]))
        PausedDownloads.save(saved)
        PausedDownloads.save(track("t2"))
        let loaded = PausedDownloads.load(albumID: "album1")
        XCTAssertEqual(Set(loaded.map(\.info.trackID)), ["t1", "t2"])
        XCTAssertEqual(loaded.first { $0.info.trackID == "t1" }, saved)
    }

    func testAlbumIDsAndRemove() {
        PausedDownloads.save(track("t1", album: "a"))
        PausedDownloads.save(track("t2", album: "b"))
        XCTAssertEqual(Set(PausedDownloads.albumIDs()), ["a", "b"])
        PausedDownloads.remove(albumID: "a")
        XCTAssertEqual(PausedDownloads.albumIDs(), ["b"])
        XCTAssertTrue(PausedDownloads.load(albumID: "a").isEmpty)
    }
}
