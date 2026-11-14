import XCTest
@testable import JellyfinMusicCore

final class DownloadsOrderTests: XCTestCase {

    func testNewestDownloadFirst() {
        let dates: [String: Date] = [
            "old": Date(timeIntervalSince1970: 100),
            "new": Date(timeIntervalSince1970: 300),
            "mid": Date(timeIntervalSince1970: 200),
        ]
        XCTAssertEqual(DownloadPaths.newestFirst(["old", "new", "mid"]) { dates[$0] },
                       ["new", "mid", "old"])
    }

    func testUndatedAlbumsGoLastInIDOrder() {
        let dates: [String: Date] = ["dated": Date(timeIntervalSince1970: 100)]
        XCTAssertEqual(DownloadPaths.newestFirst(["b", "dated", "a"]) { dates[$0] },
                       ["dated", "a", "b"])
    }
}
