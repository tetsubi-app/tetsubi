import XCTest
@testable import JellyfinMusicCore

final class FailedDownloadsTests: XCTestCase {

    private var suiteName: String!
    private var tempDir: URL!

    override func setUp() {
        suiteName = "FailedDownloadsTests-\(UUID().uuidString)"
        FailedDownloads.defaults = UserDefaults(suiteName: suiteName)!
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("FailedDownloadsTests-\(UUID().uuidString)")
        DownloadPaths.rootDirOverride = tempDir
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suiteName)
        FailedDownloads.defaults = .standard
        try? FileManager.default.removeItem(at: tempDir)
        DownloadPaths.rootDirOverride = nil
    }

    private func entry(_ id: String) -> FailedDownloads.Entry {
        .init(albumID: id, message: "HTTP 500", format: .flac, expectedTrackCount: 12)
    }

    func testSaveReplaceAndRemove() {
        FailedDownloads.save(entry("a"))
        FailedDownloads.save(entry("b"))
        FailedDownloads.save(.init(albumID: "a", message: "Timed out", format: .mp3, expectedTrackCount: 12))
        XCTAssertEqual(FailedDownloads.all().count, 2)
        XCTAssertEqual(FailedDownloads.all().first { $0.albumID == "a" }?.message, "Timed out")
        FailedDownloads.remove(albumID: "a")
        XCTAssertEqual(FailedDownloads.all(), [entry("b")])
    }

    /// Retry skips tracks already on disk, even in a different format.
    func testTrackExistsIgnoresExtension() throws {
        let mp3 = DownloadPaths.trackPath(albumID: "album", trackNumber: 2,
                                          trackName: "Song v1.2", format: .mp3)
        try FileManager.default.createDirectory(at: mp3.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try Data([0]).write(to: mp3)
        XCTAssertTrue(DownloadPaths.trackExists(albumID: "album", trackNumber: 2, trackName: "Song v1.2"))
        XCTAssertFalse(DownloadPaths.trackExists(albumID: "album", trackNumber: 3, trackName: "Song v1.2"))
        XCTAssertFalse(DownloadPaths.trackExists(albumID: "album", trackNumber: 2, trackName: "Other"))
    }
}
