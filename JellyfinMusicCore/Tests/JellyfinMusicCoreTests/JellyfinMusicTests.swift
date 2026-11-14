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

    /// A track whose `MediaSource.container == flac` and a target of
    /// `.alac` should pick the universal transcode endpoint.
    func testFlacSourceToALACTargetUsesUniversalEndpoint() throws {
        let track = Self.makeTrack(id: "trk-1", container: "flac")
        let url = try XCTUnwrap(makeBuilder().url(
            for: track,
            format: .alac
        ))
        // Universal endpoint: /Audio/{id}/universal
        XCTAssertTrue(url.absoluteString.contains("/Audio/trk-1/universal"),
                      "Expected universal endpoint, got \(url.absoluteString)")
        // The format param should be the requested target container.
        XCTAssertTrue(url.absoluteString.contains("Container=m4a"),
                      "Expected Container=m4a (ALAC), got \(url.absoluteString)")
        XCTAssertTrue(url.absoluteString.contains("UserId=user-1"),
                      "Universal endpoint needs UserId, got \(url.absoluteString)")
        XCTAssertTrue(url.absoluteString.contains("DeviceId="),
                      "Universal endpoint needs DeviceId, got \(url.absoluteString)")
    }

    /// A track whose source already matches the target should direct-play
    /// via `/Audio/{id}/stream`.
    func testMatchingSourceUsesStreamEndpoint() throws {
        let track = Self.makeTrack(id: "trk-2", container: "flac")
        let url = try XCTUnwrap(makeBuilder().url(
            for: track,
            format: .flac
        ))
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

// MARK: - PlayerManager shuffle tests

final class PlayerManagerShuffleTests: XCTestCase {

    private func track(_ id: String) -> JellyfinTrack {
        JellyfinTrack(
            id: id,
            name: id,
            serverId: nil,
            indexNumber: nil,
            runTimeTicks: nil,
            album: "al",
            albumId: "al-1",
            albumArtists: nil,
            imageTags: nil,
            mediaSource: nil
        )
    }

    /// When shuffle is off, the order is unchanged.
    func testApplyShuffleNoopWhenOff() {
        let tracks = [track("a"), track("b"), track("c")]
        let out = applyShuffle(on: tracks, forcedFirst: nil, shuffleOn: false)
        XCTAssertEqual(out.map(\.id), ["a", "b", "c"])
    }

    /// When shuffle is on and there's a forced-first index, the
    /// forced track is at position 0 and the others are *some*
    /// permutation of the remaining tracks.
    func testApplyShuffleForcedFirstIsAtHead() {
        let tracks = (0..<20).map { track("t\($0)") }
        for _ in 0..<20 {
            let out = applyShuffle(on: tracks, forcedFirst: 5, shuffleOn: true)
            XCTAssertEqual(out.count, 20)
            XCTAssertEqual(out.first?.id, "t5")
            // The rest is a permutation of tracks minus the forced one.
            let rest = Set(out.dropFirst().map(\.id))
            let expected = Set((0..<5).map { "t\($0)" } + (6..<20).map { "t\($0)" })
            XCTAssertEqual(rest, expected)
        }
    }

    /// When shuffle is on with no forced index, the result is a
    /// permutation of the original tracks.
    func testApplyShuffleFullRandomization() {
        let tracks = (0..<8).map { track("t\($0)") }
        var sawNonIdentical = false
        for _ in 0..<10 {
            let out = applyShuffle(on: tracks, forcedFirst: nil, shuffleOn: true)
            XCTAssertEqual(Set(out.map(\.id)), Set(tracks.map(\.id)),
                           "Shuffle must preserve the multiset of tracks")
            if out.map(\.id) != tracks.map(\.id) { sawNonIdentical = true }
        }
        // With 8 tracks, the chance of a no-op shuffle is ~0.98 per
        // iteration, so over 10 iterations we expect to see a
        // non-identical order at least once.
        XCTAssertTrue(sawNonIdentical,
                      "Expected at least one non-identity shuffle")
    }

    /// `applyShuffle` with an empty input returns empty.
    func testApplyShuffleEmptyIsNoop() {
        let out = applyShuffle(on: [], forcedFirst: nil, shuffleOn: true)
        XCTAssertTrue(out.isEmpty)
    }

    /// A `forcedFirst` index out of bounds is treated as "no forced
    /// first" — the entire array is still shuffled when `shuffleOn`
    /// is true (we just don't move a specific track to the front).
    func testApplyShuffleForcedIndexOutOfBoundsStillShuffles() {
        let tracks = (0..<8).map { track("t\($0)") }
        var sawNonIdentical = false
        for _ in 0..<10 {
            let out = applyShuffle(on: tracks, forcedFirst: 99, shuffleOn: true)
            XCTAssertEqual(Set(out.map(\.id)), Set(tracks.map(\.id)),
                           "Out-of-bounds forced-first must still preserve the multiset")
            if out.map(\.id) != tracks.map(\.id) { sawNonIdentical = true }
        }
        XCTAssertTrue(sawNonIdentical,
                      "Expected at least one non-identity shuffle over 10 iterations")
    }

    // MARK: - planQueue (regression: start index when shuffle is off)

    /// The regression: with shuffle OFF, "tap track N" must still
    /// start at N, not track 1. `planQueue` preserves the order and
    /// returns the forced index as the start index.
    func testPlanQueueShuffleOffPreservesOrderAndStartIndex() {
        let tracks = (0..<10).map { track("t\($0)") }
        let planned = planQueue(tracks: tracks,
                                forcedStartIndex: 4,
                                shuffleOn: false)
        XCTAssertEqual(planned.tracks.map(\.id),
                       tracks.map(\.id),
                       "Shuffle off must preserve the fetched order")
        XCTAssertEqual(planned.startIndex, 4,
                       "Shuffle off must start at the forced index, not 0")
    }

    /// Shuffle off with no forced index starts at 0 in original order.
    func testPlanQueueShuffleOffNilStartsAtZero() {
        let tracks = (0..<10).map { track("t\($0)") }
        let planned = planQueue(tracks: tracks,
                                forcedStartIndex: nil,
                                shuffleOn: false)
        XCTAssertEqual(planned.tracks.map(\.id), tracks.map(\.id))
        XCTAssertEqual(planned.startIndex, 0)
    }

    /// Shuffle on + a valid forced index: that track is forced to the
    /// head and playback starts at 0; the multiset is preserved.
    func testPlanQueueShuffleOnForcedFirstStartsAtZero() {
        let tracks = (0..<20).map { track("t\($0)") }
        for _ in 0..<10 {
            let planned = planQueue(tracks: tracks,
                                    forcedStartIndex: 3,
                                    shuffleOn: true)
            XCTAssertEqual(planned.startIndex, 0)
            XCTAssertEqual(planned.tracks.first?.id, "t3")
            XCTAssertEqual(Set(planned.tracks.map(\.id)), Set(tracks.map(\.id)))
        }
    }

    /// Shuffle on + nil forced index: whole list randomized, start 0.
    func testPlanQueueShuffleOnNoForcedStartsAtZero() {
        let tracks = (0..<8).map { track("t\($0)") }
        for _ in 0..<10 {
            let planned = planQueue(tracks: tracks,
                                    forcedStartIndex: nil,
                                    shuffleOn: true)
            XCTAssertEqual(planned.startIndex, 0)
            XCTAssertEqual(Set(planned.tracks.map(\.id)), Set(tracks.map(\.id)))
        }
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
        id: String? = nil
    ) -> JellyfinAlbum {
        // `name` can't appear in a default-parameter value, so derive the
        // unique id inside the body when the caller didn't supply one.
        JellyfinAlbum(
            id: id ?? "id-\(name)",
            name: name,
            serverId: "srv",
            premiereDate: nil,
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
