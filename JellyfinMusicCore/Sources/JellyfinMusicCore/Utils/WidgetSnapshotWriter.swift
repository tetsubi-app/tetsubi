// MARK: - WidgetSnapshotWriter — keeps the home-screen widget up to date

import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import TetsubiWidgetData
#if canImport(WidgetKit) && os(iOS)
import WidgetKit
#endif

/// Writes the widget's snapshot (`WidgetSnapshot`, in the App Group) when
/// the track, play/pause, position (a seek) or Maniac Mode changes, with
/// the queue's length and what's left of it, then asks WidgetKit to
/// redraw the widget. `PlayerManager` calls `playerChanged` (iOS only);
/// `AppSession` calls the sign-in and sign-out hooks.
///
/// Writes are throttled to about one a second, so seeking or a burst of
/// changes doesn't redraw the widget over and over. A new track's art (a
/// small JPEG) and key colour are worked out first; the snapshot waits for
/// them up to `artWait`, so the widget doesn't flash the old picture.
@MainActor
public final class WidgetSnapshotWriter {

    public static let shared = WidgetSnapshotWriter()

    /// At most one write (and widget redraw) per this many seconds.
    nonisolated static let minimumInterval: TimeInterval = 1
    /// How long a write waits for a new track's art before going without it.
    nonisolated static let artWait: TimeInterval = 3
    /// The art's longest side in pixels.
    nonisolated static let artSide = 300
    /// While playing, the widget shows Pause until the track should have
    /// ended plus this much (see `WidgetSnapshot.playingUntil`).
    nonisolated static let endMargin: TimeInterval = 30
    /// The same when the track's length isn't known yet.
    nonisolated static let unknownLengthWait: TimeInterval = 20 * 60

    /// The snapshot as it will next be written.
    private var current: WidgetSnapshot
    private var lastWrite: Date = .distantPast
    /// The art file the last written snapshot points at (kept on disk
    /// until a newer snapshot stops using it).
    private var writtenArt: String?
    private var pendingWrite: Task<Void, Never>?
    /// The art being shown or loaded (`PlaybackTrack.artKey`).
    private var artKey: String?
    private var artTask: Task<Void, Never>?
    /// While a new track's art loads, writes wait until this time.
    private var artWaitUntil: Date?

    private init() {
        // A fresh process isn't playing anything yet: keep the last track
        // shown (Play starts its album again), but as paused.
        var last = WidgetSnapshotStore.read() ?? WidgetSnapshot(signedIn: false)
        last.isPlaying = false
        last.playingUntil = nil
        current = last
        writtenArt = last.artFile
    }

    // MARK: - Hooks

    /// The player's track, play/pause state, position (after a seek) or
    /// Maniac Mode changed. Only the session's own player counts.
    public func playerChanged(_ player: PlayerManager) {
        guard player === AppSession.shared.player else { return }
        current.signedIn = true
        if let track = player.currentTrack {
            let ids = Self.albumIDs(queueAlbumIDs: player.queue.map(\.albumId),
                                    sourcePositions: player.queuePositions,
                                    sourceID: player.queueSourceId, currentAlbumID: track.albumId)
            current.trackTitle = track.title
            current.artist = track.artist
            current.album = track.album
            current.albumId = ids.album
            current.discIds = ids.discs
            if track.artKey != artKey { loadArt(for: track, player: player) }
        }
        // With no track (stopped), the last one stays shown, paused.
        let playing = player.isPlaying && player.currentTrack != nil
        current.isPlaying = playing
        current.maniacMode = player.maniacMode
        scheduleWrite()
    }

    /// Signed in. A new login (`clearTrack`) forgets the last track, which
    /// may belong to another server; restoring the saved login keeps it.
    public func signedIn(_ player: PlayerManager, clearTrack: Bool) {
        if clearTrack {
            artTask?.cancel()
            artKey = nil
            artWaitUntil = nil
            current = WidgetSnapshot(signedIn: true)
        }
        current.signedIn = true
        playerChanged(player)
    }

    /// Signed out: the widget says "Open Tetsubi to sign in".
    public func signedOut() {
        artTask?.cancel()
        artKey = nil
        artWaitUntil = nil
        current = WidgetSnapshot(signedIn: false)
        writeNow()
    }

    /// Writes straight away, ignoring the throttle — after a widget button,
    /// so the widget redraws with the new state. A new track whose art is
    /// still loading waits for it (up to `artWait`), as usual.
    public func flush() {
        if let artWaitUntil, artWaitUntil > Date() {
            lastWrite = .distantPast
            scheduleWrite()
        } else {
            writeNow(force: true)
        }
    }

    // MARK: - Writing

    private func scheduleWrite() {
        pendingWrite?.cancel()
        let now = Date()
        let due = Self.nextWrite(now: now, lastWrite: lastWrite, artWaitUntil: artWaitUntil)
        // Never straight away, even when due: the player sets a new track
        // first and its length and position just after, all in one go.
        pendingWrite = Task { [weak self] in
            let wait = due.timeIntervalSince(now)
            if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
            guard !Task.isCancelled else { return }
            self?.writeNow()
        }
    }

    /// When the next write may happen: a second after the last one, and
    /// not while a new track's art is still loading (up to `artWait`).
    nonisolated static func nextWrite(now: Date, lastWrite: Date, artWaitUntil: Date?) -> Date {
        var due = lastWrite.addingTimeInterval(minimumInterval)
        if let artWaitUntil, artWaitUntil > due { due = artWaitUntil }
        return max(due, now)
    }

    private var lastWritten: WidgetSnapshot?

    private func writeNow(force: Bool = false) {
        pendingWrite?.cancel()
        pendingWrite = nil
        // Worked out now, not when the change came in: a new track's length
        // and position are set just after it.
        let now = Date()
        let player = AppSession.shared.player
        if current.isPlaying, let player {
            current.playingUntil = Self.playingUntil(duration: player.duration, position: player.position, now: now)
        } else {
            current.playingUntil = nil
        }
        // The queue's length and what's left of it now (Now Playing's
        // Duration and End time). No track: nothing to finish.
        if let player, player.currentTrack != nil {
            let times = Self.queueTimes(length: player.albumLength, remaining: player.albumRemaining)
            current.queueSeconds = times.queue
            current.remainingSeconds = times.remaining
        } else {
            current.queueSeconds = 0
            current.remainingSeconds = 0
        }
        current.updatedAt = now
        if !force, let lastWritten, lastWritten.sameContent(as: current) { return }
        #if os(iOS)
        do {
            try WidgetSnapshotStore.write(current)
        } catch {
            print("[Widget] Couldn't save the snapshot: \(error)")
            return
        }
        lastWritten = current
        lastWrite = Date()
        // Art no longer used by the written snapshot can go now.
        if let old = writtenArt, old != current.artFile,
           let url = WidgetSnapshotStore.artURL(old) {
            try? FileManager.default.removeItem(at: url)
        }
        writtenArt = current.artFile
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetShared.kind)
        #endif
        #endif
    }

    /// While playing: when the widget should stop showing Pause if nothing
    /// rewrites the snapshot first (the app was closed). Rounded up to
    /// `endRounding` seconds, so the same track playing on gives the same
    /// time and doesn't count as a change.
    nonisolated static func playingUntil(duration: TimeInterval, position: TimeInterval, now: Date) -> Date {
        let wait = duration > 0 ? max(duration - position, 0) + endMargin : unknownLengthWait
        let end = now.addingTimeInterval(wait).timeIntervalSince1970
        return Date(timeIntervalSince1970: (end / endRounding).rounded(.up) * endRounding)
    }

    nonisolated static let endRounding: TimeInterval = 15

    /// The queue's length and what's left, in whole seconds (0 when the
    /// length isn't known yet).
    nonisolated static func queueTimes(length: TimeInterval, remaining: TimeInterval) -> (queue: Int, remaining: Int) {
        guard length.isFinite, length > 0 else { return (0, 0) }
        let left = remaining.isFinite ? min(max(remaining, 0), length) : 0
        return (Int(length.rounded()), Int(left.rounded()))
    }

    // MARK: - Album

    /// The album to play again from the widget and its discs, in disc
    /// order. A queue started from an album has that album's ID as its
    /// source (the first disc's, for a multi-disc album, with each disc's
    /// own ID on its tracks). A playlist's source is the playlist, so the
    /// playing track's own album is used.
    nonisolated static func albumIDs(queueAlbumIDs: [String], sourcePositions: [Int],
                                     sourceID: String?, currentAlbumID: String) -> (album: String, discs: [String]) {
        guard let sourceID, queueAlbumIDs.contains(sourceID) else { return (currentAlbumID, [currentAlbumID]) }
        // Back in album order when shuffled (positions are places in the album).
        let ordered: [String]
        if sourcePositions.count == queueAlbumIDs.count {
            ordered = zip(sourcePositions, queueAlbumIDs).sorted { $0.0 < $1.0 }.map(\.1)
        } else {
            ordered = queueAlbumIDs
        }
        var seen = Set<String>()
        return (sourceID, ordered.filter { seen.insert($0).inserted })
    }

    // MARK: - Art

    private func loadArt(for track: PlaybackTrack, player: PlayerManager) {
        artTask?.cancel()
        artKey = track.artKey
        current.artFile = nil
        current.accentRGB = nil
        artWaitUntil = Date().addingTimeInterval(Self.artWait)
        // The same picture the app shows (so the same key colour as Now
        // Playing's), else a downloaded album's saved cover.
        let request = player.artRequest(for: track, size: 600)
        let saved = track.localArtURL
            ?? (track.artItemId == track.albumId ? DownloadPaths.savedCover(albumID: track.albumId) : nil)
        let key = track.artKey
        artTask = Task { [weak self] in
            var image: CGImage? = nil
            if let request { image = await ImageCache.load(request) }
            if image == nil, let saved { image = await ImageCache.load(URLRequest(url: saved)) }
            guard let image, !Task.isCancelled else {
                self?.artFinished(key: key, jpeg: nil, accent: nil)
                return
            }
            let made = await Task.detached(priority: .utility) {
                (Self.jpeg(of: image, side: Self.artSide),
                 ArtPalette.accentColor(of: image))
            }.value
            guard !Task.isCancelled else { return }
            self?.artFinished(key: key, jpeg: made.0, accent: made.1)
        }
    }

    private func artFinished(key: String, jpeg: Data?, accent: ArtPalette.RGB?) {
        guard key == artKey else { return }
        artWaitUntil = nil
        if let jpeg {
            let name = "art-\(UUID().uuidString.prefix(8)).jpg"
            #if os(iOS)
            do {
                try WidgetSnapshotStore.saveArt(jpeg, name: name, keeping: writtenArt)
                current.artFile = name
            } catch {
                print("[Widget] Couldn't save the art: \(error)")
            }
            #endif
        }
        current.accentRGB = accent.map { WidgetSnapshot.RGB(red: $0.red, green: $0.green, blue: $0.blue) }
        scheduleWrite()
    }

    /// The image shrunk to fit `side` pixels (never enlarged), as a JPEG.
    nonisolated static func jpeg(of image: CGImage, side: Int) -> Data? {
        let scale = min(1, Double(side) / Double(max(image.width, image.height)))
        let width = max(1, Int((Double(image.width) * scale).rounded()))
        let height = max(1, Int((Double(image.height) * scale).rounded()))
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                                      bytesPerRow: 0, space: space,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let small = context.makeImage() else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, small, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }
}
