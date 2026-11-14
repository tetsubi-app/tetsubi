// MARK: - AudioEffects — equaliser + visualiser spectrum via an audio tap

import AVFoundation
import MediaToolbox
import Accelerate
import os

// MARK: - Equaliser model

/// Built-in equaliser curves (gains in dB for `AudioEffects.frequencies`).
/// Listed in the order shown in the preset picker.
public enum EqualizerPreset: String, CaseIterable, Sendable {
    case flat, rock, bassBoost, trebleBoost, vocal, loudness, custom

    public var displayName: String {
        switch self {
        case .flat:        "Flat"
        case .rock:        "Rock"
        case .bassBoost:   "Bass Boost"
        case .trebleBoost: "Treble Boost"
        case .vocal:       "Vocal"
        case .loudness:    "Loudness"
        case .custom:      "Custom"
        }
    }

    /// `nil` for `.custom` (the user's own sliders).
    public var gains: [Float]? {
        switch self {
        case .flat:        [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
        // Rock: a "smiley" curve. Bass and treble up, low mids scooped out
        // so guitars and drums punch through. Averages Winamp's built-in Rock
        // (60 Hz +4.6, 600 Hz −5.4, 12–16 kHz +6.5 dB) and the commonly quoted
        // iTunes Rock (+5 at 32 Hz, −1.5 at 1 kHz, +4.5 at 16 kHz).
        case .rock:        [5, 4, 3, 0, -2, -2, 0, 3, 4, 5]
        case .bassBoost:   [6, 5, 4, 2, 0, 0, 0, 0, 0, 0]
        case .trebleBoost: [0, 0, 0, 0, 0, 1, 2, 4, 5, 6]
        case .vocal:       [-2, -2, -1, 1, 3, 4, 3, 1, 0, -1]
        case .loudness:    [5, 4, 2, 0, -1, -1, 0, 2, 4, 5]
        case .custom:      nil
        }
    }
}

/// One second-order (biquad) filter: RBJ "peaking EQ" coefficients,
/// normalised so a0 = 1. Pure maths — tested in `AudioEffectsTests`.
struct Biquad: Equatable {
    var b0: Float = 1, b1: Float = 0, b2: Float = 0, a1: Float = 0, a2: Float = 0

    static let passThrough = Biquad()

    /// Boost/cut of `gainDB` centred on `frequency` (bandwidth set by `q`).
    static func peaking(frequency: Float, gainDB: Float, q: Float, sampleRate: Float) -> Biquad {
        guard gainDB != 0, frequency < sampleRate * 0.45 else { return .passThrough }
        let a = powf(10, gainDB / 40)
        let w0 = 2 * Float.pi * frequency / sampleRate
        let alpha = sinf(w0) / (2 * q)
        let cosw = cosf(w0)
        let a0 = 1 + alpha / a
        return Biquad(b0: (1 + alpha * a) / a0, b1: (-2 * cosw) / a0, b2: (1 - alpha * a) / a0,
                      a1: (-2 * cosw) / a0, a2: (1 - alpha / a) / a0)
    }

    /// Gain (linear) of this filter at `frequency`.
    func magnitude(at frequency: Float, sampleRate: Float) -> Float {
        let w = 2 * Double.pi * Double(frequency) / Double(sampleRate)
        // H(e^jw) = (b0 + b1 e^-jw + b2 e^-2jw) / (1 + a1 e^-jw + a2 e^-2jw)
        func eval(_ c0: Double, _ c1: Double, _ c2: Double) -> (Double, Double) {
            (c0 + c1 * cos(w) + c2 * cos(2 * w), -(c1 * sin(w) + c2 * sin(2 * w)))
        }
        let (nr, ni) = eval(Double(b0), Double(b1), Double(b2))
        let (dr, di) = eval(1, Double(a1), Double(a2))
        return Float(sqrt((nr * nr + ni * ni) / (dr * dr + di * di)))
    }
}

// MARK: - AudioEffects

/// Owns the equaliser settings and the latest visualiser spectrum, and
/// attaches an `MTAudioProcessingTap` to the player's tracks — but only
/// while the equaliser is on or a visualiser is showing.
///
/// **Gapless:** `AVQueuePlayer` stops joining tracks seamlessly once an item
/// has a processing tap — measured 262 ms of silence between tracks, even
/// with a tap that does nothing (pre-effects taps: 483 ms). With no tap it's
/// 0 ms. So with both effects off, no item gets a tap.
///
/// The tap works on progressive HTTP streams and local files (what this
/// app plays). Audio sent over AirPlay bypasses taps, so the EQ and
/// visualiser don't apply there.
public final class AudioEffects: @unchecked Sendable {

    public static let shared = AudioEffects()

    /// Centre frequencies (Hz) of the 10 equaliser bands.
    public static let frequencies: [Float] = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
    public static let gainRange: ClosedRange<Float> = -12...12
    /// Number of visualiser bars.
    public static let barCount = 32
    static let bandQ: Float = 1.41   // ≈ one octave per band

    struct Shared {
        var eqEnabled = false
        var gains = [Float](repeating: 0, count: 10)
        /// Bumped on every EQ change so taps rebuild their filters.
        var version = 0
        var visualizerActive = false
        var spectrum = [Float](repeating: 0, count: AudioEffects.barCount)
    }
    let state = OSAllocatedUnfairLock(initialState: Shared())

    private init() {
        let enabled = PlaybackSettings.equalizerEnabled
        let gains = PlaybackSettings.equalizerGains
        state.withLock { $0.eqEnabled = enabled; $0.gains = gains; $0.version += 1 }
    }

    // MARK: Settings (main thread)

    public var equalizerEnabled: Bool { state.withLock { $0.eqEnabled } }
    public var equalizerGains: [Float] { state.withLock { $0.gains } }

    /// Applies (and saves) the equaliser. Takes effect within one audio buffer.
    public func setEqualizer(enabled: Bool, gains: [Float]) {
        let clamped = gains.prefix(10).map { min(max($0, Self.gainRange.lowerBound), Self.gainRange.upperBound) }
        state.withLock { $0.eqEnabled = enabled; $0.gains = clamped; $0.version += 1 }
        PlaybackSettings.equalizerEnabled = enabled
        PlaybackSettings.equalizerGains = clamped
        Task { @MainActor in self.refreshAttachments() }
    }

    /// Turn spectrum analysis on while a visualiser is on screen.
    public func setVisualizerActive(_ active: Bool) {
        state.withLock {
            $0.visualizerActive = active
            if !active { $0.spectrum = [Float](repeating: 0, count: Self.barCount) }
        }
        Task { @MainActor in self.refreshAttachments() }
    }

    /// True while something needs the tap (equaliser on or visualiser showing).
    var needsTap: Bool { state.withLock { $0.eqEnabled || $0.visualizerActive } }

    /// Latest bar levels, 0…1, lowest frequency first.
    public func spectrum() -> [Float] {
        state.withLock { $0.spectrum }
    }

    // MARK: Attaching to player items

    private final class WeakItem { weak var item: AVPlayerItem?; init(_ i: AVPlayerItem) { item = i } }
    /// Items the player has queued; taps are added or removed as needed.
    @MainActor private var tracked: [WeakItem] = []

    /// Registers a queued item. It gets the tap now if an effect is on,
    /// or later when one is switched on.
    @MainActor
    func attach(to item: AVPlayerItem) {
        tracked.removeAll { $0.item == nil }
        tracked.append(WeakItem(item))
        if needsTap { addTap(to: item) }
    }

    /// Adds or removes taps on every queued item to match the settings.
    /// Removing them restores gapless playback.
    @MainActor
    private func refreshAttachments() {
        tracked.removeAll { $0.item == nil }
        let wanted = needsTap
        for item in tracked.compactMap(\.item) {
            if wanted, item.audioMix == nil {
                addTap(to: item)
            } else if !wanted, item.audioMix != nil {
                item.audioMix = nil
            }
        }
    }

    /// Adds the tap once the item's audio track has loaded.
    @MainActor
    private func addTap(to item: AVPlayerItem) {
        Task { @MainActor in
            guard let track = try? await item.asset.loadTracks(withMediaType: .audio).first,
                  self.needsTap, item.audioMix == nil,
                  let tap = Self.makeTap() else { return }
            let params = AVMutableAudioMixInputParameters(track: track)
            params.audioTapProcessor = tap
            let mix = AVMutableAudioMix()
            mix.inputParameters = [params]
            item.audioMix = mix
        }
    }

    private static func makeTap() -> MTAudioProcessingTap? {
        let context = Unmanaged.passRetained(TapContext(effects: .shared))
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: context.toOpaque(),
            init: { _, clientInfo, storageOut in
                storageOut.pointee = clientInfo
            },
            finalize: { tap in
                Unmanaged<TapContext>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release()
            },
            prepare: { tap, maxFrames, format in
                Unmanaged<TapContext>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
                    .prepare(maxFrames: Int(maxFrames), format: format.pointee)
            },
            unprepare: { tap in
                Unmanaged<TapContext>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
                    .unprepare()
            },
            process: { tap, frames, _, bufferList, framesOut, flagsOut in
                guard MTAudioProcessingTapGetSourceAudio(tap, frames, bufferList, flagsOut, nil, framesOut) == noErr
                else { return }
                Unmanaged<TapContext>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
                    .process(UnsafeMutableAudioBufferListPointer(bufferList), frames: Int(framesOut.pointee))
            }
        )
        var tap: MTAudioProcessingTap?
        let status = MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks,
                                                kMTAudioProcessingTapCreationFlag_PostEffects, &tap)
        if status != noErr {
            // No tap, so `finalize` never runs: release the context here.
            context.release()
            print("[AudioEffects] MTAudioProcessingTapCreate failed: \(status)")
            return nil
        }
        return tap
    }
}

// MARK: - Per-item DSP state (runs on the audio thread)

/// Filters, buffers and FFT for one player item. Everything the audio
/// thread touches is allocated in `prepare`; `process` never allocates or
/// blocks (it skips the shared state if the lock is busy).
final class TapContext {
    private let effects: AudioEffects
    private var sampleRate: Float = 44_100
    /// Only 32-bit float, non-interleaved audio is processed; anything else passes through.
    private var supported = false

    // Equaliser
    private var version = -1
    private var enabled = false
    private var preGain: Float = 1
    private var filters: [Biquad] = []
    /// Per channel, per band: (z1, z2) filter memory.
    private var memory: [[SIMD2<Float>]] = []

    // Spectrum
    static let fftSize = 1024
    private var fft: vDSP_DFT_Setup?
    private var window = [Float](repeating: 0, count: fftSize)
    private var ring = [Float](repeating: 0, count: fftSize)
    private var ringFill = 0
    private var real = [Float](repeating: 0, count: fftSize / 2)
    private var imag = [Float](repeating: 0, count: fftSize / 2)
    private var outReal = [Float](repeating: 0, count: fftSize / 2)
    private var outImag = [Float](repeating: 0, count: fftSize / 2)
    private var magnitudes = [Float](repeating: 0, count: fftSize / 2)
    private var bars = [Float](repeating: 0, count: AudioEffects.barCount)

    init(effects: AudioEffects) {
        self.effects = effects
    }

    func prepare(maxFrames: Int, format: AudioStreamBasicDescription) {
        sampleRate = Float(format.mSampleRate)
        let isFloat = format.mFormatFlags & kAudioFormatFlagIsFloat != 0
        let nonInterleaved = format.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        supported = isFloat && nonInterleaved && format.mBitsPerChannel == 32
        let channels = Int(format.mChannelsPerFrame)
        memory = Array(repeating: Array(repeating: .zero, count: AudioEffects.frequencies.count), count: channels)
        filters = Array(repeating: .passThrough, count: AudioEffects.frequencies.count)
        version = -1
        if let fft { vDSP_DFT_DestroySetup(fft) }  // prepare can run twice
        fft = vDSP_DFT_zrop_CreateSetup(nil, vDSP_Length(Self.fftSize), .FORWARD)
        vDSP_hann_window(&window, vDSP_Length(Self.fftSize), Int32(vDSP_HANN_NORM))
        ringFill = 0
    }

    func unprepare() {
        if let fft { vDSP_DFT_DestroySetup(fft) }
        fft = nil
    }

    deinit {
        // A tap can be finalised without `unprepare`.
        if let fft { vDSP_DFT_DestroySetup(fft) }
    }

    func process(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
        guard supported, frames > 0 else { return }

        // Pick up EQ changes without blocking the audio thread (skip this
        // buffer's update if the main thread holds the lock).
        let known = version
        let snapshot = effects.state.withLockIfAvailable { shared in
            (visualize: shared.visualizerActive,
             eq: shared.version != known ? (shared.version, shared.eqEnabled, shared.gains) : nil)
        }
        let visualize = snapshot?.visualize ?? false
        if let eq = snapshot?.eq {
            version = eq.0
            enabled = eq.1
            for (i, f) in AudioEffects.frequencies.enumerated() where i < filters.count {
                filters[i] = Biquad.peaking(frequency: f, gainDB: eq.2[i], q: AudioEffects.bandQ,
                                            sampleRate: sampleRate)
            }
            // Lower the level by the biggest boost so peaks don't clip.
            preGain = powf(10, -max(0, eq.2.max() ?? 0) / 20)
        }

        if enabled {
            for (ch, buffer) in buffers.enumerated() where ch < memory.count {
                guard let data = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
                for (band, f) in filters.enumerated() where f != .passThrough {
                    var z = memory[ch][band]
                    for n in 0..<frames {
                        let x = data[n]
                        let y = f.b0 * x + z.x
                        z.x = f.b1 * x - f.a1 * y + z.y
                        z.y = f.b2 * x - f.a2 * y
                        data[n] = y
                    }
                    memory[ch][band] = z
                }
                if preGain != 1 {
                    var g = preGain
                    vDSP_vsmul(data, 1, &g, data, 1, vDSP_Length(frames))
                }
            }
        }

        if visualize, let first = buffers.first?.mData?.assumingMemoryBound(to: Float.self) {
            analyse(first, frames: frames)
        }
    }

    /// Collects samples (first channel) and, every `fftSize` samples,
    /// turns them into bar levels for the visualiser.
    private func analyse(_ samples: UnsafePointer<Float>, frames: Int) {
        guard let fft else { return }
        var offset = 0
        while offset < frames {
            let n = min(Self.fftSize - ringFill, frames - offset)
            ring.withUnsafeMutableBufferPointer { dst in
                (dst.baseAddress! + ringFill).update(from: samples + offset, count: n)
            }
            ringFill += n
            offset += n
            guard ringFill == Self.fftSize else { continue }
            ringFill = 0

            vDSP_vmul(ring, 1, window, 1, &ring, 1, vDSP_Length(Self.fftSize))
            // Split even/odd samples into the real/imaginary halves zrop expects.
            ring.withUnsafeBufferPointer { src in
                src.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: Self.fftSize / 2) { complex in
                    real.withUnsafeMutableBufferPointer { r in
                        imag.withUnsafeMutableBufferPointer { i in
                            var split = DSPSplitComplex(realp: r.baseAddress!, imagp: i.baseAddress!)
                            vDSP_ctoz(complex, 2, &split, 1, vDSP_Length(Self.fftSize / 2))
                        }
                    }
                }
            }
            vDSP_DFT_Execute(fft, real, imag, &outReal, &outImag)
            outReal.withUnsafeMutableBufferPointer { r in
                outImag.withUnsafeMutableBufferPointer { i in
                    var split = DSPSplitComplex(realp: r.baseAddress!, imagp: i.baseAddress!)
                    vDSP_zvabs(&split, 1, &magnitudes, 1, vDSP_Length(Self.fftSize / 2))
                }
            }
            SpectrumBars.levels(from: magnitudes, sampleRate: sampleRate, fftSize: Self.fftSize, into: &bars)
            let latest = bars
            effects.state.withLockIfAvailable { $0.spectrum = latest }
        }
    }
}

// MARK: - Spectrum → bars

enum SpectrumBars {
    static let lowHz: Float = 40
    static let highHz: Float = 16_000
    /// Bars below this level read as silence.
    static let floorDB: Float = -60

    /// Upper edge (Hz) of bar `i` of `count`, log-spaced between 40 Hz and 16 kHz.
    static func edge(_ i: Int, count: Int) -> Float {
        lowHz * powf(highHz / lowHz, Float(i) / Float(count))
    }

    /// FFT magnitudes → `out.count` bar levels (0…1), averaging the bins in
    /// each log-spaced band. Writes into `out` (no allocation).
    static func levels(from magnitudes: [Float], sampleRate: Float, fftSize: Int, into out: inout [Float]) {
        let count = out.count
        let binHz = sampleRate / Float(fftSize)
        for i in 0..<count {
            let lo = max(1, Int(edge(i, count: count) / binHz))
            let hi = min(magnitudes.count - 1, max(lo, Int(edge(i + 1, count: count) / binHz)))
            var sum: Float = 0
            for bin in lo...hi { sum += magnitudes[bin] }
            let mean = sum / Float(hi - lo + 1)
            // Normalise: a full-scale sine gives magnitude ≈ fftSize / 4 with a Hann window.
            let db = 20 * log10f(max(mean / (Float(fftSize) / 4), 1e-9))
            out[i] = min(1, max(0, (db - floorDB) / -floorDB))
        }
    }
}
