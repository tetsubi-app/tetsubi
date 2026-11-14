import XCTest
import CoreAudioTypes
@testable import JellyfinMusicCore

/// Format and bitrate on Now Playing ("ALAC 905 kbps").
final class QualityLabelTests: XCTestCase {

    private func track(_ json: String) throws -> JellyfinTrack {
        try JSONDecoder().decode(JellyfinTrack.self, from: Data(json.utf8))
    }

    func testBitrateFromMediaSource() throws {
        let t = try track(#"{"Id":"t","MediaSources":[{"Container":"m4a","Bitrate":905123,"MediaStreams":[{"Type":"Audio","Codec":"alac","BitRate":900000}]}]}"#)
        XCTAssertEqual(t.mediaSource?.bitrate, 905123)
        XCTAssertEqual(PlaybackTrack(track: t, album: Self.album).qualityLabel, "ALAC 905 kbps")
    }

    func testBitrateFallsBackToTheAudioStream() throws {
        let t = try track(#"{"Id":"t","MediaSources":[{"Container":"flac","MediaStreams":[{"Type":"Audio","Codec":"flac","BitRate":1411200}]}]}"#)
        XCTAssertEqual(PlaybackTrack(track: t, album: Self.album).qualityLabel, "FLAC 1411 kbps")
    }

    func testUnknownBitrateOrFormat() throws {
        XCTAssertEqual(PlaybackTrack.qualityLabel(format: .mp3, bitrate: nil), "MP3")
        XCTAssertNil(PlaybackTrack.qualityLabel(format: nil, bitrate: 320_000))
        XCTAssertNil(PlaybackTrack(track: try track(#"{"Id":"t"}"#), album: Self.album).qualityLabel)
    }

    /// Downloaded files say what they are through their codec ID.
    func testFormatFromCodecID() {
        func fourCC(_ s: String) -> UInt32 { s.utf8.reduce(0) { $0 << 8 | UInt32($1) } }
        XCTAssertEqual(AudioFormat.fromCodecID(fourCC("alac")), .alac)
        XCTAssertEqual(AudioFormat.fromCodecID(fourCC("flac")), .flac)
        XCTAssertEqual(AudioFormat.fromCodecID(fourCC("aac ")), .aac)
        XCTAssertEqual(AudioFormat.fromCodecID(fourCC(".mp3")), .mp3)
        XCTAssertEqual(AudioFormat.fromCodecID(fourCC("opus")), .opus)
        XCTAssertEqual(AudioFormat.fromCodecID(fourCC("lpcm")), .wav)
        XCTAssertNil(AudioFormat.fromCodecID(fourCC("zzzz")))
        XCTAssertEqual(AudioFormat.fromCodecID(kAudioFormatAppleLossless), .alac)
        XCTAssertEqual(AudioFormat.fromCodecID(kAudioFormatMPEG4AAC), .aac)
    }

    // MARK: Sample rate and bit depth ("44.1kHz/16-bit")

    func testSampleRateAndBitDepthFromTheAudioStream() throws {
        let t = try track(#"{"Id":"t","MediaSources":[{"Container":"flac","MediaStreams":[{"Type":"EmbeddedImage","Codec":"mjpeg","BitDepth":8},{"Type":"Audio","Codec":"flac","SampleRate":96000,"BitDepth":24}]}]}"#)
        XCTAssertEqual(t.mediaSource?.sampleRate, 96000)
        XCTAssertEqual(t.mediaSource?.bitDepth, 24)
        let pt = PlaybackTrack(track: t, album: Self.album)
        XCTAssertEqual(pt.sampleRate, 96000)
        XCTAssertEqual(pt.bitDepth, 24)
        XCTAssertEqual(pt.sampleRateLabel, "96kHz/24-bit")
    }

    func testLossyStreamHasNoBitDepth() throws {
        let t = try track(#"{"Id":"t","MediaSources":[{"Container":"mp3","MediaStreams":[{"Type":"Audio","Codec":"mp3","SampleRate":44100}]}]}"#)
        XCTAssertNil(t.mediaSource?.bitDepth)
        XCTAssertEqual(PlaybackTrack(track: t, album: Self.album).sampleRateLabel, "44.1kHz")
    }

    func testNoSampleRateMeansNoLabel() throws {
        let t = try track(#"{"Id":"t","MediaSources":[{"Container":"flac","MediaStreams":[{"Type":"Audio","Codec":"flac","BitDepth":16}]}]}"#)
        XCTAssertNil(t.mediaSource?.sampleRate)
        let pt = PlaybackTrack(track: t, album: Self.album)
        XCTAssertNil(pt.sampleRateLabel)
        XCTAssertEqual(pt.qualityLabel, "FLAC")
        // No media source at all (downloaded tracks listed offline).
        XCTAssertNil(PlaybackTrack(track: try track(#"{"Id":"t"}"#), album: Self.album).sampleRateLabel)
        // A zero from the server counts as unknown.
        let zero = try track(#"{"Id":"t","MediaSources":[{"MediaStreams":[{"Type":"Audio","SampleRate":0,"BitDepth":0}]}]}"#)
        XCTAssertNil(zero.mediaSource?.sampleRate)
        XCTAssertNil(zero.mediaSource?.bitDepth)
    }

    func testSampleRateLabelFormatting() {
        func label(_ rate: Int?, _ depth: Int? = nil) -> String? {
            PlaybackTrack.sampleRateLabel(sampleRate: rate, bitDepth: depth)
        }
        XCTAssertEqual(label(44100, 16), "44.1kHz/16-bit")
        XCTAssertEqual(label(48000, 24), "48kHz/24-bit")
        XCTAssertEqual(label(88200, 24), "88.2kHz/24-bit")
        XCTAssertEqual(label(96000, 24), "96kHz/24-bit")
        XCTAssertEqual(label(192000, 24), "192kHz/24-bit")
        XCTAssertEqual(label(22050), "22.05kHz")
        XCTAssertEqual(label(44100), "44.1kHz")
        XCTAssertEqual(label(48000, 0), "48kHz")
        XCTAssertNil(label(nil, 16))
        XCTAssertNil(label(0, 16))
    }

    /// Downloaded files: the bit depth from the file's stream description.
    func testBitDepthFromStreamDescription() {
        XCTAssertEqual(AudioFormat.bitDepth(codecID: kAudioFormatAppleLossless, formatFlags: 1, bitsPerChannel: 0), 16)
        XCTAssertEqual(AudioFormat.bitDepth(codecID: kAudioFormatAppleLossless, formatFlags: 3, bitsPerChannel: 0), 24)
        XCTAssertEqual(AudioFormat.bitDepth(codecID: kAudioFormatFLAC, formatFlags: 2, bitsPerChannel: 0), 20)
        XCTAssertEqual(AudioFormat.bitDepth(codecID: kAudioFormatLinearPCM, formatFlags: 0, bitsPerChannel: 16), 16)
        XCTAssertNil(AudioFormat.bitDepth(codecID: kAudioFormatMPEG4AAC, formatFlags: 0, bitsPerChannel: 0))
        XCTAssertNil(AudioFormat.bitDepth(codecID: kAudioFormatAppleLossless, formatFlags: 0, bitsPerChannel: 0))
    }

    private static let album = JellyfinAlbum(
        id: "a", name: "Album", serverId: nil, premiereDate: nil, productionYear: nil,
        albumArtists: nil, album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil)
}
