import XCTest
@testable import JellyfinMusicCore

// MARK: - AudioFormat tests

final class AudioFormatTests: XCTestCase {
    func testFileExtensions() {
        XCTAssertEqual(AudioFormat.flac.fileExtension, "flac")
        XCTAssertEqual(AudioFormat.alac.fileExtension, "m4a")
        XCTAssertEqual(AudioFormat.aac.fileExtension, "m4a")
        XCTAssertEqual(AudioFormat.opus.fileExtension, "ogg")
        XCTAssertEqual(AudioFormat.mp3.fileExtension, "mp3")
        XCTAssertEqual(AudioFormat.wav.fileExtension, "wav")
    }

    func testLosslessFormatsDoNotRequireTranscode() {
        XCTAssertFalse(AudioFormat.flac.requiresTranscode)
        XCTAssertFalse(AudioFormat.alac.requiresTranscode)
    }

    func testCompressedFormatsRequireTranscode() {
        XCTAssertTrue(AudioFormat.aac.requiresTranscode)
        XCTAssertTrue(AudioFormat.opus.requiresTranscode)
        XCTAssertTrue(AudioFormat.mp3.requiresTranscode)
        XCTAssertTrue(AudioFormat.wav.requiresTranscode)
    }

    func testDisplayNames() {
        XCTAssertEqual(AudioFormat.flac.displayName, "FLAC")
        XCTAssertEqual(AudioFormat.aac.displayName, "AAC")
    }
}

// MARK: - DateFilterHelper tests

final class DateFilterHelperTests: XCTestCase {
    func testOnThisDayReturnsNoResultsForWrongDate() {
        let cal = Calendar.current
        let today = Date()
        let comps = cal.dateComponents([.year, .month, .day], from: today)
        // A release on a *different* month/day (next month, day 1) must not match.
        let releaseMonth = ((comps.month ?? 1) % 12) + 1
        guard let release = cal.date(from: DateComponents(
                year: (comps.year ?? 2023) - 3,
                month: releaseMonth,
                day: 1
            )) else { return }
        let results = DateFilterHelper.onThisDay(from: [DatedAlbum(releaseDate: release)], today: today)
        XCTAssertTrue(results.isEmpty)
    }

    func testAnniversaryLabelOneYear() {
        XCTAssertEqual(DateFilterHelper.anniversaryLabel(yearsAgo: 1), "1 year ago")
    }

    func testAnniversaryLabelMultipleYears() {
        XCTAssertEqual(DateFilterHelper.anniversaryLabel(yearsAgo: 30), "30 years ago")
    }

    func testAnniversaryLabelZeroYears() {
        XCTAssertEqual(DateFilterHelper.anniversaryLabel(yearsAgo: 0), "")
    }

    /// Proves that an album released on today's month/day, N years ago, is
    /// returned by "On This Day". Guards against a regression where the
    /// anniversary filter stops matching valid albums.
    func testOnThisDayMatchesSameMonthDayFiveYearsAgo() {
        let today = Date()
        let cal = Calendar.current
        let comps = cal.dateComponents([.year, .month, .day], from: today)
        guard let release = cal.date(from: DateComponents(
                year: comps.year.map { $0 - 5 },
                month: comps.month,
                day: comps.day
            )) else { return }
        let results = DateFilterHelper.onThisDay(
            from: [DatedAlbum(releaseDate: release)], today: today
        )
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results.first?.yearsAgo, 5)
    }
}

// MARK: - JellyfinAlbum tests

final class JellyfinAlbumTests: XCTestCase {

    private func makeAlbum(
        id: String = "1",
        premiereDate: String? = nil, productionYear: Int? = nil,
        userData: UserData? = nil
    ) -> JellyfinAlbum {
        // Built via @testable memberwise init; every field is supplied.
        JellyfinAlbum(
            id: id,
            name: "Album",
            serverId: "srv",
            premiereDate: premiereDate,
            productionYear: productionYear,
            albumArtists: [JellyfinPerson(id: nil, name: "Artist", serverId: nil)],
            album: nil,
            imageTags: nil,
            runTimeTicks: nil,
            type: "MusicAlbum",
            userData: userData
        )
    }

    /// Regression: Jellyfin sends "1994-10-31T00:00:00.0000000Z" (fractional
    /// seconds), which ISO8601DateFormatter cannot parse. `releaseDate` must
    /// still yield Oct 31 so "On This Day" can match.
    func testReleaseDateParsesFractionalISOString() {
        let album = makeAlbum(premiereDate: "1994-10-31T00:00:00.0000000Z", productionYear: 1994)
        let md = Calendar.current.dateComponents([.month, .day], from: album.releaseDate ?? Date(timeIntervalSince1970: 0))
        XCTAssertEqual(md.month, 10)
        XCTAssertEqual(md.day, 31)
    }

    func testReleaseDateYearFallsBackToJanOne() {
        let album = makeAlbum(premiereDate: nil, productionYear: 2019)
        let comps = Calendar.current.dateComponents([.year, .month, .day], from: album.releaseDate ?? Date(timeIntervalSince1970: 0))
        XCTAssertEqual(comps.year, 2019)
        XCTAssertEqual(comps.month, 1)
        XCTAssertEqual(comps.day, 1)
    }

    /// Regression for "Recently Played": a played-to-completion track has
    /// PlayCount > 0 but nil IsPlayed/DatePlayed, so `hasBeenPlayed` must be true.
    func testHasBeenPlayedUsesPlayCountWhenDatesAreNil() {
        let played = makeAlbum(userData: UserData(playCount: 1, isPlayed: nil, datePlayed: nil))
        XCTAssertTrue(played.hasBeenPlayed)
    }

    func testNotPlayedWhenPlayCountZeroAndDatesNil() {
        let unplayed = makeAlbum(userData: UserData(playCount: 0, isPlayed: nil, datePlayed: nil))
        XCTAssertFalse(unplayed.hasBeenPlayed)
    }

    /// `JellyfinAlbum` is `Hashable` by `id` so it works as a
    /// `navigationDestination` route — tapping the same album twice must push
    /// the *same* destination, not two.
    func testEqualityIsById() {
        let a = makeAlbum(id: "abc")
        let b = makeAlbum(id: "abc")
        let c = makeAlbum(id: "xyz")
        XCTAssertEqual(a, b)
        XCTAssertNotEqual(a, c)
        let set = Set<JellyfinAlbum>([a, b, c])
        XCTAssertEqual(set.count, 2)
    }
}

// MARK: - OnThisDayEntry tests

final class OnThisDayEntryTests: XCTestCase {
    /// `albumTitle`/`albumArtist` must keep working as computed properties
    /// after the `album` field was added — they read off the underlying
    /// `JellyfinAlbum`, so the home view can navigate via the album.
    func testEntryCarriesAlbumAndExposesDisplayAccessors() {
        let album = JellyfinAlbum(
            id: "al-1",
            name: "Unplugged",
            serverId: nil,
            premiereDate: "1994-11-13T00:00:00.0000000Z",
            productionYear: 1994,
            albumArtists: [JellyfinPerson(id: nil, name: "Nirvana", serverId: nil)],
            album: "Unplugged",
            imageTags: nil,
            runTimeTicks: nil,
            type: "MusicAlbum",
            userData: nil
        )
        let entry = OnThisDayEntry(
            album: album,
            yearsAgo: 32,
            label: "32 years ago",
            releaseDate: album.releaseDate!
        )
        XCTAssertEqual(entry.album, album)
        XCTAssertEqual(entry.albumTitle, "Unplugged")
        XCTAssertEqual(entry.albumArtist, "Nirvana")
        XCTAssertEqual(entry.yearsAgo, 32)
        XCTAssertEqual(entry.label, "32 years ago")
    }
}

// MARK: - StreamURLBuilder tests

final class StreamURLBuilderTests: XCTestCase {

    /// Helper: build a builder with a known, deterministic base URL + token.
    private func makeBuilder() -> StreamURLBuilder {
        let api = JellyfinAPI(
            baseURL: URL(string: "http://jellyfin-box:8096")!,
            accessToken: "test-token"
        )
        api.setUserId("user-1")
        return StreamURLBuilder(api: api, userId: "user-1")
    }

    /// A FLAC file is streamed as it is: AVFoundation plays FLAC.
    func testFlacSourceDirectPlays() throws {
        let track = Self.makeTrack(id: "trk-2", container: "flac")
        let url = try XCTUnwrap(makeBuilder().url(for: track))
        XCTAssertTrue(url.absoluteString.contains("/Audio/trk-2/stream.flac"),
                      "Expected Stream endpoint, got \(url.absoluteString)")
        // ApiKey is added so AVURLAsset (which doesn't add headers) can authenticate.
        XCTAssertTrue(url.absoluteString.contains("ApiKey="),
                      "Expected ApiKey query, got \(url.absoluteString)")
    }

    /// `containerFor(_:)` should map format → container the way Jellyfin expects.
    func testContainerForFormats() {
        XCTAssertEqual(StreamURLBuilder.containerFor(.alac), "m4a")
        XCTAssertEqual(StreamURLBuilder.containerFor(.aac),  "m4a")
        XCTAssertEqual(StreamURLBuilder.containerFor(.flac), "flac")
        XCTAssertEqual(StreamURLBuilder.containerFor(.mp3),  "mp3")
        XCTAssertEqual(StreamURLBuilder.containerFor(.opus), "ogg")
        XCTAssertEqual(StreamURLBuilder.containerFor(.wav),  "wav")
    }

    private static func makeTrack(id: String, container: String) -> JellyfinTrack {
        JellyfinTrack(
            id: id,
            name: "Track \(id)",
            serverId: nil,
            indexNumber: 1,
            runTimeTicks: 1_000_000_000,  // 100 seconds
            album: "Test Album",
            albumId: "al-1",
            albumArtists: [JellyfinPerson(id: nil, name: "Test", serverId: nil)],
            imageTags: nil,
            mediaSource: MediaSource(
                id: nil, container: container, videoType: nil,
                mediaType: "Audio", size: nil, name: nil,
                codecs: container
            )
        )
    }
}

// MARK: - Image request tests

final class JellyfinAPIImageTests: XCTestCase {

    /// `JellyfinAPI.imageURLRequest` should set the `Authorization:
    /// MediaBrowser` header with the token, so `AuthenticatedImage`
    /// can fetch the image with proper auth.
    func testImageRequestIncludesAuthorizationHeader() {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!,
                              accessToken: "jwt-abc123")
        api.setUserId("user-1")
        let request = api.imageURLRequest(for: "al-1", size: 600)
        XCTAssertNotNil(request, "Should return a request")

        let s = request!.url!.absoluteString
        XCTAssertTrue(s.contains("maxWidth=600"),
                      "Expected size hint in \(s)")
        // Image endpoint is /Items/{id}/Images/Primary, NOT
        // /Users/{userId}/Items/{id}/Images/Primary (that 404s).
        XCTAssertTrue(s.contains("/Items/al-1/Images/Primary"),
                      "Expected Jellyfin art path in \(s)")

        let authHeader = request!.value(forHTTPHeaderField: "Authorization")
        XCTAssertNotNil(authHeader, "Authorization header should be set")
        XCTAssertTrue(authHeader!.contains("Token=\"jwt-abc123\""),
                      "Expected JWT in header, got \(authHeader!)")

        // Also check the X-Emby-Token fallback.
        let embyToken = request!.value(forHTTPHeaderField: "X-Emby-Token")
        XCTAssertEqual(embyToken, "jwt-abc123",
                       "X-Emby-Token should carry the JWT as fallback")
    }

    /// Without a token, the request should still have a path but no
    /// Token in the Authorization header.
    func testImageRequestWithoutTokenHasNoTokenInHeader() {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!)
        api.setUserId("user-2")
        let request = api.imageURLRequest(for: "al-2", size: 300)
        XCTAssertNotNil(request)
        let authHeader = request!.value(forHTTPHeaderField: "Authorization")
        XCTAssertFalse(authHeader!.contains("Token="),
                       "Should NOT have Token without an access token")
        // No X-Emby-Token either.
        XCTAssertNil(request!.value(forHTTPHeaderField: "X-Emby-Token"),
                     "Should NOT have X-Emby-Token without an access token")
    }

    /// `imageURLRequest` does not require a `userId` — the image
    /// endpoint is `/Items/{id}/Images/Primary` (no user prefix).
    func testImageRequestDoesNotRequireUserId() {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!,
                              accessToken: "jwt-abc")
        // No setUserId call.
        let request = api.imageURLRequest(for: "al-3", size: 300)
        XCTAssertNotNil(request, "imageURLRequest should work without userId")
        XCTAssertFalse(request!.url!.absoluteString.contains("/Users/"),
                       "Should NOT include a /Users/ prefix")
    }
}

// MARK: - Gapless pre-load decision

final class GaplessPreloadTests: XCTestCase {

    /// Far from the end of the track: don't pre-load yet.
    func testShouldNotPreloadFarFromEnd() {
        XCTAssertFalse(shouldPreloadNext(position: 10, duration: 60, leadTime: 1.0),
                       "1s into a 60s track is nowhere near the end")
    }

    /// Within the lead window: pre-load now.
    func testShouldPreloadWithinLeadWindow() {
        XCTAssertTrue(shouldPreloadNext(position: 59.0, duration: 60, leadTime: 1.0))
        // Strictly inside the window.
        XCTAssertTrue(shouldPreloadNext(position: 59.9, duration: 60, leadTime: 1.0))
    }

    /// The boundary `position + leadTime == duration` counts as "within".
    func testShouldPreloadExactlyAtBoundary() {
        XCTAssertFalse(shouldPreloadNext(position: 58.999, duration: 60, leadTime: 1.0))
        XCTAssertTrue(shouldPreloadNext(position: 59, duration: 60, leadTime: 1.0),
                     "59 + 1 == 60 should be within the 1s lead window")
    }

    /// Zero/negative duration (unknown length) never pre-loads — the
    /// guard must not fire on a degenerate track.
    func testShouldNotPreloadWithNonPositiveDuration() {
        XCTAssertFalse(shouldPreloadNext(position: 5, duration: 0, leadTime: 1.0))
        XCTAssertFalse(shouldPreloadNext(position: 5, duration: -1, leadTime: 1.0))
    }

    /// A larger lead widens the window; a smaller one narrows it.
    func testLeadTimeControlsWindow() {
        XCTAssertFalse(shouldPreloadNext(position: 57.5, duration: 60, leadTime: 2.0),
                       "57.5 + 2 = 59.5 is still before 60")
        XCTAssertTrue(shouldPreloadNext(position: 58, duration: 60, leadTime: 2.0),
                     "58 + 2 = 60 is within a 2s lead window")
        XCTAssertFalse(shouldPreloadNext(position: 59, duration: 60, leadTime: 0.1),
                       "59 + 0.1 = 59.1 is not within a 0.1s lead window")
    }
}

// MARK: - KeychainManager tests

final class KeychainManagerTests: XCTestCase {
    func testStoreAndLoadJWT() throws {
        let manager = KeychainManager()
        try manager.storeJWTToken("test-token-value")
        let loaded = try manager.loadJWTToken()
        XCTAssertEqual(loaded, "test-token-value")
        manager.deleteJWTToken()
    }

    func testStoreAndLoadServerURL() throws {
        let manager = KeychainManager()
        try manager.storeServerURLString("http://192.168.1.5:8096")
        let loaded = try manager.loadServerURLString()
        XCTAssertEqual(loaded, "http://192.168.1.5:8096")
        manager.clearAll()
    }
}

// MARK: - DownloadPaths tests

final class DownloadPathsTests: XCTestCase {

    private var tempRoot: URL!

    override func setUp() {
        super.setUp()
        // Isolate from the real downloads folder / app-group container.
        tempRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("DownloadPathsTests-\(UUID().uuidString)", isDirectory: true)
        DownloadPaths.rootDirOverride = tempRoot
    }

    override func tearDown() {
        DownloadPaths.rootDirOverride = nil
        _ = try? FileManager.default.removeItem(at: tempRoot)
        super.tearDown()
    }

    /// `sanitize` keeps alphanumerics, dashes, underscores, spaces, and dots;
    /// strips everything else.
    func testSanitizeKeepsAllowedChars() {
        let input = "My Album: (Deluxe) [2024] — Final"
        let out = DownloadPaths.sanitize(input)
        XCTAssertTrue(out.contains("My Album"))
        XCTAssertTrue(out.contains("Deluxe"))
        XCTAssertTrue(out.contains("2024"))
        XCTAssertFalse(out.contains(":"))
        XCTAssertFalse(out.contains("["))
        XCTAssertFalse(out.contains("]"))
        XCTAssertFalse(out.contains("—"))
    }

    /// `trackPath` builds a well-formed filename with the numeric prefix.
    func testTrackPathHasExpectedFormat() {
        let path = DownloadPaths.trackPath(
            albumID: "abc-123",
            trackNumber: 7,
            trackName: "Hello World",
            format: .flac
        )
        let lastComp = path.lastPathComponent
        XCTAssertTrue(lastComp.hasPrefix("07 - "),
                      "Expected '07 - Hello World.flac', got \(lastComp)")
        XCTAssertTrue(lastComp.hasSuffix(".flac"),
                      "Expected .flac extension, got \(lastComp)")
        XCTAssertTrue(lastComp.contains("Hello World"),
                      "Expected track name in \(lastComp)")
    }

    /// Different formats produce different extensions.
    func testTrackPathRespectsFormatExtension() {
        let flac  = DownloadPaths.trackPath(albumID: "a", trackNumber: 1, trackName: "T", format: .flac)
        let m4a   = DownloadPaths.trackPath(albumID: "a", trackNumber: 1, trackName: "T", format: .alac)
        let mp3   = DownloadPaths.trackPath(albumID: "a", trackNumber: 1, trackName: "T", format: .mp3)
        let opus  = DownloadPaths.trackPath(albumID: "a", trackNumber: 1, trackName: "T", format: .opus)
        XCTAssertEqual(flac.pathExtension, "flac")
        XCTAssertEqual(m4a.pathExtension,  "m4a")
        XCTAssertEqual(mp3.pathExtension,  "mp3")
        XCTAssertEqual(opus.pathExtension, "ogg")
    }

    /// `exists` returns false for a non-existent album directory.
    func testExistsFalseForMissingAlbum() {
        XCTAssertFalse(DownloadPaths.exists(albumID: "nonexistent-123"))
        XCTAssertEqual(DownloadPaths.totalSize(albumID: "nonexistent-123"), 0)
    }

    /// `exists` returns true when at least one file is in the directory.
    func testExistsTrueWhenFilesPresent() throws {
        let dir = DownloadPaths.albumDir("test-album")
        try FileManager.default.createDirectory(
            at: dir, withIntermediateDirectories: true
        )
        let file = dir.appendingPathComponent("01 - Track One.flac")
        FileManager.default.createFile(atPath: file.path, contents: Data("dummy".utf8))
        XCTAssertTrue(DownloadPaths.exists(albumID: "test-album"))
        XCTAssertGreaterThan(DownloadPaths.totalSize(albumID: "test-album"), 0)
    }

    /// `delete` removes the entire album directory.
    func testDeleteRemovesAllFiles() throws {
        let dir = DownloadPaths.albumDir("test-del")
        try FileManager.default.createDirectory(
            at: dir, withIntermediateDirectories: true
        )
        let f1 = dir.appendingPathComponent("01 - One.flac")
        let f2 = dir.appendingPathComponent("02 - Two.flac")
        FileManager.default.createFile(atPath: f1.path, contents: Data("data1".utf8))
        FileManager.default.createFile(atPath: f2.path, contents: Data("data2".utf8))
        XCTAssertTrue(DownloadPaths.exists(albumID: "test-del"))
        try DownloadPaths.delete(albumID: "test-del")
        XCTAssertFalse(DownloadPaths.exists(albumID: "test-del"))
    }

    /// `parseTrackNumber` extracts the leading integer from "NN - Name.ext".
    /// Covers single- and double-digit numbers, and the case where there's
    /// no leading digit (returns nil).
    func testParseTrackNumber() {
        XCTAssertEqual(DownloadPaths.parseTrackNumber(from: "01 - Track.flac"), 1)
        XCTAssertEqual(DownloadPaths.parseTrackNumber(from: "12 - Another.m4a"), 12)
        // Track number 10 is a common edge — must give 10, not 1 or 0.
        XCTAssertEqual(DownloadPaths.parseTrackNumber(from: "10 - Song.wav"), 10)
        // No leading digit → nil.
        XCTAssertNil(DownloadPaths.parseTrackNumber(from: "NoNumber.flac"))
    }

    /// `localTracks` returns sorted entries when files are present.
    func testLocalTracksReturnsSortedEntries() throws {
        let dir = DownloadPaths.albumDir("sorted-album")
        try FileManager.default.createDirectory(
            at: dir, withIntermediateDirectories: true
        )
        let fm = FileManager.default
        // Create 3 track files out of order.
        fm.createFile(atPath: dir.appendingPathComponent("03 - C.flac").path, contents: Data("c".utf8))
        fm.createFile(atPath: dir.appendingPathComponent("01 - A.flac").path, contents: Data("a".utf8))
        fm.createFile(atPath: dir.appendingPathComponent("02 - B.flac").path, contents: Data("b".utf8))
        let tracks = DownloadPaths.localTracks(albumID: "sorted-album", format: .flac)
        XCTAssertEqual(tracks.count, 3)
        XCTAssertEqual(tracks.map(\.trackNumber), [1, 2, 3])
    }
}

// MARK: - DownloadAlbumState tests

final class DownloadAlbumStateTests: XCTestCase {

    /// `fractionCompleted` is 0 when nothing has completed.
    func testFractionCompletedZero() {
        let s = DownloadAlbumState(
            albumID: "a", title: "T", artistName: "A",
            format: .flac, expectedTrackCount: 10
        )
        XCTAssertEqual(s.fractionCompleted, 0)
    }

    /// `fractionCompleted` reflects completed tracks over expected.
    func testFractionCompletedTracks() {
        var s = DownloadAlbumState(
            albumID: "a", title: "T", artistName: "A",
            format: .flac, expectedTrackCount: 10
        )
        s.completedTracks = 3
        XCTAssertEqual(s.fractionCompleted, 0.3, accuracy: 0.001)
    }

    /// `fractionCompleted` is clamped to 1.0 when over-completed.
    func testFractionCompletedClamped() {
        var s = DownloadAlbumState(
            albumID: "a", title: "T", artistName: "A",
            format: .flac, expectedTrackCount: 5
        )
        s.completedTracks = 10
        XCTAssertEqual(s.fractionCompleted, 1.0)
    }

    /// `fractionCompleted` is 0 when expectedTrackCount is 0.
    func testFractionCompletedZeroTracks() {
        var s = DownloadAlbumState(
            albumID: "a", title: "T", artistName: "A",
            format: .flac, expectedTrackCount: 0
        )
        s.completedTracks = 5
        XCTAssertEqual(s.fractionCompleted, 0)
    }
}

// MARK: - LibraryViewModelTests

final class LibraryViewModelTests: XCTestCase {

    private func makeAlbum(
        name: String,
        artist: String,
        year: Int? = nil,
        id: String? = nil,
        date: String? = nil
    ) -> JellyfinAlbum {
        // `name` can't appear in a default-parameter value, so derive the
        // unique id inside the body when the caller didn't supply one.
        JellyfinAlbum(
            id: id ?? "id-\(name)",
            name: name,
            serverId: "srv",
            premiereDate: date,
            productionYear: year,
            albumArtists: [JellyfinPerson(id: nil, name: artist, serverId: nil)],
            album: nil,
            imageTags: nil,
            runTimeTicks: nil,
            type: "MusicAlbum",
            userData: nil
        )
    }

    func testSortByDisplayNameAscending() {
        let albums = [
            makeAlbum(name: "Zebra",  artist: "X"),
            makeAlbum(name: "Apple",  artist: "Y"),
            makeAlbum(name: "Mango",  artist: "Z"),
        ]
        let sorted = LibraryViewModel.sort(albums, by: .name)
        XCTAssertEqual(sorted.map(\.displayName), ["Apple", "Mango", "Zebra"])
    }

    func testSortByArtistNameAscending() {
        let albums = [
            makeAlbum(name: "A", artist: "Zebra"),
            makeAlbum(name: "B", artist: "Alpha"),
            makeAlbum(name: "C", artist: "Delta"),
        ]
        let sorted = LibraryViewModel.sort(albums, by: .artist)
        XCTAssertEqual(sorted.map(\.artistName), ["Alpha", "Delta", "Zebra"])
    }

    func testSortByYearDescending() {
        let albums = [
            makeAlbum(name: "Old",  artist: "X", year: 2000),
            makeAlbum(name: "New",  artist: "Y", year: 2025),
            makeAlbum(name: "Mid",  artist: "Z", year: 2015),
        ]
        let sorted = LibraryViewModel.sort(albums, by: .year)
        XCTAssertEqual(sorted.map(\.productionYear), [2025, 2015, 2000])
    }

    /// Year sorts by the full release date, else Jan 1 of the year; ties
    /// keep the incoming (date added) order.
    func testSortByYearUsesFullReleaseDateThenJan1() {
        let albums = [
            makeAlbum(name: "Spring", artist: "X", year: 2026, date: "2026-03-01T00:00:00.0000000Z"),
            makeAlbum(name: "YearOnly", artist: "X", year: 2026),
            makeAlbum(name: "Autumn", artist: "X", year: 2026, date: "2026-10-16T00:00:00.0000000Z"),
            makeAlbum(name: "Late2025", artist: "X", year: 2025, date: "2025-12-31T00:00:00.0000000Z"),
            makeAlbum(name: "SameDayAddedLater", artist: "X", year: 2026, date: "2026-03-01T00:00:00.0000000Z"),
        ]
        let sorted = LibraryViewModel.sort(albums, by: .year)
        XCTAssertEqual(sorted.map(\.displayName), ["Autumn", "Spring", "SameDayAddedLater", "YearOnly", "Late2025"])
    }

    /// A nil year should sort to the end (treated as 0).
    func testSortByYearNilGoesLast() {
        let albums = [
            makeAlbum(name: "NoYear", artist: "X", year: nil),
            makeAlbum(name: "Year1",  artist: "Y", year: 2020),
        ]
        let sorted = LibraryViewModel.sort(albums, by: .year)
        XCTAssertEqual(sorted.first?.productionYear, 2020)
        XCTAssertNil(sorted.last?.productionYear)
    }

    /// `.dateAdded` preserves input order (the server already returns
    /// items in that order, the client just passes through).
    func testSortByDateAddedPreservesOrder() {
        let albums = [
            makeAlbum(name: "First",  artist: "X"),
            makeAlbum(name: "Second", artist: "Y"),
            makeAlbum(name: "Third",  artist: "Z"),
        ]
        let sorted = LibraryViewModel.sort(albums, by: .dateAdded)
        XCTAssertEqual(sorted.map(\.displayName), ["First", "Second", "Third"])
    }

    func testFilterAllReturnsEveryAlbum() {
        // Filter `.all` returns everything unchanged — test the logic
        // directly without an `api` instance.
        let albums = [
            makeAlbum(name: "A", artist: "X"),
            makeAlbum(name: "B", artist: "Y"),
        ]
        // Simulate the .all filter path: no filtering, just sort.
        let filtered = LibraryViewModel.sort(albums, by: .name)
        XCTAssertEqual(filtered.count, 2)
    }
}

// MARK: - Quick Connect tests

final class QuickConnectTests: XCTestCase {

    private func makePoll(_ json: String) -> QuickConnectPoll {
        try! JSONDecoder().decode(QuickConnectPoll.self, from: Data(json.utf8))
    }

    func testStatusApproved() {
        XCTAssertEqual(quickConnectStatus(from: makePoll("{\"Authenticated\":true}")), .approved)
    }

    func testStatusPending() {
        XCTAssertEqual(quickConnectStatus(from: makePoll("{\"Authenticated\":false}")), .pending)
        XCTAssertEqual(quickConnectStatus(from: makePoll("{}")), .pending)
    }

    func testStartResponseDecodes() throws {
        let json = "{\"Secret\":\"abc\",\"Code\":\"123456\",\"Authenticated\":false}"
        let start = try JSONDecoder().decode(QuickConnectStart.self, from: Data(json.utf8))
        XCTAssertEqual(start.code, "123456")
        XCTAssertEqual(start.secret, "abc")
    }

    /// `POST /QuickConnect/Initiate` has no body and carries no user token.
    func testStartRequestShape() throws {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "jwt-abc")
        let request = try XCTUnwrap(api.startQuickConnectRequest())
        XCTAssertTrue(request.url!.absoluteString.hasSuffix("/QuickConnect/Initiate"))
        XCTAssertEqual(request.httpMethod, "POST")
        let auth = request.value(forHTTPHeaderField: "Authorization") ?? ""
        XCTAssertTrue(auth.contains("MediaBrowser"))
        XCTAssertFalse(auth.contains("Token="), "Initiate must not carry a user token: \(auth)")
    }

    /// `GET /QuickConnect/Connect?secret=...` carries no user token.
    func testPollRequestShape() throws {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "jwt-abc")
        let request = try XCTUnwrap(api.quickConnectPollRequest(forSecret: "abc"))
        XCTAssertTrue(request.url!.absoluteString.contains("/QuickConnect/Connect"))
        XCTAssertTrue(request.url!.absoluteString.contains("secret=abc"))
        let auth = request.value(forHTTPHeaderField: "Authorization") ?? ""
        XCTAssertFalse(auth.contains("Token="))
    }
}


final class AlbumDecodingTests: XCTestCase {
    /// Audio items standing in for albums resolve to their parent album.
    func testAudioItemResolvesToParentAlbum() throws {
        let json = "{\"Id\":\"trk\",\"Name\":\"Break down!!\",\"Type\":\"Audio\",\"Album\":\"#1stDEMO\",\"AlbumId\":\"alb\"}"
        let a = try JSONDecoder().decode(JellyfinAlbum.self, from: Data(json.utf8))
        XCTAssertEqual(a.id, "alb")
        XCTAssertEqual(a.displayName, "#1stDEMO")
    }

    func testMusicAlbumKeepsItsOwnId() throws {
        let json = "{\"Id\":\"alb\",\"Name\":\"#1stDEMO\",\"Type\":\"MusicAlbum\"}"
        let a = try JSONDecoder().decode(JellyfinAlbum.self, from: Data(json.utf8))
        XCTAssertEqual(a.id, "alb")
    }
}

final class DownloadFormatTests: XCTestCase {
    private func track(container: String, codec: String?) -> JellyfinTrack {
        JellyfinTrack(id: "t", name: nil, serverId: nil, indexNumber: 1, runTimeTicks: nil,
                      album: nil, albumId: nil, albumArtists: nil, imageTags: nil,
                      mediaSource: MediaSource(id: nil, container: container, videoType: nil,
                                               mediaType: nil, size: nil, name: nil, codecs: codec))
    }

    func testOriginalWhenNoPreference() {
        XCTAssertEqual(AudioFormat.downloadFormat(for: track(container: "mp3", codec: "mp3"), preferred: nil), .mp3)
        XCTAssertEqual(AudioFormat.downloadFormat(for: track(container: "flac", codec: "flac"), preferred: nil), .flac)
        XCTAssertEqual(AudioFormat.downloadFormat(for: track(container: "m4a", codec: "alac"), preferred: nil), .alac)
    }

    /// A lossy source is never transcoded up to a higher-quality format.
    func testLossySourceIsNotUpgraded() {
        XCTAssertEqual(AudioFormat.downloadFormat(for: track(container: "mp3", codec: "mp3"), preferred: .flac), .mp3)
        XCTAssertEqual(AudioFormat.downloadFormat(for: track(container: "mp3", codec: "mp3"), preferred: .aac), .mp3)
    }

    func testLosslessSourceHonoursPreference() {
        XCTAssertEqual(AudioFormat.downloadFormat(for: track(container: "flac", codec: "flac"), preferred: .mp3), .mp3)
        XCTAssertEqual(AudioFormat.downloadFormat(for: track(container: "flac", codec: "flac"), preferred: .flac), .flac)
    }

    func testMediaSourceCodecComesFromAudioStream() throws {
        let json = """
        {"Id":"t","MediaSources":[{"Container":"m4a","MediaStreams":[{"Type":"Audio","Codec":"alac"},{"Type":"EmbeddedImage","Codec":"mjpeg"}]}]}
        """
        let t = try JSONDecoder().decode(JellyfinTrack.self, from: Data(json.utf8))
        XCTAssertEqual(t.mediaSource?.codecs, "alac")
        XCTAssertEqual(AudioFormat.source(of: t), .alac)
    }
}

// MARK: - AlphaIndex

final class AlphaIndexTests: XCTestCase {
    func testBuckets() {
        XCTAssertEqual(AlphaIndex.bucket(for: "Abbey Road"), "A")
        XCTAssertEqual(AlphaIndex.bucket(for: "émile"), "E")
        XCTAssertEqual(AlphaIndex.bucket(for: "13 Songs"), "#")
        XCTAssertEqual(AlphaIndex.bucket(for: "...out flew reason"), "#")
        XCTAssertEqual(AlphaIndex.bucket(for: ""), "#")
        XCTAssertEqual(AlphaIndex.bucket(for: "ヘイトスピーチ"), AlphaIndex.other)
        XCTAssertEqual(AlphaIndex.bucket(for: "Привет"), AlphaIndex.other)
    }

    func testLabelOrder() {
        XCTAssertEqual(AlphaIndex.labels.first, "#")
        XCTAssertEqual(AlphaIndex.labels.last, AlphaIndex.other)
        XCTAssertEqual(AlphaIndex.labels.count, 28)
    }
}

final class TrackTitleTests: XCTestCase {
    func testStripsNumberAndArtist() {
        let r = TrackTitle.clean("01 - BrokenMan - Follow", artists: ["BrokenMan"])
        XCTAssertEqual(r.title, "Follow")
        XCTAssertEqual(r.number, 1)
    }

    func testKeepsOtherArtistAndPlainTitles() {
        XCTAssertEqual(TrackTitle.clean("02 - Someone Else - Song", artists: ["BrokenMan"]).title, "Someone Else - Song")
        XCTAssertEqual(TrackTitle.clean("1979", artists: []).title, "1979")
        XCTAssertEqual(TrackTitle.clean("99 Problems", artists: []).title, "99 Problems")
        XCTAssertEqual(TrackTitle.clean("Follow", artists: ["BrokenMan"]).title, "Follow")
    }

    func testNeverEmpty() {
        XCTAssertEqual(TrackTitle.clean("BrokenMan - ", artists: ["BrokenMan"]).title.isEmpty, false)
    }
}

// MARK: - What AVPlayer streams: original or converted (HLS)

final class StreamPlanTests: XCTestCase {

    private func builder() -> StreamURLBuilder {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")
        api.setUserId("user-1")
        return StreamURLBuilder(api: api, userId: "user-1")
    }

    private func track(_ container: String?, _ codec: String?) -> JellyfinTrack {
        JellyfinTrack(id: "t1", name: nil, serverId: nil, indexNumber: 1, runTimeTicks: nil,
                      album: nil, albumId: nil, albumArtists: nil, imageTags: nil,
                      mediaSource: MediaSource(id: nil, container: container, videoType: nil,
                                               mediaType: nil, size: nil, name: nil, codecs: codec))
    }

    private let album = JellyfinAlbum(id: "al", name: "A", serverId: nil, premiereDate: nil, productionYear: nil,
                                      albumArtists: nil, album: nil, imageTags: nil, runTimeTicks: nil,
                                      type: "MusicAlbum", userData: nil)

    private func query(_ url: URL) -> [String: String] {
        Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems ?? []).map { ($0.name, $0.value ?? "") })
    }

    func testDecisionTable() {
        typealias P = StreamURLBuilder.StreamPlan
        let cases: [(String?, String?, P)] = [
            ("mp3", "mp3", .original(fileExtension: "mp3")),
            ("flac", "flac", .original(fileExtension: "flac")),
            ("m4a", "aac", .original(fileExtension: "m4a")),
            ("m4a", "alac", .original(fileExtension: "m4a")),
            ("mp4", "aac", .original(fileExtension: "mp4")),
            ("m4b", "aac", .original(fileExtension: "m4b")),
            ("mov,mp4,m4a,3gp,3g2,mj2", "aac", .original(fileExtension: "mp4")),
            ("aac", "aac", .original(fileExtension: "aac")),
            ("wav", "pcm_s16le", .original(fileExtension: "wav")),
            ("aiff", "pcm_s16be", .original(fileExtension: "aiff")),
            ("MP3", "MP3", .original(fileExtension: "mp3")),
            ("flac", nil, .original(fileExtension: "flac")),
            // Not AVPlayer's: converted, lossy to AAC, lossless or unknown to ALAC.
            ("ogg", "opus", .converted(.aac)),
            ("opus", "opus", .converted(.aac)),
            ("ogg", "vorbis", .converted(.aac)),
            ("webm", "opus", .converted(.aac)),
            ("asf", "wmav2", .converted(.aac)),
            ("asf", "wmalossless", .converted(.alac)),
            ("ape", "ape", .converted(.alac)),
            ("wv", "wavpack", .converted(.alac)),
            ("dsf", "dsd_lsbf_planar", .converted(.alac)),
            ("ogg", "flac", .converted(.alac)),
            ("wav", "adpcm_ms", .converted(.alac)),
            ("xyz", "mystery", .converted(.alac)),
            (nil, nil, .converted(.alac)),
            // A saved queue with only the codec.
            (nil, "mp3", .original(fileExtension: "mp3")),
            (nil, "opus", .converted(.aac)),
        ]
        for (container, codec, expected) in cases {
            XCTAssertEqual(StreamURLBuilder.plan(codec: codec, container: container), expected,
                           "\(container ?? "nil") / \(codec ?? "nil")")
        }
    }

    /// The all-MP3 library that failed: each track is the original file,
    /// with its own extension, never `/universal`.
    func testMP3PlaysOriginalWithItsOwnExtension() throws {
        let url = try XCTUnwrap(builder().url(for: track("mp3", "mp3")))
        XCTAssertEqual(url.path, "/Audio/t1/stream.mp3")
        XCTAssertEqual(query(url)["static"], "true")
        XCTAssertEqual(query(url)["ApiKey"], "test-token")
    }

    func testPlayableFormatsNeverUseUniversal() throws {
        for (container, codec, ext) in [("mp3", "mp3", "mp3"), ("flac", "flac", "flac"), ("m4a", "aac", "m4a"),
                                         ("m4a", "alac", "m4a"), ("wav", "pcm_s24le", "wav"),
                                         ("aiff", "pcm_s16be", "aiff")] {
            let url = try XCTUnwrap(builder().url(for: track(container, codec)))
            XCTAssertEqual(url.path, "/Audio/t1/stream.\(ext)", "\(container)/\(codec)")
        }
    }

    /// A lossy source AVPlayer can't play: AAC 320 kbps as HLS (never plain HTTP).
    func testOpusIsConvertedToAACOverHLS() throws {
        let url = try XCTUnwrap(builder().url(for: track("ogg", "opus")))
        XCTAssertEqual(url.path, "/Audio/t1/universal")
        let q = query(url)
        XCTAssertEqual(q["TranscodingProtocol"], "hls")
        XCTAssertEqual(q["TranscodingContainer"], "mp4")
        XCTAssertEqual(q["AudioCodec"], "aac")
        // The source's own container, restricted to the target codec: one reason to convert.
        XCTAssertEqual(q["Container"], "ogg|aac")
        XCTAssertEqual(q["AudioBitRate"], "320000")
        XCTAssertNil(q["MaxStreamingBitrate"])
        XCTAssertEqual(q["UserId"], "user-1")
        XCTAssertNotNil(q["DeviceId"])
        XCTAssertEqual(q["ApiKey"], "test-token")
    }

    /// A lossless (or unknown) source AVPlayer can't play: ALAC as HLS, no bitrate.
    func testLosslessUnplayableIsConvertedToALACOverHLS() throws {
        for (container, codec) in [("ape", "ape"), ("wv", "wavpack"), ("xyz", nil)] {
            let url = try XCTUnwrap(builder().url(for: track(container, codec)))
            let q = query(url)
            XCTAssertEqual(url.path, "/Audio/t1/universal")
            XCTAssertEqual(q["TranscodingProtocol"], "hls")
            XCTAssertEqual(q["AudioCodec"], "alac")
            XCTAssertEqual(q["Container"], "\(container)|alac")
            XCTAssertNil(q["AudioBitRate"])
            XCTAssertNil(q["MaxStreamingBitrate"])
        }
    }

    /// The converted stream is still recognised as converted (start watchdog, error text).
    func testConvertedStreamIsWatched() throws {
        let converted = try XCTUnwrap(builder().url(for: track("ogg", "vorbis")))
        XCTAssertEqual(PlayerManager.firstResponseLimit(for: converted), PlayerManager.firstResponseSeconds)
        let original = try XCTUnwrap(builder().url(for: track("mp3", "mp3")))
        XCTAssertNil(PlayerManager.firstResponseLimit(for: original))
    }

    /// Mobile data with the cap: AAC HLS at the cap. A non-AAC source asks
    /// for its own container restricted to AAC (one reason to convert:
    /// several give a variant playlist AVPlayer gets HTTP 400 for).
    func testMobileCapIsAACOverHLS() throws {
        for (container, codec) in [("mp3", "mp3"), ("flac", "flac"), ("ogg", "opus"), ("m4a", "alac")] {
            let url = try XCTUnwrap(builder().url(for: track(container, codec), maxBitrate: 128_000))
            let q = query(url)
            XCTAssertEqual(url.path, "/Audio/t1/universal")
            XCTAssertEqual(q["Container"], "\(container)|aac")
            XCTAssertEqual(q["TranscodingContainer"], "mp4")
            XCTAssertEqual(q["TranscodingProtocol"], "hls")
            XCTAssertEqual(q["AudioCodec"], "aac")
            XCTAssertNil(q["MaxStreamingBitrate"])
            XCTAssertEqual(q["AudioBitRate"], "128000")
        }
    }

    /// An AAC source is converted only when over the cap (the server's
    /// single bitrate reason); under it the server sends the file.
    func testMobileCapOnAACSourceUsesTheBitrateLimit() throws {
        let url = try XCTUnwrap(builder().url(for: track("mov,mp4,m4a", "aac"), maxBitrate: 256_000))
        let q = query(url)
        XCTAssertEqual(q["Container"], "mov,mp4,m4a")
        XCTAssertEqual(q["TranscodingProtocol"], "hls")
        XCTAssertEqual(q["MaxStreamingBitrate"], "256000")
        XCTAssertEqual(q["AudioBitRate"], "256000")
    }

    func testHLSContainerFromFFprobeList() {
        XCTAssertEqual(StreamURLBuilder.hlsContainer(source: "mov,mp4,m4a", codec: "alac"), "mov|alac,mp4|alac,m4a|alac")
        XCTAssertEqual(StreamURLBuilder.hlsContainer(source: nil, codec: "aac"), "m4a|aac")
    }

    /// A restored queue keeps the container and codec, and streams the same way.
    func testRestoredQueueTrackUsesItsCodec() throws {
        let saved = QueueTrack(track: track("m4a", "AAC"), album: album)
        let data = try JSONEncoder().encode(saved)
        let restored = try JSONDecoder().decode(QueueTrack.self, from: data)
        XCTAssertEqual(restored.codec, "aac")
        let url = try XCTUnwrap(builder().url(trackId: restored.id, container: restored.container,
                                               codec: restored.codec))
        XCTAssertEqual(url.path, "/Audio/t1/stream.m4a")
        let opus = QueueTrack(track: track("ogg", "opus"), album: album)
        let converted = try XCTUnwrap(builder().url(trackId: opus.id, container: opus.container, codec: opus.codec))
        XCTAssertEqual(query(converted)["TranscodingProtocol"], "hls")
    }

    /// Now Playing names what is actually streamed.
    func testQualityLabelFollowsTheStream() {
        var pt = PlaybackTrack(track: track("ogg", "opus"), album: album)
        pt.bitDepth = 16
        pt.describeStream(codec: "opus", container: "ogg", mobileCap: nil)
        XCTAssertEqual(pt.qualityLabel, "AAC 320 kbps")
        XCTAssertNil(pt.bitDepth)

        var ape = PlaybackTrack(track: track("ape", "ape"), album: album)
        ape.describeStream(codec: "ape", container: "ape", mobileCap: nil)
        XCTAssertEqual(ape.qualityLabel, "ALAC")

        var mp3 = PlaybackTrack(track: track("mp3", "mp3"), album: album)
        let before = mp3.qualityLabel
        mp3.describeStream(codec: "mp3", container: "mp3", mobileCap: nil)
        XCTAssertEqual(mp3.qualityLabel, before)
        XCTAssertEqual(before, "MP3")

        mp3.describeStream(codec: "mp3", container: "mp3", mobileCap: 128_000)
        XCTAssertEqual(mp3.qualityLabel, "AAC 128 kbps")
    }

    // MARK: Downloads

    func testOriginalDownloadsArePlayableFiles() throws {
        let b = builder()
        for (container, codec, ext) in [("mp3", "mp3", "mp3"), ("flac", "flac", "flac"), ("m4a", "aac", "m4a"),
                                         ("m4a", "alac", "m4a"), ("mp4", "aac", "mp4"), ("wav", "pcm_s16le", "wav")] {
            let t = track(container, codec)
            let format = AudioFormat.downloadFormat(for: t, preferred: nil)
            let url = try XCTUnwrap(b.downloadURL(for: t, format: format))
            XCTAssertEqual(url.path, "/Audio/t1/stream.\(ext)", "\(container)/\(codec)")
        }
    }

    /// Opus (Ogg) isn't a file AVPlayer plays, so "Original" downloads FLAC,
    /// over plain HTTP (URLSession takes that).
    func testUnplayableOriginalDownloadsAsFLAC() throws {
        let t = track("ogg", "opus")
        XCTAssertEqual(AudioFormat.downloadFormat(for: t, preferred: nil), .flac)
        XCTAssertEqual(AudioFormat.downloadFormat(for: t, preferred: .opus), .flac)
        XCTAssertEqual(AudioFormat.downloadFormat(for: track("flac", "flac"), preferred: .opus), .flac)
        let url = try XCTUnwrap(builder().downloadURL(for: t, format: .flac))
        XCTAssertEqual(url.path, "/Audio/t1/universal")
        XCTAssertEqual(query(url)["Container"], "flac")
        XCTAssertEqual(query(url)["TranscodingProtocol"], "http")
    }

    /// AIFF counts as WAV; saved as .wav it is converted, not copied.
    func testAIFFDownloadIsConvertedToWAV() throws {
        let t = track("aiff", "pcm_s16be")
        XCTAssertEqual(AudioFormat.downloadFormat(for: t, preferred: nil), .wav)
        let url = try XCTUnwrap(builder().downloadURL(for: t, format: .wav))
        XCTAssertEqual(url.path, "/Audio/t1/universal")
        XCTAssertEqual(query(url)["Container"], "wav")
    }

    func testPreferredFormatDownloadConverts() throws {
        let t = track("flac", "flac")
        let url = try XCTUnwrap(builder().downloadURL(for: t, format: .mp3))
        XCTAssertEqual(url.path, "/Audio/t1/universal")
        XCTAssertEqual(query(url)["AudioCodec"], "mp3")
    }
}
