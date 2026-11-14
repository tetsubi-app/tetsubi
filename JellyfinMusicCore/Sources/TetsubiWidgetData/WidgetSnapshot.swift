// MARK: - WidgetSnapshot — what the app tells the home-screen widget

import Foundation

/// The playing (or last played) track, as the home-screen widget shows it.
/// The app writes one whenever the track, play/pause or Maniac Mode
/// changes (`WidgetSnapshotWriter` in JellyfinMusicCore); the widget only
/// ever reads it. The widget never talks to the server.
public struct WidgetSnapshot: Codable, Equatable, Sendable {

    /// An sRGB colour, each channel 0...1 (the art's key colour).
    public struct RGB: Codable, Equatable, Sendable {
        public let red: Double, green: Double, blue: Double
        public init(red: Double, green: Double, blue: Double) {
            self.red = red; self.green = green; self.blue = blue
        }
    }

    public var trackTitle: String?
    public var artist: String?
    public var album: String?
    /// The album to play again from the widget when the app has no queue:
    /// the merged entry's ID for a multi-disc album (its first disc).
    public var albumId: String?
    /// Every disc's own album ID, in disc order (just `albumId` for a
    /// single-disc album), so a multi-disc album can play from downloads.
    public var discIds: [String]
    public var isPlaying: Bool
    public var maniacMode: Bool
    /// The art's key colour; nil means Tetsubi's usual blue.
    public var accentRGB: RGB?
    /// The art's file name in the snapshot folder, or nil for none.
    public var artFile: String?
    public var signedIn: Bool
    public var updatedAt: Date
    /// While playing: when the track should have ended. If the app has been
    /// closed (so nothing has rewritten the snapshot) the widget shows Play
    /// again after this, instead of a Pause button for music that stopped.
    public var playingUntil: Date?
    /// The whole queue's length in seconds (the album or playlist, as Now
    /// Playing's "Duration"); 0 when it isn't known.
    public var queueSeconds: Int
    /// What's left of the queue at `updatedAt`, in seconds: the rest of
    /// this track and every track after it. Playing, the queue finishes at
    /// `updatedAt` plus this; paused, at the clock's time plus this.
    public var remainingSeconds: Int

    public init(trackTitle: String? = nil, artist: String? = nil, album: String? = nil,
                albumId: String? = nil, discIds: [String] = [], isPlaying: Bool = false,
                maniacMode: Bool = false, accentRGB: RGB? = nil, artFile: String? = nil,
                signedIn: Bool, updatedAt: Date = Date(), playingUntil: Date? = nil,
                queueSeconds: Int = 0, remainingSeconds: Int = 0) {
        self.trackTitle = trackTitle
        self.artist = artist
        self.album = album
        self.albumId = albumId
        self.discIds = discIds
        self.isPlaying = isPlaying
        self.maniacMode = maniacMode
        self.accentRGB = accentRGB
        self.artFile = artFile
        self.signedIn = signedIn
        self.updatedAt = updatedAt
        self.playingUntil = playingUntil
        self.queueSeconds = queueSeconds
        self.remainingSeconds = remainingSeconds
    }

    /// A snapshot from before the queue's length was written reads as 0
    /// (not known) rather than failing.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        trackTitle = try c.decodeIfPresent(String.self, forKey: .trackTitle)
        artist = try c.decodeIfPresent(String.self, forKey: .artist)
        album = try c.decodeIfPresent(String.self, forKey: .album)
        albumId = try c.decodeIfPresent(String.self, forKey: .albumId)
        discIds = try c.decodeIfPresent([String].self, forKey: .discIds) ?? []
        isPlaying = try c.decode(Bool.self, forKey: .isPlaying)
        maniacMode = try c.decodeIfPresent(Bool.self, forKey: .maniacMode) ?? false
        accentRGB = try c.decodeIfPresent(RGB.self, forKey: .accentRGB)
        artFile = try c.decodeIfPresent(String.self, forKey: .artFile)
        signedIn = try c.decode(Bool.self, forKey: .signedIn)
        updatedAt = try c.decode(Date.self, forKey: .updatedAt)
        playingUntil = try c.decodeIfPresent(Date.self, forKey: .playingUntil)
        queueSeconds = try c.decodeIfPresent(Int.self, forKey: .queueSeconds) ?? 0
        remainingSeconds = try c.decodeIfPresent(Int.self, forKey: .remainingSeconds) ?? 0
    }

    /// While playing, when the queue will finish (`updatedAt` plus what
    /// was left then).
    public var playingFinish: Date { updatedAt.addingTimeInterval(TimeInterval(remainingSeconds)) }

    /// The same snapshot with `updatedAt` left out, to tell whether
    /// anything the widget shows has changed. Time left that only moved
    /// on with the clock (playing) or by a second's rounding doesn't count.
    public func sameContent(as other: WidgetSnapshot) -> Bool {
        var a = self, b = other
        if a.isPlaying && b.isPlaying {
            if abs(a.playingFinish.timeIntervalSince(b.playingFinish)) <= Self.finishTolerance {
                a.remainingSeconds = 0; b.remainingSeconds = 0
            }
        } else if abs(a.remainingSeconds - b.remainingSeconds) <= Int(Self.finishTolerance) {
            a.remainingSeconds = 0; b.remainingSeconds = 0
        }
        a.updatedAt = .distantPast
        b.updatedAt = .distantPast
        return a == b
    }

    /// How far the finish time may drift before it's worth a new snapshot.
    public static let finishTolerance: TimeInterval = 2
}

// MARK: - Shared names

public enum WidgetShared {
    /// The App Group both the app and the widget extension belong to.
    public static let appGroup = "group.gg.dekiru.musicplayer.shared"
    /// The widget's kind, for `WidgetCenter.reloadTimelines(ofKind:)`.
    public static let kind = "TetsubiNowPlaying"
    /// Tapping the widget: open Now Playing (Home when nothing is playing).
    public static let nowPlayingURL = URL(string: "tetsubi://nowplaying")!
    /// Tapping the widget when it shows nothing: open the app on Home.
    public static let homeURL = URL(string: "tetsubi://home")!
}

// MARK: - Store

/// Reads and writes the snapshot and its art in the App Group container:
/// `Widget/nowplaying.json` and `Widget/art-<n>.jpg`.
public enum WidgetSnapshotStore {

    /// Used by tests so they never touch the real App Group container.
    nonisolated(unsafe) public static var directoryOverride: URL?

    static let snapshotName = "nowplaying.json"

    /// The snapshot folder, or nil when the App Group isn't available
    /// (unsigned builds, the Mac).
    public static var directory: URL? {
        if let directoryOverride { return directoryOverride }
        return FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: WidgetShared.appGroup)?
            .appendingPathComponent("Widget", isDirectory: true)
    }

    /// The last snapshot written, or nil if there's none (or it can't be read).
    public static func read() -> WidgetSnapshot? {
        guard let url = directory?.appendingPathComponent(snapshotName),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(WidgetSnapshot.self, from: data)
    }

    /// Saves the snapshot (all at once, so the widget never reads half a file).
    public static func write(_ snapshot: WidgetSnapshot) throws {
        guard let dir = directory else { return }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try encoder.encode(snapshot).write(to: dir.appendingPathComponent(snapshotName), options: .atomic)
    }

    /// Where an art file named in a snapshot lives, if it's there.
    public static func artURL(_ name: String?) -> URL? {
        guard let name, let url = directory?.appendingPathComponent(name),
              FileManager.default.fileExists(atPath: url.path) else { return nil }
        return url
    }

    /// Saves art under `name` and removes the older art files, keeping
    /// `keeping` (the art the current snapshot still points at, so the
    /// widget never loses its picture between the two writes).
    public static func saveArt(_ jpeg: Data, name: String, keeping: String? = nil) throws {
        guard let dir = directory else { return }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try jpeg.write(to: dir.appendingPathComponent(name), options: .atomic)
        let others = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        for file in others where file.hasPrefix("art-") && file != name && file != keeping {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(file))
        }
    }

    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        e.outputFormatting = [.sortedKeys]
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return d
    }()
}
