import XCTest
@testable import JellyfinMusicCore

final class DownloadedTitlesTests: XCTestCase {
    func testTitlesKeepBracketsAndPunctuation() {
        let album = "test-album-\(UUID().uuidString)"
        XCTAssertTrue(DownloadedTitles.titles(for: album).isEmpty)
        DownloadedTitles.save([1: "Intro (Nine Inch Noize Version)",
                               7: "Me, I’m Not (Nine Inch Noize Version)"], for: album)
        let titles = DownloadedTitles.titles(for: album)
        XCTAssertEqual(titles[1], "Intro (Nine Inch Noize Version)")
        XCTAssertEqual(titles[7], "Me, I’m Not (Nine Inch Noize Version)")
        // The file name is what loses them.
        XCTAssertEqual(DownloadPaths.sanitize(titles[7]!), "Me Im Not Nine Inch Noize Version")
    }
}
