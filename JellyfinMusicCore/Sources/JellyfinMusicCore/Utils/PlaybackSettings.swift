// MARK: - PlaybackSettings — user preferences for streaming and downloads

import Foundation

/// Bitrate cap for streaming over cellular. `original` streams the file as-is.
public enum StreamingQuality: String, CaseIterable, Sendable {
    case original
    case kbps320
    case kbps256
    case kbps128

    /// Bits per second, or `nil` for no cap.
    public var bitrate: Int? {
        switch self {
        case .original: nil
        case .kbps320:  320_000
        case .kbps256:  256_000
        case .kbps128:  128_000
        }
    }

    public var displayName: String {
        switch self {
        case .original: "Original (as served)"
        case .kbps320:  "High (320 kbps AAC)"
        case .kbps256:  "Normal (256 kbps AAC)"
        case .kbps128:  "Data saver (128 kbps AAC)"
        }
    }
}

/// UserDefaults-backed settings, read where they're applied.
public enum PlaybackSettings {

    /// Used by tests so they never touch the app's real defaults.
    nonisolated(unsafe) static var defaults: UserDefaults = .standard

    private static let cellularQualityKey = "playback.cellularQuality"
    private static let mobileStreamingKey = "playback.reduceOnMobileData"
    private static let normalizeKey = "playback.normalizeVolume"
    private static let cellularDownloadsKey = "downloads.allowCellular"

    private static let maniacKey = "playback.maniacMode"

    /// Maniac Mode: albums play start to finish, in order, once — no skip,
    /// shuffle, repeat or scrubbing. Default off.
    public static var maniacMode: Bool {
        get { defaults.bool(forKey: maniacKey) }
        set { defaults.set(newValue, forKey: maniacKey) }
    }

    /// "Streaming over mobile data" in Settings: the quality on mobile data
    /// and hotspots. Default: Normal (256 kbps). The old on/off switch
    /// (`playback.reduceOnMobileData`) is gone; switched off, it reads as
    /// Original (as served), which is what it did.
    public static var cellularQuality: StreamingQuality {
        get {
            if defaults.object(forKey: mobileStreamingKey) as? Bool == false { return .original }
            return defaults.string(forKey: cellularQualityKey).flatMap(StreamingQuality.init) ?? .kbps256
        }
        set {
            defaults.set(newValue.rawValue, forKey: cellularQualityKey)
            defaults.removeObject(forKey: mobileStreamingKey)
        }
    }

    /// Bitrate cap for a stream on mobile data, or `nil` for the original file.
    public static var mobileDataBitrate: Int? { cellularQuality.bitrate }

    /// Level loud and quiet tracks using Jellyfin's `NormalizationGain`. Default off.
    public static var normalizeVolume: Bool {
        get { defaults.bool(forKey: normalizeKey) }
        set { defaults.set(newValue, forKey: normalizeKey) }
    }

    /// "Download over mobile data": whether downloads may use cellular data
    /// or a hotspot. Default off (a saved choice is kept); when off they wait
    /// for Wi‑Fi (`DownloadNetworkRule`).
    public static var downloadOverCellular: Bool {
        get { defaults.object(forKey: cellularDownloadsKey) as? Bool ?? false }
        set { defaults.set(newValue, forKey: cellularDownloadsKey) }
    }

    private static let eqEnabledKey = "playback.eqEnabled"
    private static let eqGainsKey = "playback.eqGains"
    private static let eqPresetKey = "playback.eqPreset"

    /// Equaliser on/off. Default off.
    public static var equalizerEnabled: Bool {
        get { defaults.bool(forKey: eqEnabledKey) }
        set { defaults.set(newValue, forKey: eqEnabledKey) }
    }

    /// Ten band gains in dB (31 Hz … 16 kHz). Default flat.
    public static var equalizerGains: [Float] {
        get {
            let stored = (defaults.array(forKey: eqGainsKey) as? [Double])?.map(Float.init) ?? []
            return stored.count == 10 ? stored : [Float](repeating: 0, count: 10)
        }
        set { defaults.set(newValue.map(Double.init), forKey: eqGainsKey) }
    }

    /// The preset last chosen in the equaliser screen. Default Flat.
    public static var equalizerPreset: EqualizerPreset {
        get { defaults.string(forKey: eqPresetKey).flatMap(EqualizerPreset.init) ?? .flat }
        set { defaults.set(newValue.rawValue, forKey: eqPresetKey) }
    }

    /// Player volume (0…1) for a track's normalisation gain in dB. Gains
    /// above 0 dB can't be applied (the player can't go past full volume).
    public static func volume(forGain gain: Double?) -> Float {
        guard let gain, gain.isFinite else { return 1 }
        return Float(min(1, max(0, pow(10, gain / 20))))
    }
}
