import XCTest
@testable import JellyfinMusicCore

final class DownloadsDetailLineTests: XCTestCase {
    func testTrackCountThenDurationThenSize() {
        let size: Int64 = 512_000_000
        let sizeText = DownloadPaths.formatBytes(size)
        XCTAssertEqual(DownloadsView.detailLine(count: 12, duration: 2832, size: size), "12 tracks · 47m 12s · \(sizeText)")
        XCTAssertEqual(DownloadsView.detailLine(count: 25, duration: 6305, size: size), "25 tracks · 1h 45m 05s · \(sizeText)")
    }

    func testAlbumDurationFormat() {
        XCTAssertEqual(NowPlayingView.formatAlbumDuration(0), "0m 00s")
        XCTAssertEqual(NowPlayingView.formatAlbumDuration(65), "1m 05s")
        XCTAssertEqual(NowPlayingView.formatAlbumDuration(1419), "23m 39s")
        XCTAssertEqual(NowPlayingView.formatAlbumDuration(3600), "1h 00m 00s")
    }

    func testDurationLeftOutUntilKnown() {
        let sizeText = DownloadPaths.formatBytes(1_000)
        XCTAssertEqual(DownloadsView.detailLine(count: 1, duration: nil, size: 1_000), "1 track · \(sizeText)")
    }

    func testTotalDurationAddsEveryAlbum() {
        XCTAssertEqual(DownloadsView.totalDuration(of: ["a", "b"], durations: ["a": 600, "b": 3005, "gone": 99]), 3605)
    }

    func testTotalDurationWaitsForUnknownAlbums() {
        XCTAssertNil(DownloadsView.totalDuration(of: ["a", "b"], durations: ["a": 600]))
    }
}
