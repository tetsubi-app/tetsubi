// MARK: - PlaybackModels — shared enums and types for the audio layer

import Foundation

// MARK: - PlaybackError

public enum PlaybackError: Error, Equatable, Sendable {
    case noCredentials
    case trackListEmpty
    case urlBuildFailed
    case audioSessionUnavailable
    case noCurrentItem
    /// The track couldn't be played (stream failed to load or stopped partway).
    case trackFailed
    case unknown
}

// MARK: - PlaylistPlayRefusal

/// Why a playlist didn't start playing with no connection.
public enum PlaylistPlayRefusal: Error, Equatable, Sendable {
    /// The tapped song isn't on this device.
    case songNotDownloaded
    /// None of the playlist's songs are on this device.
    case nothingDownloaded

    public var message: String {
        switch self {
        case .songNotDownloaded: "This song isn't downloaded."
        case .nothingDownloaded: "None of this playlist's songs are downloaded."
        }
    }
}

// MARK: - RepeatMode

/// Raw values ("off", "all", "one") are the saved queue's format.
public enum RepeatMode: String, Codable, Sendable, Equatable, CaseIterable {
    /// Default — the queue stops at the end.
    case off
    /// Loop the entire queue.
    case all
    /// Loop the current track.
    case one
}

// MARK: - PlaybackTrack — lightweight view of a track in the queue

/// The minimal metadata the now-playing centre / bar / queue view need.
public struct PlaybackTrack: Sendable, Identifiable, Hashable {
    public let id: String
    public let trackNumber: Int
    public let title: String
    /// The album artist (the track's first, else the album's).
    public let artist: String
    public let album: String
    public let albumId: String
    public let duration: TimeInterval
    /// URL of the album's primary image — nil if the server doesn't have one.
    public let albumArtURL: URL?
    /// Local file URL when playing a downloaded file; `nil` when streaming
    /// from the Jellyfin server. `PlayerManager` prefers this over the
    /// stream URL when non-nil.
    public let localURL: URL?
    /// Loudness correction in dB (see `PlaybackSettings.normalizeVolume`).
    public var normalizationGain: Double? = nil
    /// Format and bitrate for Now Playing, e.g. "ALAC 905 kbps"; nil if unknown.
    public var qualityLabel: String? = nil
    /// The source file's sample rate in Hz, e.g. 44100; nil if unknown.
    public var sampleRate: Int? = nil
    /// The source file's bit depth, e.g. 16; nil for lossy files or if unknown.
    public var bitDepth: Int? = nil
    /// The source file's audio codec, lower-case ("alac", "aac", "flac"…),
    /// from the server's media source; nil if unknown. Not the stream's:
    /// on mobile data the stream is AAC whatever this says.
    public var codec: String? = nil
    /// Server ID of `artist`, when known; Now Playing's artist line opens
    /// that artist's albums (`ArtistLink`). Nil for a download played from
    /// the Downloads tab, which saved only the name.
    public var albumArtistId: String? = nil
    /// Sample rate and bit depth for Now Playing, e.g. "44.1kHz/16-bit".
    public var sampleRateLabel: String? {
        Self.sampleRateLabel(sampleRate: sampleRate, bitDepth: bitDepth)
    }
    /// The item whose picture is shown: the track's own when it differs
    /// from the album's (`JellyfinTrack.hasOwnArt`), else the album.
    public var artItemId: String
    /// A downloaded track's own picture on disk (`TrackCovers`), if any.
    public var localArtURL: URL? = nil
    /// Changes when the picture does: the background and Now Playing
    /// fade to the new art only then.
    public var artKey: String { localArtURL?.path ?? artItemId }

    public init(
        id: String,
        trackNumber: Int,
        title: String,
        artist: String,
        album: String,
        albumId: String,
        duration: TimeInterval,
        albumArtURL: URL? = nil,
        localURL: URL? = nil
    ) {
        self.id = id
        self.trackNumber = trackNumber
        self.title = title
        self.artist = artist
        self.album = album
        self.albumId = albumId
        self.duration = duration
        self.albumArtURL = albumArtURL
        self.localURL = localURL
        self.artItemId = albumId
    }

    /// Build a `PlaybackTrack` from a `JellyfinTrack`, inheriting album
    /// metadata from the parent album.
    public init(
        track: JellyfinTrack,
        album: JellyfinAlbum,
        albumArtURL: URL? = nil,
        localURL: URL? = nil
    ) {
        self.id = track.id
        self.trackNumber = track.trackNumber
        self.title = track.displayName
        let albumArtist = ArtistLink.albumArtist(trackArtists: track.albumArtists, album: album)
        self.artist = albumArtist.name
        self.albumArtistId = albumArtist.id
        self.album = album.displayName
        self.albumId = album.id
        self.duration = track.duration
        self.albumArtURL = albumArtURL
        self.localURL = localURL
        self.artItemId = track.hasOwnArt ? track.id : album.id
        self.normalizationGain = track.normalizationGain
        self.qualityLabel = Self.qualityLabel(format: AudioFormat.source(of: track),
                                              bitrate: track.mediaSource?.bitrate)
        self.sampleRate = track.mediaSource?.sampleRate
        self.bitDepth = track.mediaSource?.bitDepth
        self.codec = track.mediaSource?.codecs?.lowercased()
    }

    /// "44.1kHz/16-bit", "48kHz" (no bit depth, as for lossy files), or
    /// nil (sample rate unknown). The rate is in kHz with up to two
    /// decimals, trailing zeros dropped: 44100 → "44.1kHz", 96000 → "96kHz".
    public static func sampleRateLabel(sampleRate: Int?, bitDepth: Int?) -> String? {
        guard let sampleRate, sampleRate > 0 else { return nil }
        var khz = String(format: "%.2f", Double(sampleRate) / 1000)
        while khz.hasSuffix("0") { khz.removeLast() }
        if khz.hasSuffix(".") { khz.removeLast() }
        guard let bitDepth, bitDepth > 0 else { return "\(khz)kHz" }
        return "\(khz)kHz/\(bitDepth)-bit"
    }

    /// Describes what is streamed when it isn't the file itself: AAC at the
    /// cap on mobile data, or the format the server converts a source
    /// AVPlayer can't play to (`StreamURLBuilder.plan`). Lossy, so no bit
    /// depth; the sample rate stays the file's. Unchanged for an original.
    public mutating func describeStream(codec: String?, container: String?, mobileCap: Int?) {
        if let mobileCap {
            qualityLabel = "AAC \(mobileCap / 1000) kbps"
            bitDepth = nil
            return
        }
        guard case .converted(let format) = StreamURLBuilder.plan(codec: codec, container: container) else { return }
        if format == .aac {
            qualityLabel = "AAC \(StreamURLBuilder.convertedAACBitrate / 1000) kbps"
            bitDepth = nil
        } else {
            qualityLabel = format.displayName
        }
    }

    /// "ALAC 905 kbps", "FLAC" (bitrate unknown), or nil (format unknown).
    public static func qualityLabel(format: AudioFormat?, bitrate: Int?) -> String? {
        guard let format else { return nil }
        guard let bitrate, bitrate > 0 else { return format.displayName }
        return "\(format.displayName) \(Int((Double(bitrate) / 1000).rounded())) kbps"
    }
}

// MARK: - JellyfinTrack artist helper

extension JellyfinTrack {
    /// First album artist, for the now-playing centre.
    var artistName: String? {
        albumArtists?.first?.name
    }
}