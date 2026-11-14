import XCTest
@testable import JellyfinMusicCore

final class DownloadTaskInfoTests: XCTestCase {

    private let info = DownloadTaskInfo(
        albumID: "album1", trackID: "track1", trackNumber: 3,
        trackName: "Song", format: .mp3, expectedTrackCount: 10)

    /// The background session hands this string back after a relaunch.
    func testRoundTripsThroughTaskDescription() {
        XCTAssertEqual(DownloadTaskInfo(taskDescription: info.encoded), info)
    }

    /// Tasks started before `retries` existed must still be recognised.
    func testDecodesDescriptionWithoutRetries() {
        let old = #"{"albumID":"album1","trackID":"track1","trackNumber":3,"trackName":"Song","format":"mp3","expectedTrackCount":10}"#
        let decoded = DownloadTaskInfo(taskDescription: old)
        XCTAssertEqual(decoded, info)
        XCTAssertNil(decoded?.retries)
    }

    func testRetryCountSurvivesEncoding() {
        var retried = info
        retried.retries = 2
        XCTAssertEqual(DownloadTaskInfo(taskDescription: retried.encoded)?.retries, 2)
    }

    func testRejectsMissingOrForeignDescriptions() {
        XCTAssertNil(DownloadTaskInfo(taskDescription: nil))
        XCTAssertNil(DownloadTaskInfo(taskDescription: "not json"))
    }

    func testDestinationMatchesDownloadPaths() {
        XCTAssertEqual(info.destination, DownloadPaths.trackPath(
            albumID: "album1", trackNumber: 3, trackName: "Song", format: .mp3))
        XCTAssertEqual(info.destination.lastPathComponent, "03 - Song.mp3")
    }
}
