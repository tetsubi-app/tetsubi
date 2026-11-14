import XCTest
@testable import JellyfinMusicCore

/// Saved covers for downloaded albums (shown on the lock screen offline).
final class DownloadCoverTests: XCTestCase {

    private var tempRoot: URL!

    override func setUp() {
        super.setUp()
        tempRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("DownloadCoverTests-\(UUID().uuidString)", isDirectory: true)
        DownloadPaths.rootDirOverride = tempRoot
    }

    override func tearDown() {
        DownloadPaths.rootDirOverride = nil
        try? FileManager.default.removeItem(at: tempRoot)
        super.tearDown()
    }

    private func addTrack(albumID: String) throws {
        let dir = DownloadPaths.albumDir(albumID)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data([1, 2, 3]).write(to: dir.appendingPathComponent("01 - Song.flac"))
    }

    func testCoverIsSavedAndFound() throws {
        XCTAssertNil(DownloadPaths.savedCover(albumID: "a1"))
        try DownloadPaths.saveCover(Data([0xFF, 0xD8]), albumID: "a1")
        XCTAssertEqual(DownloadPaths.savedCover(albumID: "a1"), DownloadPaths.coverPath(albumID: "a1"))
    }

    func testCoversAreNotCountedAsAlbumsOrTracks() throws {
        try addTrack(albumID: "a1")
        try DownloadPaths.saveCover(Data([0xFF, 0xD8]), albumID: "a1")
        XCTAssertEqual(DownloadPaths.downloadedAlbumIDs(), ["a1"])
        XCTAssertEqual(DownloadPaths.fileCount(albumID: "a1"), 1)
    }

    func testDeletingAnAlbumDeletesItsCover() throws {
        try addTrack(albumID: "a1")
        try DownloadPaths.saveCover(Data([0xFF, 0xD8]), albumID: "a1")
        try DownloadPaths.delete(albumID: "a1")
        XCTAssertNil(DownloadPaths.savedCover(albumID: "a1"))
        XCTAssertTrue(DownloadPaths.downloadedAlbumIDs().isEmpty)
    }
}
