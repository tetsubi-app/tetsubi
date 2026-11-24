import XCTest
@testable import JellyfinMusicCore

final class PlaybackSettingsTests: XCTestCase {

    func testVolumeFromGain() {
        XCTAssertEqual(PlaybackSettings.volume(forGain: nil), 1)
        XCTAssertEqual(PlaybackSettings.volume(forGain: 0), 1, accuracy: 0.0001)
        XCTAssertEqual(PlaybackSettings.volume(forGain: -6), 0.501, accuracy: 0.001)
        XCTAssertEqual(PlaybackSettings.volume(forGain: -20), 0.1, accuracy: 0.0001)
        XCTAssertEqual(PlaybackSettings.volume(forGain: 8.3), 1, "can't boost past full volume")
        XCTAssertEqual(PlaybackSettings.volume(forGain: .nan), 1)
    }

    func testDefaultsAndPersistence() {
        let suite = "PlaybackSettingsTests-\(UUID().uuidString)"
        PlaybackSettings.defaults = UserDefaults(suiteName: suite)!
        defer {
            UserDefaults().removePersistentDomain(forName: suite)
            PlaybackSettings.defaults = .standard
        }
        XCTAssertEqual(PlaybackSettings.cellularQuality, .kbps256)
        XCTAssertFalse(PlaybackSettings.normalizeVolume)
        XCTAssertFalse(PlaybackSettings.downloadOverCellular, "mobile data is off until switched on")
        PlaybackSettings.cellularQuality = .kbps128
        PlaybackSettings.downloadOverCellular = true
        XCTAssertEqual(PlaybackSettings.cellularQuality, .kbps128)
        XCTAssertTrue(PlaybackSettings.downloadOverCellular)
        PlaybackSettings.downloadOverCellular = false
        XCTAssertFalse(PlaybackSettings.downloadOverCellular)
        XCTAssertNil(StreamingQuality.original.bitrate)
        // "Streaming over mobile data" drives the cap; Original (as served) has none.
        XCTAssertEqual(PlaybackSettings.mobileDataBitrate, 128_000)
        PlaybackSettings.cellularQuality = .original
        XCTAssertNil(PlaybackSettings.mobileDataBitrate)
    }

    /// The old switch, switched off, carries over as Original (as served);
    /// choosing a quality afterwards takes over from it.
    func testOldSwitchOffReadsAsOriginal() {
        let suite = "PlaybackSettingsTests-\(UUID().uuidString)"
        PlaybackSettings.defaults = UserDefaults(suiteName: suite)!
        defer {
            UserDefaults().removePersistentDomain(forName: suite)
            PlaybackSettings.defaults = .standard
        }
        PlaybackSettings.defaults.set(false, forKey: "playback.reduceOnMobileData")
        XCTAssertEqual(PlaybackSettings.cellularQuality, .original)
        PlaybackSettings.cellularQuality = .kbps320
        XCTAssertEqual(PlaybackSettings.cellularQuality, .kbps320)
        XCTAssertNil(PlaybackSettings.defaults.object(forKey: "playback.reduceOnMobileData"))
    }
}

final class CappedStreamURLTests: XCTestCase {

    func testCellularCapForcesAACAtBitrate() throws {
        let api = JellyfinAPI(baseURL: URL(string: "http://jellyfin-box:8096")!, accessToken: "test-token")
        api.setUserId("user-1")
        let builder = StreamURLBuilder(api: api, userId: "user-1")
        let track = JellyfinTrack(id: "t1", name: nil, serverId: nil, indexNumber: 1, runTimeTicks: nil,
                                  album: nil, albumId: nil, albumArtists: nil, imageTags: nil,
                                  mediaSource: MediaSource(id: nil, container: "m4a", videoType: nil,
                                                           mediaType: nil, size: nil, name: nil, codecs: "alac"))
        // Without a cap an m4a source direct-plays…
        XCTAssertTrue(try XCTUnwrap(builder.url(for: track)).path.hasSuffix("/stream.m4a"))
        // …with one it's transcoded to AAC at that bitrate.
        let url = try XCTUnwrap(builder.url(for: track, maxBitrate: 128_000))
        XCTAssertTrue(url.path.hasSuffix("/Audio/t1/universal"))
        let q = Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems ?? []).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(q["AudioCodec"], "aac")
        // The source's container restricted to AAC: the server's one reason is the codec.
        XCTAssertEqual(q["Container"], "m4a|aac")
        // HLS, not plain HTTP: AVPlayer refuses a chunked transcode with no byte ranges.
        XCTAssertEqual(q["TranscodingProtocol"], "hls")
        XCTAssertEqual(q["TranscodingContainer"], "mp4")
        XCTAssertNil(q["MaxStreamingBitrate"])
        XCTAssertEqual(q["AudioBitRate"], "128000")
        XCTAssertEqual(q["ApiKey"], "test-token")
        XCTAssertEqual(q["UserId"], "user-1")
    }

    func testDecodesNormalizationGain() throws {
        let json = #"{"Id":"t","Name":"Song","NormalizationGain":-9.5}"#.data(using: .utf8)!
        XCTAssertEqual(try JSONDecoder().decode(JellyfinTrack.self, from: json).normalizationGain, -9.5)
    }
}

final class SearchSupportTests: XCTestCase {

    private var suite: String!

    override func setUp() {
        suite = "SearchSupportTests-\(UUID().uuidString)"
        SearchHistory.defaults = UserDefaults(suiteName: suite)!
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suite)
        SearchHistory.defaults = .standard
    }

    func testHistoryKeepsRefinedTermOnly() {
        SearchHistory.record("me")
        SearchHistory.record("mell")
        SearchHistory.record("mellon collie")
        XCTAssertEqual(SearchHistory.terms, ["mellon collie"])
        SearchHistory.record("Gish")
        SearchHistory.record("MELLON COLLIE")
        XCTAssertEqual(SearchHistory.terms, ["MELLON COLLIE", "Gish"])
    }

    func testHistoryIgnoresShortTermsAndCaps() {
        SearchHistory.record("a")
        XCTAssertEqual(SearchHistory.terms, [])
        for i in 0..<15 { SearchHistory.record("album \(i) x\(i)") }
        XCTAssertEqual(SearchHistory.terms.count, SearchHistory.limit)
        SearchHistory.clear()
        XCTAssertEqual(SearchHistory.terms, [])
    }

    func testLocalSearchMatchesAllWordsIgnoringAccents() {
        let albums = [
            LocalAlbum(id: "1", title: "Mellon Collie", artist: "Smashing Pumpkins"),
            LocalAlbum(id: "2", title: "Café Tacvba", artist: "Café Tacvba"),
            LocalAlbum(id: "3", title: "Gish", artist: "Smashing Pumpkins"),
        ]
        XCTAssertEqual(LocalSearch.matches("pumpkins", in: albums).map(\.id), ["1", "3"])
        XCTAssertEqual(LocalSearch.matches("collie smashing", in: albums).map(\.id), ["1"])
        XCTAssertEqual(LocalSearch.matches("cafe", in: albums).map(\.id), ["2"])
        XCTAssertEqual(LocalSearch.matches("   ", in: albums), [])
    }
}
