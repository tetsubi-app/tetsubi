import XCTest
import AVFoundation
@testable import JellyfinMusicCore

final class AudioEffectsTests: XCTestCase {

    private var suite: String!

    override func setUp() {
        suite = "AudioEffectsTests-\(UUID().uuidString)"
        PlaybackSettings.defaults = UserDefaults(suiteName: suite)!
    }

    override func tearDown() {
        AudioEffects.shared.setEqualizer(enabled: false, gains: Array(repeating: 0, count: 10))
        AudioEffects.shared.setVisualizerActive(false)
        UserDefaults().removePersistentDomain(forName: suite)
        PlaybackSettings.defaults = .standard
    }

    // MARK: Filter maths

    func testPeakingFilterHitsTargetGainAtCentre() {
        let f = Biquad.peaking(frequency: 1000, gainDB: 6, q: 1.41, sampleRate: 44_100)
        let atCentre = 20 * log10(f.magnitude(at: 1000, sampleRate: 44_100))
        XCTAssertEqual(atCentre, 6, accuracy: 0.05)
        let farAway = 20 * log10(f.magnitude(at: 10_000, sampleRate: 44_100))
        XCTAssertEqual(farAway, 0, accuracy: 0.5)
    }

    func testZeroGainAndOutOfRangeBandsPassThrough() {
        XCTAssertEqual(Biquad.peaking(frequency: 1000, gainDB: 0, q: 1.41, sampleRate: 44_100), .passThrough)
        XCTAssertEqual(Biquad.peaking(frequency: 16_000, gainDB: 6, q: 1.41, sampleRate: 22_050), .passThrough)
    }

    func testRockFollowsFlatAndBoostsBassAndTrebleOverMids() throws {
        XCTAssertEqual(Array(EqualizerPreset.allCases.prefix(2)), [.flat, .rock])
        XCTAssertEqual(EqualizerPreset.rock.displayName, "Rock")
        let g = try XCTUnwrap(EqualizerPreset.rock.gains)
        let mids = g[4...5].max()!               // 500 Hz, 1 kHz
        XCTAssertGreaterThan(g[0], mids)         // 31 Hz
        XCTAssertGreaterThan(g[9], mids)         // 16 kHz
        XCTAssertLessThan(mids, 0)
    }

    func testPresetsHaveTenBands() {
        for preset in EqualizerPreset.allCases where preset != .custom {
            XCTAssertEqual(preset.gains?.count, 10, preset.rawValue)
        }
    }

    // MARK: The real tap processing path

    private let sampleRate = 44_100.0

    private func format() -> AudioStreamBasicDescription {
        AudioStreamBasicDescription(
            mSampleRate: sampleRate, mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsNonInterleaved | kAudioFormatFlagIsPacked,
            mBytesPerPacket: 4, mFramesPerPacket: 1, mBytesPerFrame: 4,
            mChannelsPerFrame: 2, mBitsPerChannel: 32, mReserved: 0)
    }

    /// Runs `frames` of a sine at `hz` (amplitude 0.5, both channels) through
    /// a tap context; returns the RMS of the last half of the output.
    private func run(_ context: TapContext, hz: Double, frames: Int = 8192) -> Float {
        let channels = 2
        let list = AudioBufferList.allocate(maximumBuffers: channels)
        defer { for b in list { b.mData?.deallocate() }; free(list.unsafeMutablePointer) }
        for ch in 0..<channels {
            let data = UnsafeMutablePointer<Float>.allocate(capacity: frames)
            for n in 0..<frames { data[n] = Float(0.5 * sin(2 * .pi * hz * Double(n) / sampleRate)) }
            list[ch] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(frames * 4), mData: data)
        }
        context.process(list, frames: frames)
        let out = list[0].mData!.assumingMemoryBound(to: Float.self)
        var sum: Float = 0
        for n in frames / 2..<frames { sum += out[n] * out[n] }
        return (sum / Float(frames / 2)).squareRoot()
    }

    func testEqualizerBoostsTheChosenBandRelativeToOthers() {
        var gains = [Float](repeating: 0, count: 10)
        gains[5] = 12  // +12 dB at 1 kHz
        AudioEffects.shared.setEqualizer(enabled: true, gains: gains)

        let context = TapContext(effects: .shared)
        context.prepare(maxFrames: 8192, format: format())
        let at1k = run(context, hz: 1000)
        let context2 = TapContext(effects: .shared)
        context2.prepare(maxFrames: 8192, format: format())
        let at100 = run(context2, hz: 100)

        let inputRMS = Float(0.5 / 2.0.squareRoot())
        // Pre-gain of -12 dB cancels the boost at 1 kHz and cuts elsewhere.
        XCTAssertEqual(20 * log10(at1k / inputRMS), 0, accuracy: 0.5)
        XCTAssertEqual(20 * log10(at100 / inputRMS), -12, accuracy: 0.7)
    }

    func testEqualizerOffLeavesAudioUntouched() {
        AudioEffects.shared.setEqualizer(enabled: false, gains: [12, 12, 12, 12, 12, 12, 12, 12, 12, 12])
        let context = TapContext(effects: .shared)
        context.prepare(maxFrames: 8192, format: format())
        XCTAssertEqual(run(context, hz: 440), Float(0.5 / 2.0.squareRoot()), accuracy: 0.001)
    }

    func testVisualizerPeaksInTheRightBar() throws {
        AudioEffects.shared.setVisualizerActive(true)
        let context = TapContext(effects: .shared)
        context.prepare(maxFrames: 8192, format: format())
        _ = run(context, hz: 1000)

        let bars = AudioEffects.shared.spectrum()
        let peak = try XCTUnwrap(bars.indices.max { bars[$0] < bars[$1] })
        let lo = SpectrumBars.edge(peak, count: bars.count)
        let hi = SpectrumBars.edge(peak + 1, count: bars.count)
        XCTAssertTrue((lo...hi).contains(1000), "peak bar \(peak) covers \(lo)–\(hi) Hz")
        XCTAssertGreaterThan(bars[peak], 0.7)
        XCTAssertLessThan(bars[2], 0.3, "low bars stay quiet for a 1 kHz tone")
    }
}
