// MARK: - AudioFormat — supported download stream formats

import Foundation

/// Audio download/stream format. Order matters: first = default.
public enum AudioFormat: String, CaseIterable, Codable, Sendable {
    case flac   // lossless, default
    case alac   // Apple lossless
    case aac    // 320kbps, mobile-friendly
    case opus   // bandwidth-efficient
    case mp3    // universal compatibility
    case wav    // uncompressed PCM

    public var displayName: String {
        switch self {
        case .flac: "FLAC"
        case .alac: "ALAC"
        case .aac:  "AAC"
        case .opus: "Opus"
        case .mp3:  "MP3"
        case .wav:  "WAV"
        }
    }

    public var fileExtension: String {
        switch self {
        case .flac: "flac"
        case .alac: "m4a"
        case .aac:  "m4a"
        case .opus: "ogg"
        case .mp3:  "mp3"
        case .wav:  "wav"
        }
    }

    /// Whether this format requires server-side transcoding when
    /// the source format is not already this codec.
    public var requiresTranscode: Bool {
        // FLAC is direct-play on Apple platforms; ALAC also works.
        // The rest require transcode unless source is already that codec.
        switch self {
        case .flac, .alac: false   // natively decoded by Apple
        case .aac, .opus, .mp3, .wav: true
        }
    }

    /// Quality label for UI.
    public var qualityBadge: String {
        switch self {
        case .flac:  "Lossless"
        case .alac:  "Lossless"
        case .aac:   "High Quality"
        case .opus:  "Bandwidth Efficient"
        case .mp3:   "Compatible"
        case .wav:   "Uncompressed"
        }
    }
}

// MARK: - Source detection & download resolution

extension AudioFormat {

    /// Whether the codec is lossless.
    public var isLossless: Bool {
        switch self {
        case .flac, .alac, .wav: true
        case .aac, .opus, .mp3:  false
        }
    }

    /// Relative quality: lossless > aac/opus > mp3.
    var qualityRank: Int {
        switch self {
        case .flac, .alac, .wav: 3
        case .aac, .opus:        2
        case .mp3:               1
        }
    }

    /// The format of a file's audio, from its Core Audio format ID (the
    /// codec FourCC of `CMFormatDescriptionGetMediaSubType`): how a
    /// downloaded file says what it is. `nil` if unrecognised.
    public static func fromCodecID(_ id: UInt32) -> AudioFormat? {
        switch id {
        case 0x616C_6163: .alac                      // 'alac'
        case 0x666C_6163: .flac                      // 'flac'
        case 0x6161_6320, 0x6161_6368, 0x6161_6370: .aac  // 'aac ', 'aach', 'aacp'
        case 0x2E6D_7033: .mp3                       // '.mp3'
        case 0x6F70_7573: .opus                      // 'opus'
        case 0x6C70_636D: .wav                       // 'lpcm'
        default: nil
        }
    }

    /// A file's bit depth from its Core Audio stream description: ALAC and
    /// FLAC give the source's depth in their format flags (1 = 16, 2 = 20,
    /// 3 = 24, 4 = 32-bit), linear PCM in its bits per channel. `nil` for
    /// lossy formats and anything else.
    public static func bitDepth(codecID: UInt32, formatFlags: UInt32, bitsPerChannel: UInt32) -> Int? {
        switch fromCodecID(codecID) {
        case .alac, .flac:
            switch formatFlags {
            case 1: 16
            case 2: 20
            case 3: 24
            case 4: 32
            default: nil
            }
        case .wav: bitsPerChannel > 0 ? Int(bitsPerChannel) : nil
        default: nil
        }
    }

    /// The format a track is stored in on the server, or `nil` if unrecognised.
    public static func source(of track: JellyfinTrack) -> AudioFormat? {
        guard let source = track.mediaSource else { return nil }
        return self.source(codec: source.codecs, container: source.container)
    }

    /// The same from the codec and container alone (a saved queue's track).
    public static func source(codec: String?, container: String?) -> AudioFormat? {
        guard codec != nil || container != nil else { return nil }
        let codec = codec?.lowercased() ?? ""
        let container = container?.lowercased() ?? ""
        if codec.contains("flac") || container == "flac" { return .flac }
        if codec.contains("alac") { return .alac }
        if codec.contains("opus") { return .opus }
        if codec.contains("mp3") || container == "mp3" { return .mp3 }
        if codec.contains("aac") { return .aac }
        if codec.hasPrefix("pcm") || container == "wav" { return .wav }
        if container == "ogg" { return .opus }
        if ["m4a", "mp4", "m4b"].contains(container) || container.contains("mp4") {
            return codec.isEmpty ? .alac : .aac
        }
        return nil
    }

    /// The format to download a track in.
    ///
    /// - `preferred == nil` means "original": whatever Jellyfin stores.
    /// - A preferred format is honoured unless the original is already of
    ///   lower or equal quality (e.g. an MP3 is never transcoded up to FLAC).
    public static func downloadFormat(for track: JellyfinTrack, preferred: AudioFormat?) -> AudioFormat {
        let original = source(of: track)
        guard let preferred else { return original ?? .flac }
        guard let original else { return preferred }
        if original == preferred { return original }
        return original.qualityRank <= preferred.qualityRank && !(original.isLossless && preferred.isLossless)
            ? original : preferred
    }
}
