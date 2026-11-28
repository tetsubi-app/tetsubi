import XCTest
@testable import JellyfinMusicCore

/// Downloads cards ask for a card-sized picture, not a 100 px thumbnail.
final class DownloadArtRequestTests: XCTestCase {
    private let saved = URL(fileURLWithPath: "/tmp/cover.jpg")
    private let remote = URLRequest(url: URL(string: "https://example.invalid/Items/a/Images/Primary?maxWidth=600")!)

    func testCardSizeUsesTheSavedCoverWhenThereIsOne() {
        let r = DownloadManager.artRequest(albumID: "a", size: DownloadManager.coverSize, saved: { _ in self.saved }, remote: { self.remote })
        XCTAssertEqual(r?.url, saved)
    }

    func testCardSizeFallsBackToTheServerWhenNothingIsSaved() {
        let r = DownloadManager.artRequest(albumID: "a", size: DownloadManager.coverSize, saved: { _ in nil }, remote: { self.remote })
        XCTAssertEqual(r?.url, remote.url)
    }

    func testThumbnailsAlwaysComeFromTheServer() {
        let r = DownloadManager.artRequest(albumID: "a", size: 100, saved: { _ in self.saved }, remote: { self.remote })
        XCTAssertEqual(r?.url, remote.url)
    }

    func testCardRequestsAreBiggerThanThumbnails() {
        XCTAssertGreaterThan(DownloadManager.coverSize, DownloadManager.thumbnailSize)
    }
}
