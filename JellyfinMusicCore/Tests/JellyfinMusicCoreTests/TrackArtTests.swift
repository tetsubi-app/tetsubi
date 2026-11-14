import XCTest
@testable import JellyfinMusicCore

/// Tracks with their own picture (The Slip, Mercy Girl): shown instead of
/// the album's only when the picture really differs.
final class TrackArtTests: XCTestCase {

    private func track(_ json: String) throws -> JellyfinTrack {
        try JSONDecoder().decode(JellyfinTrack.self, from: Data(json.utf8))
    }

    func testOwnArtOnlyWhenTheBlurHashDiffers() throws {
        // As the server sends them: every track has its own tag.
        let same = try track(#"{"Id":"t1","ImageTags":{"Primary":"own"},"AlbumPrimaryImageTag":"alb","ImageBlurHashes":{"Primary":{"own":"eAKne","alb":"eAKne"}}}"#)
        let different = try track(#"{"Id":"t2","ImageTags":{"Primary":"own"},"AlbumPrimaryImageTag":"alb","ImageBlurHashes":{"Primary":{"own":"e69tM","alb":"eAKne"}}}"#)
        let noHashes = try track(#"{"Id":"t3","ImageTags":{"Primary":"own"},"AlbumPrimaryImageTag":"alb"}"#)
        let noAlbumArt = try track(#"{"Id":"t4","ImageTags":{"Primary":"own"}}"#)
        let noArt = try track(#"{"Id":"t5"}"#)
        XCTAssertFalse(same.hasOwnArt)
        XCTAssertTrue(different.hasOwnArt)
        XCTAssertFalse(noHashes.hasOwnArt, "unknown: keep the album picture")
        XCTAssertTrue(noAlbumArt.hasOwnArt)
        XCTAssertFalse(noArt.hasOwnArt)
    }

    func testQueuedTrackShowsItsOwnPictureOnlyWhenItHasOne() throws {
        let album = try JSONDecoder().decode(JellyfinAlbum.self, from: Data(#"{"Id":"alb1","Name":"The Slip"}"#.utf8))
        let own = try track(#"{"Id":"t2","ImageTags":{"Primary":"own"},"AlbumPrimaryImageTag":"alb","ImageBlurHashes":{"Primary":{"own":"x","alb":"y"}}}"#)
        let plain = try track(#"{"Id":"t1"}"#)
        XCTAssertEqual(PlaybackTrack(track: own, album: album).artItemId, "t2")
        XCTAssertEqual(PlaybackTrack(track: plain, album: album).artItemId, "alb1")
        XCTAssertEqual(PlaybackTrack(track: plain, album: album).artKey, "alb1")
    }

    func testDownloadedFilesMatchedByNumberOrByNameWhenNumbersClash() throws {
        let own = #""ImageTags":{"Primary":"o"},"AlbumPrimaryImageTag":"a","ImageBlurHashes":{"Primary":{"o":"x","a":"y"}}"#
        let tracks = try [
            track(#"{"Id":"t1","Name":"Closer"}"#),
            track(#"{"Id":"t2","Name":"I'm In Love",\#(own)}"#),
        ]
        // Numbered files (downloaded after untagged tracks were numbered).
        XCTAssertEqual(TrackCovers.match(tracks: tracks, files: ["01 - Closer.flac", "02 - Im In Love.flac"]).map(\.fileName),
                       ["02 - Im In Love.flac"])
        // Old download: every file "00 - …".
        XCTAssertEqual(TrackCovers.match(tracks: tracks, files: ["00 - Closer.flac", "00 - Im In Love.flac"]).map(\.trackID),
                       ["t2"])
        XCTAssertEqual(TrackCovers.url(albumID: "A", fileName: "00 - Im In Love.flac").lastPathComponent, "00 - Im In Love.jpg")
    }
}
