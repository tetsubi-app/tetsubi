import XCTest
@testable import JellyfinMusicCore

/// Album, Playlist and Artist share one layout (`DetailLayout`): stacked on
/// compact, two panes on expanded and on medium pages 900 wide or more.
final class DetailPageTests: XCTestCase {

    func testCompactIsAlwaysStacked() {
        XCTAssertFalse(DetailLayout.isTwoPane(windowClass: .compact, paneWidth: 390))
        XCTAssertFalse(DetailLayout.isTwoPane(windowClass: .compact, paneWidth: 1200))
    }

    func testMediumNeedsAPageOf900() {
        XCTAssertFalse(DetailLayout.isTwoPane(windowClass: .medium, paneWidth: 834))   // an iPad Pro 11 portrait
        XCTAssertFalse(DetailLayout.isTwoPane(windowClass: .medium, paneWidth: 899))
        XCTAssertTrue(DetailLayout.isTwoPane(windowClass: .medium, paneWidth: 900))
    }

    func testExpandedIsAlwaysTwoPanes() {
        XCTAssertTrue(DetailLayout.isTwoPane(windowClass: .expanded, paneWidth: 784))  // a 1024 Mac window beside the sidebar
        XCTAssertTrue(DetailLayout.isTwoPane(windowClass: .expanded, paneWidth: 1194))
    }

    func testTheStackedCoverFitsBetweenTheMargins() {
        XCTAssertEqual(DetailLayout.stackedArtSize(paneWidth: 390, windowClass: .compact), 260)   // up to 260
        XCTAssertEqual(DetailLayout.stackedArtSize(paneWidth: 280, windowClass: .compact), 248)   // 280 - 2 x 16
        XCTAssertEqual(DetailLayout.stackedArtSize(paneWidth: 834, windowClass: .medium), 320)    // up to 320 on medium
        XCTAssertEqual(DetailLayout.stackedArtSize(paneWidth: 0, windowClass: .compact), 0)
    }

    func testAlbumMetaListsWhatIsKnown() {
        XCTAssertEqual(AlbumDetailViewModel.metaLine(year: 1991, trackCount: 12, duration: 2892), "1991 · 12 tracks · 48m 12s")
        XCTAssertEqual(AlbumDetailViewModel.metaLine(year: nil, trackCount: 1, duration: 200), "1 track · 3m 20s")
        XCTAssertEqual(AlbumDetailViewModel.metaLine(year: 1991, trackCount: 0, duration: 0), "1991")
        XCTAssertNil(AlbumDetailViewModel.metaLine(year: nil, trackCount: 0, duration: 0))
    }

    func testTrackTimes() {
        XCTAssertEqual(TrackTime.format(257), "4:17")
        XCTAssertEqual(TrackTime.format(59.9), "0:59")
        XCTAssertEqual(TrackTime.format(3601), "60:01")
    }

    func testArtistAlbumCount() {
        XCTAssertNil(ArtistAlbumsView.albumCount(0))
        XCTAssertEqual(ArtistAlbumsView.albumCount(1), "1 album")
        XCTAssertEqual(ArtistAlbumsView.albumCount(12), "12 albums")
    }
}
