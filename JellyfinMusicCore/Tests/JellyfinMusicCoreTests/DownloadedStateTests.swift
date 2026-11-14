import XCTest
@testable import JellyfinMusicCore

/// "Downloaded" only once the whole album is saved, not after the first track.
final class DownloadedStateTests: XCTestCase {

    func testAlbumStillDownloadingIsNotDownloaded() {
        // First tracks are on disk, the rest are still coming.
        XCTAssertFalse(DownloadManager.isDownloaded(inProgress: true, finished: false, onDisk: true))
    }

    func testFinishedAlbumIsDownloaded() {
        XCTAssertTrue(DownloadManager.isDownloaded(inProgress: false, finished: true, onDisk: true))
    }

    func testAlbumFoundOnDiskAtLaunchIsDownloaded() {
        XCTAssertTrue(DownloadManager.isDownloaded(inProgress: false, finished: false, onDisk: true))
    }

    func testPausedOrFailedAlbumIsNotDownloaded() {
        // Paused and failed albums stay in progress, with partial files on disk.
        XCTAssertFalse(DownloadManager.isDownloaded(inProgress: true, finished: false, onDisk: true))
    }

    func testNothingOnDiskIsNotDownloaded() {
        XCTAssertFalse(DownloadManager.isDownloaded(inProgress: false, finished: false, onDisk: false))
    }
}
