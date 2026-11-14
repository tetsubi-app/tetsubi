// MARK: - Jellyfin API data models

import Foundation

// MARK: - JellyfinAlbum

/// Decodes a Jellyfin MusicAlbum or Audio item response.
/// The Jellyfin API returns a large `BaseItemDto` — we only decode
/// the fields we need and ignore the rest.
public struct JellyfinAlbum: Decodable, Identifiable, Sendable, Hashable {
    public let id: String
    public let name: String?
    public let serverId: String?

    /// Release date — used for "On This Day" feature.
    /// Jellyfin uses `PremiereDate` or `ProductionYear` + month/day.
    public let premiereDate: String?       // RFC3339, e.g. "1995-03-15"
    public let productionYear: Int?

    /// Artist names — Jellyfin returns an array because of compilations.
    public let albumArtists: [JellyfinPerson]?
    public let album: String?

    /// Album cover art tag — used to fetch the image via `albumArtURL`.
    public let imageTags: [String: String]?

    /// Runtime in ticks (100ns intervals). `duration` is computed.
    public let runTimeTicks: Int64?

    /// Whether this item is a MusicAlbum type (vs an Audio track).
    public let type: String?

    /// Per-user play data. `DatePlayed` and `PlayCount` live under `UserData`
    /// (a nested object), not at the top level — required for "Recently Played".
    /// Only present when the request asks for `Fields=UserData`.
    public let userData: UserData?

    /// For a multi-disc album shown as one: every disc's own album, in disc
    /// order. Empty for ordinary albums. Not decoded — set by `DiscGrouping`.
    public var discAlbums: [JellyfinAlbum] = []

    // Computed

    /// Number of times the current user has played this item.
    public var playCount: Int {
        userData?.playCount ?? 0
    }

    /// Last time this item was played by the current user, or nil if the
    /// server didn't record `DatePlayed`. Derived from `UserData.DatePlayed`
    /// (ticks, 100ns intervals).
    public var lastPlayed: Date? {
        guard let ticks = userData?.datePlayed, ticks > 0 else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(ticks / 1_000_000))
    }

    /// Whether the current user has played this item at all.
    ///
    /// Important: for many items Jellyfin reports `PlayCount > 0` while
    /// leaving `IsPlayed`/`DatePlayed` nil, so `playCount` is the most
    /// reliable "has been played" signal. This feeds "Recently Played".
    public var hasBeenPlayed: Bool {
        playCount > 0 || userData?.isPlayed == true
    }

    /// Release date parsed from `PremiereDate`, with `productionYear` fallback.
    ///
    /// Jellyfin sends `PremiereDate` as a full ISO 8601 timestamp with fractional
    /// seconds, e.g. "1994-10-31T00:00:00.0000000Z". Neither `ISO8601DateFormatter`
    /// nor a full-string `DateFormatter("yyyy-MM-dd")` handles the fractional
    /// part, so the reliable approach is to read just the leading `yyyy-MM-dd`
    /// date slice. "On This Day" only compares month/day, so that's all that's
    /// needed. Falls back to `productionYear` (as Jan 1) when no premiere date.
    public var releaseDate: Date? {
        if let raw = premiereDate {
            let datePart = String(raw.prefix(10))         // "1994-10-31"
            if datePart.count == 10 {
                let f = DateFormatter()
                f.locale = Locale(identifier: "en_US_POSIX")
                f.timeZone = TimeZone(identifier: "UTC")
                f.dateFormat = "yyyy-MM-dd"
                if let date = f.date(from: datePart), date.timeIntervalSince1970 > 0 {
                    return date
                }
            }
            // Whole-string fallbacks for the rare clean / date-only forms.
            let iso = ISO8601DateFormatter()
            iso.formatOptions = [.withInternetDateTime]
            if let date = iso.date(from: raw) { return date }
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.timeZone = TimeZone(identifier: "UTC")
            f.dateFormat = "yyyy-MM-dd"
            if let date = f.date(from: raw), date.timeIntervalSince1970 > 0 { return date }
        }
        // No PremiereDate: use production year as Jan 1. "On This Day" will
        // match only Jan 1 then, but at least incomplete metadata still works.
        guard let year = productionYear else { return nil }
        var comps = DateComponents()
        comps.year = year
        comps.month = 1
        comps.day = 1
        return Calendar.current.date(from: comps)
    }

    /// Duration in seconds derived from `runTimeTicks`.
    public var duration: TimeInterval {
        guard let ticks = runTimeTicks else { return 0 }
        return TimeInterval(ticks / 10_000_000)
    }

    /// Display name — falls back to album name if `name` is nil.
    public var displayName: String {
        name ?? album ?? "Unknown"
    }

    /// First artist name for display.
    public var artistName: String {
        albumArtists?.first?.name ?? ""
    }

    /// Server ID of the first album artist, if known.
    public var artistId: String? {
        albumArtists?.first?.id
    }

    enum CodingKeys: String, CodingKey {
        case id = "Id"
        case name = "Name"
        case serverId = "ServerId"
        case premiereDate = "PremiereDate"
        case productionYear = "ProductionYear"
        case albumArtists = "AlbumArtists"
        case album = "Album"
        case imageTags = "ImageTags"
        case runTimeTicks = "RunTimeTicks"
        case type = "Type"
        case userData = "UserData"
    }
}

// MARK: - JellyfinArtist

/// An album artist from `/Artists/AlbumArtists`.
public struct JellyfinArtist: Decodable, Identifiable, Sendable, Hashable {
    public let id: String
    public let name: String

    enum CodingKeys: String, CodingKey {
        case id = "Id"
        case name = "Name"
    }

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }
}

// MARK: - UserData

/// Per-user playback metadata for an item, as returned when the request
/// includes `Fields=UserData`. Carries the play-count and last-played time
/// used to build "Recently Played" lists.
public struct UserData: Decodable, Sendable {
    /// How many times the current user has played this item.
    public let playCount: Int?

    /// Whether the user has played the item to (near) completion.
    public let isPlayed: Bool?

    /// Last time the user played this item, in ticks (100ns intervals).
    /// Convert with `Date(timeIntervalSince1970: TimeInterval(ticks / 1_000_000))`.
    public let datePlayed: Int64?

    /// Whether the user has favourited the item.
    public var isFavorite: Bool? = nil

    enum CodingKeys: String, CodingKey {
        case isFavorite = "IsFavorite"
        case playCount  = "PlayCount"
        case isPlayed   = "IsPlayed"
        case datePlayed = "DatePlayed"
    }
}

// MARK: - JellyfinTrack

/// Decodes a single audio track from a Jellyfin Items response.
public struct JellyfinTrack: Decodable, Identifiable, Sendable {
    public let id: String
    public let name: String?
    public let serverId: String?

    /// Track number within the album (1-indexed).
    public let indexNumber: Int?

    /// Runtime in ticks.
    public let runTimeTicks: Int64?

    /// The album this track belongs to.
    public let album: String?
    public let albumId: String?

    /// Album artist names.
    public let albumArtists: [JellyfinPerson]?

    /// Album art tag.
    public let imageTags: [String: String]?

    /// Media source — tells us the original codec/container.
    /// Used to decide whether to direct-play or transcode.
    public let mediaSource: MediaSource?

    /// Loudness correction in dB from Jellyfin's analysis (e.g. -9.5).
    /// Requested with `Fields=NormalizationGain`.
    public var normalizationGain: Double? = nil

    /// Disc number within the album (`ParentIndexNumber`), for albums
    /// stored as one album with tracks tagged disc 1, disc 2…
    public var discNumber: Int? = nil

    /// The track has its own picture, different from the album's (each
    /// song of The Slip; loose singles). Every track has its own image
    /// tag, so the pictures are compared by blur hash (Fields=ImageBlurHashes).
    public var hasOwnArt = false

    /// Whether a track's picture differs from its album's: by blur hash,
    /// since tags differ even for the same picture. Unknown hashes count
    /// as the same (the album picture is the safe choice).
    public static func ownArt(ownTag: String?, albumTag: String?, blurHashes: [String: String]) -> Bool {
        guard let ownTag else { return false }
        guard let albumTag else { return true }
        guard let own = blurHashes[ownTag], let album = blurHashes[albumTag] else { return false }
        return own != album
    }

    /// Number used for this track's download file and saved title: the
    /// track number on disc 1, disc × 100 + track on later discs (disc 2
    /// track 1 → 201), so discs don't collide and files sort in play order.
    public var fileNumber: Int {
        guard let disc = discNumber, disc > 1 else { return trackNumber }
        return disc * 100 + trackNumber
    }

    /// Download numbers for an album's tracks, in the order given (the
    /// server's play order): each track's `fileNumber`, or 1, 2, 3… when
    /// those aren't all different. Untagged tracks have no numbers (all 0),
    /// and files and saved titles are keyed by number, so every track got
    /// the first one's title.
    public static func fileNumbers(for tracks: [JellyfinTrack]) -> [Int] {
        let numbers = tracks.map(\.fileNumber)
        return Set(numbers).count == numbers.count ? numbers : tracks.indices.map { $0 + 1 }
    }

    // Computed

    public var duration: TimeInterval {
        guard let ticks = runTimeTicks else { return 0 }
        return TimeInterval(ticks / 10_000_000)
    }

    /// Title for display. Untagged files come through as
    /// "01 - Artist - Song"; this shows just "Song".
    public var displayName: String {
        guard let name, !name.isEmpty else { return "Unknown" }
        return TrackTitle.clean(name, artists: albumArtists?.map(\.name) ?? []).title
    }

    /// Track number from the tag, else from a "01 - " filename-style prefix.
    public var trackNumber: Int {
        if let indexNumber { return indexNumber }
        guard let name else { return 0 }
        return TrackTitle.clean(name, artists: []).number ?? 0
    }

    enum CodingKeys: String, CodingKey {
        case id = "Id"
        case name = "Name"
        case serverId = "ServerId"
        case indexNumber = "IndexNumber"
        case runTimeTicks = "RunTimeTicks"
        case album = "Album"
        case albumId = "AlbumId"
        case albumArtists = "AlbumArtists"
        case imageTags = "ImageTags"
        case mediaSource = "MediaSources"
        case normalizationGain = "NormalizationGain"
        case discNumber = "ParentIndexNumber"
        case albumPrimaryImageTag = "AlbumPrimaryImageTag"
        case imageBlurHashes = "ImageBlurHashes"
    }
}

extension JellyfinAlbum {
    private enum AudioKeys: String, CodingKey { case albumId = "AlbumId" }

    /// Some queries (e.g. recently played) return `Audio` tracks standing in
    /// for albums. Resolve those to the parent album's id and name so detail
    /// views fetch the album, not the track.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let type = try c.decodeIfPresent(String.self, forKey: .type)
        let album = try c.decodeIfPresent(String.self, forKey: .album)
        var id = try c.decode(String.self, forKey: .id)
        var name = try c.decodeIfPresent(String.self, forKey: .name)
        if type == "Audio" {
            if let parent = try decoder.container(keyedBy: AudioKeys.self)
                .decodeIfPresent(String.self, forKey: .albumId) {
                id = parent
            }
            if let album { name = album }
        }
        self.init(
            id: id,
            name: name,
            serverId: try c.decodeIfPresent(String.self, forKey: .serverId),
            premiereDate: try c.decodeIfPresent(String.self, forKey: .premiereDate),
            productionYear: try c.decodeIfPresent(Int.self, forKey: .productionYear),
            albumArtists: try c.decodeIfPresent([JellyfinPerson].self, forKey: .albumArtists),
            album: album,
            imageTags: try c.decodeIfPresent([String: String].self, forKey: .imageTags),
            runTimeTicks: try c.decodeIfPresent(Int64.self, forKey: .runTimeTicks),
            type: type,
            userData: try c.decodeIfPresent(UserData.self, forKey: .userData)
        )
    }
}

extension JellyfinTrack {
    /// The server sends `MediaSources` (an array); we keep the first entry.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: try c.decode(String.self, forKey: .id),
            name: try c.decodeIfPresent(String.self, forKey: .name),
            serverId: try c.decodeIfPresent(String.self, forKey: .serverId),
            indexNumber: try c.decodeIfPresent(Int.self, forKey: .indexNumber),
            runTimeTicks: try c.decodeIfPresent(Int64.self, forKey: .runTimeTicks),
            album: try c.decodeIfPresent(String.self, forKey: .album),
            albumId: try c.decodeIfPresent(String.self, forKey: .albumId),
            albumArtists: try c.decodeIfPresent([JellyfinPerson].self, forKey: .albumArtists),
            imageTags: try c.decodeIfPresent([String: String].self, forKey: .imageTags),
            mediaSource: try c.decodeIfPresent([MediaSource].self, forKey: .mediaSource)?.first
        )
        normalizationGain = try c.decodeIfPresent(Double.self, forKey: .normalizationGain)
        discNumber = try c.decodeIfPresent(Int.self, forKey: .discNumber)
        let hashes = (try? c.decodeIfPresent([String: [String: String]].self, forKey: .imageBlurHashes))??["Primary"] ?? [:]
        hasOwnArt = Self.ownArt(ownTag: imageTags?["Primary"],
                                albumTag: try c.decodeIfPresent(String.self, forKey: .albumPrimaryImageTag),
                                blurHashes: hashes)
    }
}

// MARK: - JellyfinPerson

public struct JellyfinPerson: Decodable, Sendable {
    public let id: String?
    public let name: String
    public let serverId: String?

    enum CodingKeys: String, CodingKey {
        case id = "Id"
        case name = "Name"
        case serverId = "ServerId"
    }
}

// MARK: - MediaSource

/// Represents the original file info for a track.
/// Used to determine direct-play vs transcode.
public struct MediaSource: Decodable, Sendable {
    public let id: String?
    public let container: String?      // "flac", "mp3", "m4a", "ogg", "wav", "ts"
    public let videoType: String?
    public let mediaType: String?      // "Audio"
    public let size: Int64?
    public let name: String?

    /// The codec — key for transcode decisions.
    public let codecs: String?         // e.g. "flac", "aac", "opus", "mp3"

    /// The file's bitrate in bits per second (`Bitrate`, else the audio
    /// stream's `BitRate`), for Now Playing. Only set when decoded from the server.
    public var bitrate: Int? = nil
    /// The audio stream's sample rate in Hz (`SampleRate`), e.g. 44100.
    public var sampleRate: Int? = nil
    /// The audio stream's bit depth (`BitDepth`), e.g. 16; lossy files have none.
    public var bitDepth: Int? = nil

    enum CodingKeys: String, CodingKey {
        case id = "Id"
        case container = "Container"
        case videoType = "VideoType"
        case mediaType = "MediaType"
        case size = "Size"
        case name = "Name"
        case codecs = "Codecs"
        case bitrate = "Bitrate"
    }
}

extension MediaSource {
    private struct Stream: Decodable {
        let codec: String?
        let type: String?
        let bitRate: Int?
        let sampleRate: Int?
        let bitDepth: Int?
        enum CodingKeys: String, CodingKey {
            case codec = "Codec", type = "Type", bitRate = "BitRate"
            case sampleRate = "SampleRate", bitDepth = "BitDepth"
        }
    }
    private enum StreamKeys: String, CodingKey { case mediaStreams = "MediaStreams" }

    /// Jellyfin reports codecs per stream (`MediaStreams`), not as a top-level
    /// `Codecs` field, so fall back to the first audio stream's codec.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        var codecs = try c.decodeIfPresent(String.self, forKey: .codecs)
        let audio = try decoder.container(keyedBy: StreamKeys.self)
            .decodeIfPresent([Stream].self, forKey: .mediaStreams)?
            .first { $0.type == "Audio" }
        if codecs == nil { codecs = audio?.codec }
        self.init(
            id: try c.decodeIfPresent(String.self, forKey: .id),
            container: try c.decodeIfPresent(String.self, forKey: .container),
            videoType: try c.decodeIfPresent(String.self, forKey: .videoType),
            mediaType: try c.decodeIfPresent(String.self, forKey: .mediaType),
            size: try c.decodeIfPresent(Int64.self, forKey: .size),
            name: try c.decodeIfPresent(String.self, forKey: .name),
            codecs: codecs?.lowercased()
        )
        bitrate = (try? c.decodeIfPresent(Int.self, forKey: .bitrate)) ?? audio?.bitRate
        sampleRate = audio?.sampleRate.flatMap { $0 > 0 ? $0 : nil }
        bitDepth = audio?.bitDepth.flatMap { $0 > 0 ? $0 : nil }
    }
}

// MARK: - Hashable

/// `JellyfinAlbum` is a navigation destination — its identity is `id`, so
/// `navigationDestination` can dedupe repeat taps on the same album.
extension JellyfinAlbum {
    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.id == rhs.id
    }
    public func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
}
