import XCTest
@testable import JellyfinMusicCore

/// Downloads of untagged tracks (no track numbers): each needs its own
/// number, or every track plays with the first one's title.
final class UntaggedTrackNumberTests: XCTestCase {

    private func tracks(_ json: String) throws -> [JellyfinTrack] {
        try JSONDecoder().decode([JellyfinTrack].self, from: Data(json.utf8))
    }

    func testUntaggedTracksAreNumberedInPlayOrder() throws {
        // As Mercy Girl on the server: loose files, no Album, no IndexNumber.
        let t = try tracks(#"[{"Id":"a","Name":"Closer"},{"Id":"b","Name":"Ghost"},{"Id":"c","Name":"Heaven"}]"#)
        XCTAssertEqual(t.map(\.fileNumber), [0, 0, 0])
        XCTAssertEqual(JellyfinTrack.fileNumbers(for: t), [1, 2, 3])
    }

    func testTaggedTracksKeepTheirNumbers() throws {
        let t = try tracks(#"[{"Id":"a","IndexNumber":1},{"Id":"b","IndexNumber":2},{"Id":"c","IndexNumber":1,"ParentIndexNumber":2}]"#)
        XCTAssertEqual(JellyfinTrack.fileNumbers(for: t), [1, 2, 201])
    }

    func testOldDownloadsMatchServerTitlesByFileName() {
        // Files saved before the fix: all "00 - <sanitised title>".
        let byFile = DownloadedTitles.byFileName(["Closer", "I'm In Love", "Closer - Ubre Blance Remix"])
        XCTAssertEqual(byFile[DownloadedTitles.fileTitle("00 - Im In Love.flac")], "I'm In Love")
        XCTAssertEqual(byFile[DownloadedTitles.fileTitle("00 - Closer - Ubre Blance Remix.m4a")], "Closer - Ubre Blance Remix")
        XCTAssertEqual(DownloadedTitles.fileTitle("12 - Ghost.flac"), "Ghost")
    }

    func testRepeatedNumbersFallBackToPosition() throws {
        let t = try tracks(#"[{"Id":"a","IndexNumber":1},{"Id":"b","IndexNumber":1}]"#)
        XCTAssertEqual(JellyfinTrack.fileNumbers(for: t), [1, 2])
        XCTAssertEqual(JellyfinTrack.fileNumbers(for: []), [])
    }
}
