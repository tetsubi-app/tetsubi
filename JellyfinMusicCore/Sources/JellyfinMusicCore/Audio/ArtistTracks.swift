// MARK: - ArtistTracks — the rules behind Play all and Shuffle on an artist's page (docs/redesign.md section 5)

import Foundation

/// Pure rules for "play an artist"; the same as Kotlin's `ArtistTracks`.
public enum ArtistTracks {
    /// The most tracks an artist's Play all or Shuffle queues; the rest are left out and the player says so.
    public static let cap = 500

    /// The albums as they are played: oldest release first (full release date, else Jan 1 of the
    /// production year; unknown last), the same date by title. The tracks of each album follow in
    /// disc and track order. The page itself lists the newest first.
    public static func albumOrder(_ albums: [JellyfinAlbum]) -> [JellyfinAlbum] {
        albums.sorted { a, b in
            switch (a.releaseDate, b.releaseDate) {
            case let (x?, y?) where x != y: return x < y
            case (nil, .some): return false
            case (.some, nil): return true
            default: return a.displayName.lowercased() < b.displayName.lowercased()
            }
        }
    }

    /// `tracks` cut to `cap`, and whether anything was left out.
    public static func limit<T>(_ tracks: [T], cap: Int = ArtistTracks.cap) -> (kept: [T], capped: Bool) {
        tracks.count > cap ? (Array(tracks.prefix(cap)), true) : (tracks, false)
    }

    /// More albums are only fetched while the tracks so far are within the cap (one over proves something is left out).
    public static func needsMore(trackCount: Int, cap: Int = ArtistTracks.cap) -> Bool { trackCount <= cap }

    /// The notice after a capped play: "Queued the first 500 of this artist's tracks."
    public static func cappedMessage(cap: Int = ArtistTracks.cap) -> String {
        "Queued the first \(cap) of this artist's tracks."
    }

    public static let noTracks = "This artist has no tracks."
    public static let notDownloaded = "None of this artist's music is downloaded."
    public static let notLoaded = "Couldn't load this artist's music. Check the connection, then try again."

    /// How an artist's play or queue ended.
    public enum Outcome: Equatable, Sendable {
        case done(capped: Bool)
        case noTracks
        case notDownloaded
        case notLoaded

        /// The notice to show: the cap, or why nothing was queued; nil when all is as asked.
        public var notice: String? {
            switch self {
            case .done(let capped): capped ? ArtistTracks.cappedMessage() : nil
            case .noTracks: ArtistTracks.noTracks
            case .notDownloaded: ArtistTracks.notDownloaded
            case .notLoaded: ArtistTracks.notLoaded
            }
        }

        public var isFailure: Bool {
            if case .done = self { return false }
            return true
        }
    }

    /// Nothing came out: the reason, by connection.
    public static func emptyOutcome(online: Bool) -> Outcome { online ? .noTracks : .notDownloaded }

    /// The artist's queue group: titled and pictured by the artist.
    public static func group(id: String, name: String) -> QueueGroup {
        QueueGroup(id: id, kind: .tracks, title: name, artItemId: id)
    }
}
