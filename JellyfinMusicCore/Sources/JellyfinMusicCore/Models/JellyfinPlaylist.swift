// MARK: - Playlists — a server playlist and the songs in it

import Foundation

// MARK: - JellyfinPlaylist

/// A music playlist on the server, as listed by `JellyfinAPI.playlists`.
public struct JellyfinPlaylist: Decodable, Identifiable, Sendable, Hashable {
    public let id: String
    public let name: String
    /// Songs in the playlist (`ChildCount`). The server's figure can lag
    /// after an edit; the playlist page counts its own loaded tracks.
    public let songCount: Int
    /// Total playing time in ticks (100ns), from `CumulativeRunTimeTicks`.
    public let runTimeTicks: Int64
    public let dateCreated: Date?
    /// The server has a picture for the playlist (its collage). Until it
    /// does, the first track's album art is shown instead.
    public let hasArt: Bool

    public init(id: String, name: String, songCount: Int = 0, runTimeTicks: Int64 = 0,
                dateCreated: Date? = nil, hasArt: Bool = false) {
        self.id = id
        self.name = name
        self.songCount = songCount
        self.runTimeTicks = runTimeTicks
        self.dateCreated = dateCreated
        self.hasArt = hasArt
    }

    /// Playing time in seconds.
    public var duration: TimeInterval { TimeInterval(runTimeTicks / 10_000_000) }

    /// "12 songs · 1 h 12 min", for rows and cards.
    public var summary: String { Self.summary(songCount: songCount, duration: duration) }

    enum CodingKeys: String, CodingKey {
        case id = "Id"
        case name = "Name"
        case songCount = "ChildCount"
        case cumulativeRunTimeTicks = "CumulativeRunTimeTicks"
        case runTimeTicks = "RunTimeTicks"
        case dateCreated = "DateCreated"
        case imageTags = "ImageTags"
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let ticks = try c.decodeIfPresent(Int64.self, forKey: .cumulativeRunTimeTicks)
            ?? c.decodeIfPresent(Int64.self, forKey: .runTimeTicks)
        let tags = try c.decodeIfPresent([String: String].self, forKey: .imageTags)
        self.init(
            id: try c.decode(String.self, forKey: .id),
            name: try c.decodeIfPresent(String.self, forKey: .name) ?? "Unknown",
            songCount: try c.decodeIfPresent(Int.self, forKey: .songCount) ?? 0,
            runTimeTicks: ticks ?? 0,
            dateCreated: (try c.decodeIfPresent(String.self, forKey: .dateCreated)).flatMap(Self.date(from:)),
            hasArt: tags?["Primary"] != nil
        )
    }

    /// The server's timestamps carry seven decimal places
    /// ("2026-11-11T23:28:15.0000000Z"), which the ISO formatter won't
    /// read, so only the part up to the seconds is parsed (always UTC).
    static func date(from raw: String) -> Date? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return f.date(from: String(raw.prefix(19)))
    }

    // MARK: Wording

    /// "1 song", "12 songs".
    public static func songCountText(_ count: Int) -> String {
        count == 1 ? "1 song" : "\(count) songs"
    }

    /// "1 h 12 min", "47 min"; under a minute, "1 min". Empty when zero.
    public static func durationText(_ seconds: TimeInterval) -> String {
        guard seconds > 0 else { return "" }
        let minutes = max(1, Int(seconds) / 60)
        let h = minutes / 60, m = minutes % 60
        if h == 0 { return "\(m) min" }
        return m == 0 ? "\(h) h" : "\(h) h \(m) min"
    }

    /// "12 songs · 1 h 12 min"; just the count when there's no playing time.
    public static func summary(songCount: Int, duration: TimeInterval) -> String {
        let time = durationText(duration)
        return time.isEmpty ? songCountText(songCount) : "\(songCountText(songCount)) · \(time)"
    }
}

// MARK: - PlaylistEntry

/// One row of a playlist. A song can be in a playlist more than once, and
/// the server gives both rows the same IDs, so a row is known by its
/// `position` — never by `entryId` or the track's ID alone.
public struct PlaylistEntry: Identifiable, Sendable {
    /// Place in the playlist, from 0.
    public let position: Int
    /// The server's `PlaylistItemId` (on this server, the track's ID).
    public let entryId: String
    public let track: JellyfinTrack
    /// The track's own album, shown per row and carried into the queue.
    public let albumName: String
    public let albumId: String?
    public let albumArtist: String

    public var id: Int { position }

    public init(position: Int, entryId: String, track: JellyfinTrack,
                albumName: String, albumId: String?, albumArtist: String) {
        self.position = position
        self.entryId = entryId
        self.track = track
        self.albumName = albumName
        self.albumId = albumId
        self.albumArtist = albumArtist
    }

    /// "artist · album" under the title (whichever of the two is known).
    public var detailLine: String {
        [albumArtist, albumName].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// The item whose picture the row shows: the track's own when it
    /// differs from its album's, else the album.
    public var artItemId: String {
        track.hasOwnArt ? track.id : (albumId ?? track.id)
    }

    /// The track's album as a queue album, so each queued song keeps its
    /// own album name, ID and art.
    var album: JellyfinAlbum {
        JellyfinAlbum(
            id: albumId ?? track.id, name: albumName, serverId: nil,
            premiereDate: nil, productionYear: nil,
            albumArtists: albumArtist.isEmpty ? nil : [JellyfinPerson(id: nil, name: albumArtist, serverId: nil)],
            album: nil, imageTags: nil, runTimeTicks: nil, type: "MusicAlbum", userData: nil
        )
    }
}

public extension Array where Element == PlaylistEntry {
    /// Playing time of the loaded tracks, in seconds.
    var totalDuration: TimeInterval { reduce(0) { $0 + $1.track.duration } }
}

/// The answer of `Playlists/{id}/Items`: its tracks in playlist order.
struct PlaylistEntryList: Decodable {
    let entries: [PlaylistEntry]

    private enum CodingKeys: String, CodingKey { case items = "Items" }

    /// A track of the list with the fields only playlists use.
    private struct Item: Decodable {
        let track: JellyfinTrack
        let entryId: String?
        let albumArtist: String?

        private enum CodingKeys: String, CodingKey {
            case entryId = "PlaylistItemId"
            case albumArtist = "AlbumArtist"
        }

        init(from decoder: any Decoder) throws {
            track = try JellyfinTrack(from: decoder)
            let c = try decoder.container(keyedBy: CodingKeys.self)
            entryId = try c.decodeIfPresent(String.self, forKey: .entryId)
            albumArtist = try c.decodeIfPresent(String.self, forKey: .albumArtist)
        }
    }

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let items = try c.decodeIfPresent([Item].self, forKey: .items) ?? []
        entries = items.enumerated().map { position, item in
            PlaylistEntry(
                position: position,
                entryId: item.entryId ?? item.track.id,
                track: item.track,
                albumName: item.track.album ?? "",
                albumId: item.track.albumId,
                albumArtist: item.albumArtist ?? item.track.albumArtists?.first?.name ?? ""
            )
        }
    }
}
