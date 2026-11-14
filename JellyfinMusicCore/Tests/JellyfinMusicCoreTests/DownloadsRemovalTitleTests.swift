import XCTest
@testable import JellyfinMusicCore

final class DownloadsRemovalTitleTests: XCTestCase {

    func testSingleDownloadTitle() {
        XCTAssertEqual(DownloadsView.removalTitle(count: 1), "Remove this download?")
    }

    func testSeveralDownloadsTitle() {
        XCTAssertEqual(DownloadsView.removalTitle(count: 2), "Remove 2 downloads?")
        XCTAssertEqual(DownloadsView.removalTitle(count: 12), "Remove 12 downloads?")
    }
}
